package com.trialflow.service

import com.trialflow.api.conflict
import com.trialflow.api.notFound
import com.trialflow.domain.ConsumptionAccount
import com.trialflow.domain.ContractStatus
import com.trialflow.domain.LessonEvent
import com.trialflow.domain.LessonEventType
import com.trialflow.domain.LessonKind
import com.trialflow.infra.LockService
import com.trialflow.store.DataStore
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/** 课消:到课/请假/缺席/补课,消耗规则绑定在合同上 */
@Service
class ConsumptionService(
    private val store: DataStore,
    private val locks: LockService,
    private val trajectory: TrajectoryService,
) {

    fun accountOf(studentId: String): ConsumptionAccount =
        store.accounts[studentId] ?: notFound("课消账户", studentId)

    /** 到课消课 */
    fun completeLesson(studentId: String, slot: String, actor: String): ConsumptionAccount =
        consume(studentId, slot, LessonEventType.CONSUMED, actor, "到课消课")

    /** 补课消课(补课时段不限于班级常规课次) */
    fun makeupLesson(studentId: String, slot: String, actor: String): ConsumptionAccount {
        accountOf(studentId)
        val contract = activeContractOf(studentId)
        if (!contract.rules.makeupAllowed) conflict("MAKEUP_NOT_ALLOWED", "该合同不允许补课")
        return consume(studentId, slot, LessonEventType.MAKEUP_CONSUMED, actor, "补课消课", requireScheduledSlot = false)
    }

    /**
     * 课程请假:提前 leaveAdvanceHours 小时以上请假不消课,否则按规则消课。
     * requestedAt 为请假发起时间(ISO-8601),slot 为课次开始时间(本地日期时间)。
     */
    fun leaveLesson(studentId: String, slot: String, requestedAt: Instant, reason: String?, actor: String): ConsumptionAccount {
        val account = accountOf(studentId)
        val contract = activeContractOf(studentId)
        checkLessonCancellable(studentId, contract.classGroupId, slot)
        val lessonStart = LocalDateTime.parse(slot).toInstant(ZoneOffset.UTC)
        val hoursAhead = Duration.between(requestedAt, lessonStart).toHours()
        return locks.withLock("account:$studentId") {
            account.leaveCount += 1
            if (hoursAhead >= contract.rules.leaveAdvanceHours) {
                account.events.add(LessonEvent(store.nextId("LE"), LessonEventType.LEAVE_EXCUSED, null, slot, requestedAt.toString(), reason))
                trajectory.record(
                    studentId, "LESSON_LEAVE", actor,
                    "课程请假(提前${hoursAhead}h,不消课):$slot${reason?.let { ",原因:$it" } ?: ""}",
                    mapOf("slot" to slot, "hoursAhead" to hoursAhead, "consumed" to false, "reason" to reason),
                )
            } else {
                val kind = consumeOne(account, contract.rules.giftFirst)
                account.events.add(LessonEvent(store.nextId("LE"), LessonEventType.LEAVE_LATE_CONSUMED, kind, slot, requestedAt.toString(), reason))
                trajectory.record(
                    studentId, "LESSON_LEAVE_LATE", actor,
                    "临时请假(提前${hoursAhead}h < ${contract.rules.leaveAdvanceHours}h,按规则消${kind}课):$slot",
                    mapOf("slot" to slot, "hoursAhead" to hoursAhead, "consumed" to true, "kind" to kind.name),
                )
            }
            account
        }
    }

    /** 学生缺席:按合同规则决定是否消课 */
    fun absentLesson(studentId: String, slot: String, actor: String): ConsumptionAccount {
        val account = accountOf(studentId)
        val contract = activeContractOf(studentId)
        checkLessonCancellable(studentId, contract.classGroupId, slot)
        return locks.withLock("account:$studentId") {
            account.absentCount += 1
            if (contract.rules.absentConsumes) {
                val kind = consumeOne(account, contract.rules.giftFirst)
                account.events.add(LessonEvent(store.nextId("LE"), LessonEventType.ABSENT_CONSUMED, kind, slot, Instant.now().toString(), "缺席消课"))
                trajectory.record(
                    studentId, "LESSON_ABSENT", actor, "学生缺席,按规则消${kind}课:$slot",
                    mapOf("slot" to slot, "consumed" to true, "kind" to kind.name),
                )
            } else {
                account.events.add(LessonEvent(store.nextId("LE"), LessonEventType.ABSENT_EXCUSED, null, slot, Instant.now().toString(), "缺席不消课"))
                trajectory.record(
                    studentId, "LESSON_ABSENT", actor, "学生缺席,规则不消课:$slot",
                    mapOf("slot" to slot, "consumed" to false),
                )
            }
            account
        }
    }

    private fun consume(
        studentId: String, slot: String, type: LessonEventType, actor: String, note: String,
        requireScheduledSlot: Boolean = true,
    ): ConsumptionAccount {
        val account = accountOf(studentId)
        val contract = activeContractOf(studentId)
        checkLessonCancellable(studentId, contract.classGroupId, slot, requireScheduledSlot)
        return locks.withLock("account:$studentId") {
            val kind = consumeOne(account, contract.rules.giftFirst)
            account.events.add(LessonEvent(store.nextId("LE"), type, kind, slot, Instant.now().toString(), note))
            trajectory.record(
                studentId, "LESSON_CONSUMED", actor,
                "$note:$slot,剩余正课${account.remainingPaid}节/赠课${account.remainingGift}节",
                mapOf(
                    "slot" to slot, "kind" to kind.name, "type" to type.name,
                    "remainingPaid" to account.remainingPaid, "remainingGift" to account.remainingGift,
                ),
            )
            account
        }
    }

    /** 消耗一节课:默认先正课后赠课,giftFirst 时相反;无剩余课时抛冲突 */
    private fun consumeOne(account: ConsumptionAccount, giftFirst: Boolean): LessonKind {
        val order = if (giftFirst) listOf(LessonKind.GIFT, LessonKind.PAID) else listOf(LessonKind.PAID, LessonKind.GIFT)
        for (kind in order) {
            when (kind) {
                LessonKind.PAID -> if (account.remainingPaid > 0) {
                    account.consumedPaid += 1
                    return kind
                }
                LessonKind.GIFT -> if (account.remainingGift > 0) {
                    account.consumedGift += 1
                    return kind
                }
            }
        }
        conflict("NO_LESSONS_LEFT", "课消账户已无剩余课时")
    }

    private fun activeContractOf(studentId: String) =
        store.contracts.values.find { it.studentId == studentId && it.status == ContractStatus.ACTIVE }
            ?: conflict("NO_ACTIVE_CONTRACT", "学员无生效中合同(可能已停课/退费),不能课消")

    private fun checkLessonCancellable(studentId: String, classGroupId: String, slot: String, requireScheduledSlot: Boolean = true) {
        val group = store.classGroups[classGroupId] ?: notFound("班级", classGroupId)
        if (group.suspended) conflict("CLASS_SUSPENDED", "班级已停课,不能课消")
        if (slot in group.cancelledSlots) conflict("LESSON_CANCELLED", "该课次已因老师请假取消,不能课消")
        if (requireScheduledSlot && slot !in group.slots) conflict("NOT_CLASS_SESSION", "该时段不是班级课次")
        if (studentId !in group.studentIds) conflict("NOT_IN_CLASS", "学员不在当前班级中")
    }
}
