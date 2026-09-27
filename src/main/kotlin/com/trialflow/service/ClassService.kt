package com.trialflow.service

import com.trialflow.api.badRequest
import com.trialflow.api.conflict
import com.trialflow.api.notFound
import com.trialflow.domain.ClassGroup
import com.trialflow.domain.ClassType
import com.trialflow.domain.ContractStatus
import com.trialflow.domain.Level
import com.trialflow.domain.Subject
import com.trialflow.domain.Textbook
import com.trialflow.infra.LockService
import com.trialflow.store.DataStore
import org.springframework.stereotype.Service

@Service
class ClassService(
    private val store: DataStore,
    private val locks: LockService,
    private val trajectory: TrajectoryService,
    private val trialService: TrialService,
) {

    fun createClassGroup(
        campusId: String, subject: Subject, level: Level, textbook: Textbook, type: ClassType,
        teacherId: String, classroomId: String, capacity: Int, slots: Set<String>, actor: String,
    ): ClassGroup {
        store.campuses[campusId] ?: notFound("校区", campusId)
        val teacher = store.teachers[teacherId] ?: notFound("老师", teacherId)
        val classroom = store.classrooms[classroomId] ?: notFound("教室", classroomId)
        if (classroom.campusId != campusId) badRequest("VALIDATION", "教室不属于该校区")
        if (campusId !in teacher.campusIds) badRequest("VALIDATION", "老师不在该校区授课")
        if (subject !in teacher.subjects) badRequest("VALIDATION", "老师不教授该科目")
        if (capacity <= 0) badRequest("VALIDATION", "班级容量必须为正数")
        if (capacity > classroom.capacity) badRequest("VALIDATION", "班级容量超过教室容纳量")
        val group = ClassGroup(
            store.nextId("CLASS"), campusId, subject, level, textbook, type,
            teacherId, classroomId, capacity, slots.toMutableSet(),
        )
        store.classGroups[group.id] = group
        return group
    }

    fun get(id: String): ClassGroup = store.classGroups[id] ?: notFound("班级", id)

    fun list(campusId: String?): List<ClassGroup> =
        store.classGroups.values.filter { campusId == null || it.campusId == campusId }.sortedBy { it.id }

    // ---------------- 老师管理 ----------------

    fun createTeacher(
        name: String, subjects: Set<Subject>, levels: Set<Level>, textbooks: Set<Textbook>,
        campusIds: Set<String>, availableSlots: Set<String>,
    ): com.trialflow.domain.Teacher {
        campusIds.forEach { store.campuses[it] ?: notFound("校区", it) }
        val teacher = com.trialflow.domain.Teacher(
            store.nextId("T"), name, subjects, levels, textbooks, campusIds,
            availableSlots = availableSlots.toMutableSet(),
        )
        store.teachers[teacher.id] = teacher
        return teacher
    }

    fun listTeachers() = store.teachers.values.sortedBy { it.id }

    fun getTeacher(id: String) = store.teachers[id] ?: notFound("老师", id)

    /**
     * 老师临时请假:取消其待上试听;其班级对应课次标记取消(不消课);
     * 多校区共享老师在任一校区请假,全局时段都不可再排。
     */
    fun teacherLeave(teacherId: String, slots: Set<String>, reason: String, actor: String): Map<String, Any> {
        val teacher = store.teachers[teacherId] ?: notFound("老师", teacherId)
        teacher.leaveSlots.addAll(slots)
        val cancelledTrials = trialService.cancelTrialsOfTeacher(teacherId, slots, "老师请假:$reason", actor)
        val affectedClasses = store.classGroups.values.filter { it.teacherId == teacherId }
        val cancelledSessions = mutableListOf<String>()
        for (g in affectedClasses) {
            val hit = g.slots.intersect(slots)
            if (hit.isNotEmpty()) {
                g.cancelledSlots.addAll(hit)
                cancelledSessions.addAll(hit.map { "${g.id}@$it" })
                for (sid in g.studentIds) {
                    trajectory.record(
                        sid, "LESSON_CANCELLED_BY_TEACHER", actor,
                        "老师请假,课次取消不消课:班级[${g.id}],时段:${hit.joinToString()}",
                        mapOf("classGroupId" to g.id, "slots" to hit.toList(), "teacherId" to teacherId),
                    )
                }
            }
        }
        return mapOf(
            "teacherId" to teacherId,
            "leaveSlots" to teacher.leaveSlots.toList(),
            "cancelledTrials" to cancelledTrials.map { it.id },
            "cancelledClassSessions" to cancelledSessions,
        )
    }

    /** 报名后插班 */
    fun insertStudent(classGroupId: String, studentId: String, actor: String): ClassGroup {
        val group = get(classGroupId)
        store.students[studentId] ?: notFound("学员", studentId)
        val contract = store.contracts.values.find { it.studentId == studentId && it.status == ContractStatus.ACTIVE }
            ?: conflict("NO_ACTIVE_CONTRACT", "学员无生效中合同,不能插班")
        return locks.withLock("class:$classGroupId") {
            validateStudentFitsClass(group.id, studentId, contract.packageId)
            group.studentIds.add(studentId)
            contract.classGroupId = group.id
            trajectory.record(
                studentId, "CLASS_INSERTED", actor,
                "报名后插班:加入班级[${group.id}](${group.subject}/${group.level})",
                mapOf("classGroupId" to group.id, "contractId" to contract.id),
            )
            group
        }
    }

    /** 转班 */
    fun transferStudent(studentId: String, toClassGroupId: String, actor: String): ClassGroup {
        val to = get(toClassGroupId)
        store.students[studentId] ?: notFound("学员", studentId)
        val contract = store.contracts.values.find { it.studentId == studentId && it.status == ContractStatus.ACTIVE }
            ?: conflict("NO_ACTIVE_CONTRACT", "学员无生效中合同,不能转班")
        val from = get(contract.classGroupId)
        if (from.id == to.id) badRequest("SAME_CLASS", "目标班级与当前班级相同")
        return locks.withLocks(listOf("class:${from.id}", "class:${to.id}")) {
            validateStudentFitsClass(to.id, studentId, contract.packageId)
            from.studentIds.remove(studentId)
            to.studentIds.add(studentId)
            contract.classGroupId = to.id
            trajectory.record(
                studentId, "CLASS_TRANSFERRED", actor,
                "转班:[${from.id}] -> [${to.id}]",
                mapOf("fromClassGroupId" to from.id, "toClassGroupId" to to.id, "contractId" to contract.id),
            )
            to
        }
    }

    /** 班级停课 */
    fun suspendClass(classGroupId: String, reason: String, actor: String): ClassGroup {
        val group = get(classGroupId)
        group.suspended = true
        for (sid in group.studentIds) {
            trajectory.record(
                sid, "CLASS_SUSPENDED", actor, "班级停课:[${group.id}],原因:$reason",
                mapOf("classGroupId" to group.id, "reason" to reason),
            )
        }
        return group
    }

    fun resumeClass(classGroupId: String, actor: String): ClassGroup {
        val group = get(classGroupId)
        group.suspended = false
        for (sid in group.studentIds) {
            trajectory.record(sid, "CLASS_RESUMED", actor, "班级复课:[${group.id}]", mapOf("classGroupId" to group.id))
        }
        return group
    }

    private fun validateStudentFitsClass(classGroupId: String, studentId: String, packageId: String) {
        val group = get(classGroupId)
        if (group.suspended) conflict("CLASS_SUSPENDED", "班级已停课,不能加入")
        if (studentId in group.studentIds) conflict("ALREADY_IN_CLASS", "学员已在该班级中")
        if (group.studentIds.size >= group.capacity) conflict("CLASS_FULL", "班级容量已满")
        val pkg = store.packages[packageId] ?: notFound("课包", packageId)
        if (pkg.subject != group.subject || pkg.level != group.level || pkg.textbook != group.textbook) {
            conflict(
                "CLASS_MISMATCH", "学员课包与班级不匹配(科目/水平/教材版本需一致)",
                mapOf(
                    "package" to mapOf("subject" to pkg.subject, "level" to pkg.level, "textbook" to pkg.textbook),
                    "class" to mapOf("subject" to group.subject, "level" to group.level, "textbook" to group.textbook),
                ),
            )
        }
    }
}
