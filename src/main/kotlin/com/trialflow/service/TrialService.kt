package com.trialflow.service

import com.trialflow.api.badRequest
import com.trialflow.api.conflict
import com.trialflow.api.notFound
import com.trialflow.domain.Gift
import com.trialflow.domain.Lead
import com.trialflow.domain.LeadStatus
import com.trialflow.domain.TrialClass
import com.trialflow.domain.TrialFeedback
import com.trialflow.domain.TrialStatus
import com.trialflow.domain.ClassType
import com.trialflow.infra.LockService
import com.trialflow.store.DataStore
import org.springframework.stereotype.Service
import java.time.Instant

data class MatchOption(
    val teacherId: String,
    val teacherName: String,
    val classroomId: String,
    val classroomName: String,
    val classGroupId: String?,
    val classRemainingCapacity: Int?,
)

@Service
class TrialService(
    private val store: DataStore,
    private val locks: LockService,
    private val trajectory: TrajectoryService,
) {

    /** 老师在正式班级已承诺的课次(跨校区),1v1 试听需避开 */
    private fun teacherClassSlots(teacherId: String): Set<String> =
        store.classGroups.values.filter { it.teacherId == teacherId }.flatMap { it.slots }.toSet()

    /** 某教室在某时段是否被正式班占用 */
    private fun classroomHasClass(classroomId: String, slot: String): Boolean =
        store.classGroups.values.any { it.classroomId == classroomId && slot in it.slots }

    /** 班级某课次剩余容量 = 容量 - 正式学员 - 已约试听生 */
    private fun classRemaining(classGroupId: String, slot: String): Int {
        val g = store.classGroups.getValue(classGroupId)
        val trialSeats = store.classTrialSeats["$classGroupId@$slot"]?.size ?: 0
        return g.capacity - g.studentIds.size - trialSeats
    }

    /**
     * 试听匹配:老师空档(科目/水平/教材版本/校区/时段空闲且未请假、不与常规班冲突)
     * × 教室空闲 × 可选班级容量(若挂到正式班试听)。
     */
    fun match(leadId: String, slot: String): List<MatchOption> {
        val lead = getLead(leadId)
        val teachers = store.teachers.values.filter { t ->
            lead.subject in t.subjects &&
                lead.level in t.levels &&
                lead.textbook in t.textbooks &&
                lead.campusId in t.campusIds &&
                slot in t.availableSlots &&
                slot !in t.busySlots &&
                slot !in t.leaveSlots
        }
        val freeClassrooms = store.classrooms.values.filter { c ->
            c.campusId == lead.campusId &&
                slot !in (store.classroomBusy[c.id] ?: emptySet()) &&
                !classroomHasClass(c.id, slot)
        }
        val candidateClasses = store.classGroups.values.filter { g ->
            g.campusId == lead.campusId && !g.suspended &&
                g.subject == lead.subject && g.level == lead.level && g.textbook == lead.textbook &&
                slot in g.slots && classRemaining(g.id, slot) > 0
        }
        val options = mutableListOf<MatchOption>()
        // 优先推荐可插入的正式班(班级老师需同时满足老师匹配条件)
        for (g in candidateClasses) {
            val t = store.teachers[g.teacherId] ?: continue
            if (teachers.none { it.id == t.id }) continue
            options.add(
                MatchOption(
                    t.id, t.name, g.classroomId,
                    store.classrooms[g.classroomId]?.name ?: g.classroomId,
                    g.id, classRemaining(g.id, slot),
                ),
            )
        }
        // 一对一/教室试听组合(需避开老师常规班课次)
        for (t in teachers) {
            if (slot in teacherClassSlots(t.id)) continue
            for (c in freeClassrooms) {
                if (options.any { it.teacherId == t.id && it.classroomId == c.id }) continue
                options.add(MatchOption(t.id, t.name, c.id, c.name, null, null))
            }
        }
        return options
    }

    /** 预约试听;teacherId/classroomId 缺省时自动取第一个匹配项 */
    fun book(
        leadId: String, slot: String,
        teacherId: String?, classroomId: String?, classGroupId: String?,
        actor: String,
    ): TrialClass {
        val lead = getLead(leadId)
        if (lead.status !in setOf(LeadStatus.NEW, LeadStatus.TRIAL_NO_SHOW, LeadStatus.TRIAL_BOOKED)) {
            conflict("LEAD_STATUS", "当前线索状态不允许预约试听: ${lead.status}")
        }
        if (slot !in lead.availableSlots) {
            badRequest("SLOT_NOT_AVAILABLE", "该时段不在家长可上课时间内", mapOf("availableSlots" to lead.availableSlots))
        }
        val resolved = resolveOption(lead, slot, teacherId, classroomId, classGroupId)
        return locks.withLocks(lockKeysOf(resolved, slot)) {
            validateBookable(slot, resolved)
            occupy(resolved, slot)
            val trial = TrialClass(
                id = store.nextId("TRIAL"), leadId = lead.id, campusId = lead.campusId,
                teacherId = resolved.teacherId, classroomId = resolved.classroomId,
                classGroupId = resolved.classGroupId, slot = slot, bookedAt = Instant.now().toString(),
            )
            store.trials[trial.id] = trial
            if (resolved.classGroupId != null) {
                store.classTrialSeats.computeIfAbsent("${resolved.classGroupId}@$slot") { mutableSetOf() }.add(trial.id)
            }
            lead.status = LeadStatus.TRIAL_BOOKED
            val teacher = store.teachers.getValue(resolved.teacherId)
            trajectory.record(
                lead.id, "TRIAL_BOOKED", actor,
                "预约试听:$slot,老师[${teacher.name}],教室[${resolved.classroomId}]" +
                    (resolved.classGroupId?.let { ",随班[$it]" } ?: ",1v1"),
                mapOf(
                    "trialId" to trial.id, "slot" to slot, "teacherId" to resolved.teacherId,
                    "classroomId" to resolved.classroomId, "classGroupId" to resolved.classGroupId,
                    "campusId" to lead.campusId,
                ),
            )
            trial
        }
    }

    /** 改约试听:释放旧占用,占用新时段;多次改约记录风险轨迹 */
    fun reschedule(trialId: String, newSlot: String, actor: String): TrialClass {
        val trial = getTrial(trialId)
        if (trial.status != TrialStatus.BOOKED) conflict("TRIAL_STATUS", "只有待上课的试听可以改约: ${trial.status}")
        val lead = getLead(trial.leadId)
        if (newSlot !in lead.availableSlots) {
            badRequest("SLOT_NOT_AVAILABLE", "新时段不在家长可上课时间内", mapOf("availableSlots" to lead.availableSlots))
        }
        val resolved = resolveOption(lead, newSlot, null, null, trial.classGroupId)
        return locks.withLocks(lockKeysOf(resolved, newSlot)) {
            validateBookable(newSlot, resolved)
            releaseOccupancy(trial)
            occupy(resolved, newSlot)
            val rescheduled = TrialClass(
                id = store.nextId("TRIAL"), leadId = lead.id, campusId = lead.campusId,
                teacherId = resolved.teacherId, classroomId = resolved.classroomId,
                classGroupId = resolved.classGroupId, slot = newSlot, bookedAt = Instant.now().toString(),
            )
            store.trials[rescheduled.id] = rescheduled
            if (resolved.classGroupId != null) {
                store.classTrialSeats.computeIfAbsent("${resolved.classGroupId}@$newSlot") { mutableSetOf() }.add(rescheduled.id)
            }
            trial.status = TrialStatus.CANCELLED
            trial.cancelReason = "改约到 $newSlot"
            lead.rescheduleCount += 1
            trajectory.record(
                lead.id, "TRIAL_RESCHEDULED", actor,
                "试听改约:${trial.slot} -> $newSlot(第${lead.rescheduleCount}次改约)",
                mapOf(
                    "oldTrialId" to trial.id, "newTrialId" to rescheduled.id,
                    "oldSlot" to trial.slot, "newSlot" to newSlot,
                    "rescheduleCount" to lead.rescheduleCount,
                ),
            )
            if (lead.rescheduleCount >= 2) {
                trajectory.record(
                    lead.id, "RISK_FLAG", actor,
                    "家长多次改约试听(${lead.rescheduleCount}次),到课风险上升",
                    mapOf("rescheduleCount" to lead.rescheduleCount),
                )
            }
            rescheduled
        }
    }

    /** 试听到课,可登记发放的体验课赠品 */
    fun attend(trialId: String, giftName: String?, giftValueCents: Long?, actor: String): TrialClass {
        val trial = getTrial(trialId)
        if (trial.status != TrialStatus.BOOKED) conflict("TRIAL_STATUS", "试听不是待上课状态: ${trial.status}")
        trial.status = TrialStatus.DONE
        releaseOccupancy(trial)
        val lead = getLead(trial.leadId)
        lead.status = LeadStatus.TRIAL_DONE
        if (giftName != null) {
            lead.experienceGift = Gift(giftName, giftValueCents ?: 0L)
        }
        trajectory.record(
            lead.id, "TRIAL_ATTENDED", actor,
            "试听到课:$trialId" + (giftName?.let { ",发放赠品[$it](价值${(giftValueCents ?: 0) / 100.0}元)" } ?: ""),
            mapOf("trialId" to trialId, "gift" to giftName, "giftValueCents" to giftValueCents),
        )
        return trial
    }

    /** 试听缺席 */
    fun absent(trialId: String, actor: String): TrialClass {
        val trial = getTrial(trialId)
        if (trial.status != TrialStatus.BOOKED) conflict("TRIAL_STATUS", "试听不是待上课状态: ${trial.status}")
        trial.status = TrialStatus.NO_SHOW
        releaseOccupancy(trial)
        val lead = getLead(trial.leadId)
        lead.status = LeadStatus.TRIAL_NO_SHOW
        trajectory.record(
            lead.id, "TRIAL_NO_SHOW", actor,
            "试听缺席:$trialId,时段 ${trial.slot}",
            mapOf("trialId" to trialId, "slot" to trial.slot),
        )
        return trial
    }

    fun cancel(trialId: String, reason: String, actor: String): TrialClass {
        val trial = getTrial(trialId)
        if (trial.status != TrialStatus.BOOKED) conflict("TRIAL_STATUS", "试听不是待上课状态: ${trial.status}")
        doCancelTrial(trial, reason, actor)
        return trial
    }

    /** 老师提交试听反馈:课堂表现/测评结果/适合班型/风险提醒 */
    fun submitFeedback(
        trialId: String, teacherId: String, performance: String, assessmentScore: Int,
        suitableClassType: ClassType, riskWarnings: List<String>, actor: String,
    ): TrialFeedback {
        val trial = getTrial(trialId)
        if (trial.status != TrialStatus.DONE) conflict("TRIAL_STATUS", "试听到课后才能提交反馈: ${trial.status}")
        if (trial.teacherId != teacherId) badRequest("TEACHER_MISMATCH", "只能由本次试听授课老师提交反馈")
        if (assessmentScore !in 0..100) badRequest("VALIDATION", "测评结果需在 0-100 之间")
        if (store.feedbacks.containsKey(trialId)) conflict("FEEDBACK_EXISTS", "该试听已提交过反馈")
        val feedback = TrialFeedback(
            trialId, teacherId, performance, assessmentScore, suitableClassType,
            riskWarnings, Instant.now().toString(),
        )
        store.feedbacks[trialId] = feedback
        trajectory.record(
            trial.leadId, "TRIAL_FEEDBACK", actor,
            "试听反馈:测评$assessmentScore 分,适合班型[$suitableClassType]" +
                (if (riskWarnings.isEmpty()) "" else ",风险提醒:${riskWarnings.joinToString("/")}"),
            mapOf(
                "trialId" to trialId, "assessmentScore" to assessmentScore,
                "suitableClassType" to suitableClassType.name, "riskWarnings" to riskWarnings,
                "performance" to performance,
            ),
        )
        return feedback
    }

    fun getTrial(id: String): TrialClass = store.trials[id] ?: notFound("试听", id)

    fun feedbackOf(trialId: String): TrialFeedback? = store.feedbacks[trialId]

    /** 老师请假时由 ClassService 调用:取消该老师这些时段的待上试听 */
    internal fun cancelTrialsOfTeacher(teacherId: String, slots: Set<String>, reason: String, actor: String): List<TrialClass> {
        val affected = store.trials.values.filter {
            it.teacherId == teacherId && it.status == TrialStatus.BOOKED && it.slot in slots
        }
        for (trial in affected) {
            doCancelTrial(trial, reason, actor)
        }
        return affected
    }

    internal fun doCancelTrial(trial: TrialClass, reason: String, actor: String) {
        trial.status = TrialStatus.CANCELLED
        trial.cancelReason = reason
        releaseOccupancy(trial)
        val lead = store.leads[trial.leadId]
        if (lead != null && lead.status == LeadStatus.TRIAL_BOOKED) lead.status = LeadStatus.NEW
        trajectory.record(
            trial.leadId, "TRIAL_CANCELLED", actor,
            "试听取消:${trial.slot},原因:$reason",
            mapOf("trialId" to trial.id, "slot" to trial.slot, "reason" to reason),
        )
    }

    // ---------------- 占用管理 ----------------

    private fun lockKeysOf(option: MatchOption, slot: String): List<String> {
        val keys = mutableListOf("teacher:${option.teacherId}:$slot", "classroom:${option.classroomId}:$slot")
        option.classGroupId?.let { keys.add("class:$it") }
        return keys
    }

    /** 占用资源:随班试听占班级座位(座位在 book 中登记);1v1 试听占老师时段与教室 */
    private fun occupy(option: MatchOption, slot: String) {
        if (option.classGroupId == null) {
            store.teachers.getValue(option.teacherId).busySlots.add(slot)
            store.classroomBusy.computeIfAbsent(option.classroomId) { mutableSetOf() }.add(slot)
        }
    }

    private fun releaseOccupancy(trial: TrialClass) {
        if (trial.classGroupId == null) {
            store.teachers[trial.teacherId]?.busySlots?.remove(trial.slot)
            store.classroomBusy[trial.classroomId]?.remove(trial.slot)
        } else {
            store.classTrialSeats["${trial.classGroupId}@${trial.slot}"]?.remove(trial.id)
        }
    }

    private fun resolveOption(
        lead: Lead, slot: String,
        teacherId: String?, classroomId: String?, classGroupId: String?,
    ): MatchOption {
        val options = match(lead.id, slot)
        if (options.isEmpty()) {
            conflict(
                "NO_MATCH", "该时段无可用排课资源(老师空档/教室/班级容量/教材版本/学生水平需同时满足)",
                mapOf("slot" to slot, "campusId" to lead.campusId),
            )
        }
        val filtered = options.filter {
            (teacherId == null || it.teacherId == teacherId) &&
                (classroomId == null || it.classroomId == classroomId) &&
                (classGroupId == null || it.classGroupId == classGroupId)
        }
        return filtered.firstOrNull()
            ?: conflict(
                "NO_MATCH", "指定的老师/教室/班级在该时段不可用",
                mapOf("slot" to slot, "teacherId" to teacherId, "classroomId" to classroomId, "classGroupId" to classGroupId),
            )
    }

    /** 持锁后二次校验(匹配与加锁之间的并发窗口) */
    private fun validateBookable(slot: String, option: MatchOption) {
        val teacher = store.teachers.getValue(option.teacherId)
        if (slot in teacher.leaveSlots) conflict("TEACHER_ON_LEAVE", "老师该时段已请假")
        if (option.classGroupId == null) {
            if (slot in teacher.busySlots) conflict("TEACHER_BUSY", "老师该时段已被占用(可能跨校区)")
            if (slot in teacherClassSlots(teacher.id)) conflict("TEACHER_BUSY", "老师该时段有常规班课,不能排 1v1 试听")
            if (slot in (store.classroomBusy[option.classroomId] ?: emptySet())) {
                conflict("CLASSROOM_BUSY", "教室该时段已被占用")
            }
            if (classroomHasClass(option.classroomId, slot)) conflict("CLASSROOM_BUSY", "教室该时段有班级上课")
        } else {
            val g = store.classGroups[option.classGroupId] ?: notFound("班级", option.classGroupId)
            if (g.suspended) conflict("CLASS_SUSPENDED", "班级已停课")
            if (classRemaining(g.id, slot) <= 0) conflict("CLASS_FULL", "班级该课次容量已满")
        }
    }

    private fun getLead(id: String): Lead = store.leads[id] ?: notFound("线索", id)
}
