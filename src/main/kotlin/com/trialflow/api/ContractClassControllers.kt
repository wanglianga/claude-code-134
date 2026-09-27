package com.trialflow.api

import com.trialflow.service.ClassService
import com.trialflow.service.ConsumptionService
import com.trialflow.service.ContractService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/api/contracts")
class ContractController(private val contractService: ContractService) {

    /** 报名转化:同一学员账户绑定课包/优惠/赠课/分期/发票/班级/课消规则 */
    @PostMapping
    fun create(
        @Valid @RequestBody req: CreateContractReq,
        @RequestHeader("X-Idempotency-Key", required = false) idemKey: String?,
        @RequestHeader("X-Actor-Id", required = false) actor: String?,
    ): ResponseEntity<Any> {
        val result = contractService.create(
            req.leadId, req.packageId, req.classGroupId, req.giftLessons, req.discountCents,
            req.installments.map { it.toDomain() }, req.paidInstallments,
            req.textbookDelivered, req.promises.map { it.toDomain() }, req.rules.toDomain(),
            req.acknowledgeMismatch, idemKey, actor ?: "system",
        )
        val status = if (result.replayed) HttpStatus.OK else HttpStatus.CREATED
        return ResponseEntity.status(status)
            .header("X-Idempotent-Replay", result.replayed.toString())
            .body(result.contract as Any)
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: String) = contractService.get(id)

    /** 承诺与试听反馈一致性核对结果 */
    @GetMapping("/{id}/promise-check")
    fun promiseCheck(@PathVariable id: String) = contractService.get(id).promiseCheck

    @PostMapping("/{id}/pay-installment")
    fun payInstallment(
        @PathVariable id: String,
        @Valid @RequestBody req: PayInstallmentReq,
        @RequestHeader("X-Actor-Id", required = false) actor: String?,
    ) = contractService.payInstallment(id, req.no, actor ?: "system")

    @PostMapping("/{id}/invoice")
    fun issueInvoice(
        @PathVariable id: String,
        @RequestHeader("X-Actor-Id", required = false) actor: String?,
    ) = contractService.issueInvoice(id, actor ?: "system")

    /** 停课 */
    @PostMapping("/{id}/suspend")
    fun suspend(
        @PathVariable id: String,
        @RequestBody req: ReasonReq,
        @RequestHeader("X-Actor-Id", required = false) actor: String?,
    ) = contractService.suspend(id, req.reason ?: "未填写", actor ?: "system")

    /** 复课 */
    @PostMapping("/{id}/resume")
    fun resume(
        @PathVariable id: String,
        @RequestHeader("X-Actor-Id", required = false) actor: String?,
    ) = contractService.resume(id, actor ?: "system")
}

@RestController
@RequestMapping("/api/classes")
class ClassController(private val classService: ClassService) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @Valid @RequestBody req: CreateClassReq,
        @RequestHeader("X-Actor-Id", required = false) actor: String?,
    ) = classService.createClassGroup(
        req.campusId, req.subject, req.level, req.textbook, req.type,
        req.teacherId, req.classroomId, req.capacity, req.slots, actor ?: "system",
    )

    @GetMapping
    fun list(@RequestParam(required = false) campusId: String?) = classService.list(campusId)

    @GetMapping("/{id}")
    fun get(@PathVariable id: String) = classService.get(id)

    /** 报名后插班 */
    @PostMapping("/{id}/insert")
    fun insert(
        @PathVariable id: String,
        @Valid @RequestBody req: InsertStudentReq,
        @RequestHeader("X-Actor-Id", required = false) actor: String?,
    ) = classService.insertStudent(id, req.studentId, actor ?: "system")

    /** 班级停课/复课 */
    @PostMapping("/{id}/suspend")
    fun suspend(
        @PathVariable id: String,
        @RequestBody req: ReasonReq,
        @RequestHeader("X-Actor-Id", required = false) actor: String?,
    ) = classService.suspendClass(id, req.reason ?: "未填写", actor ?: "system")

    @PostMapping("/{id}/resume")
    fun resume(
        @PathVariable id: String,
        @RequestHeader("X-Actor-Id", required = false) actor: String?,
    ) = classService.resumeClass(id, actor ?: "system")
}

@RestController
@RequestMapping("/api/students")
class StudentClassController(
    private val classService: ClassService,
    private val consumptionService: ConsumptionService,
) {

    /** 转班 */
    @PostMapping("/{id}/transfer")
    fun transfer(
        @PathVariable id: String,
        @Valid @RequestBody req: TransferStudentReq,
        @RequestHeader("X-Actor-Id", required = false) actor: String?,
    ) = classService.transferStudent(id, req.toClassGroupId, actor ?: "system")

    /** 到课消课 */
    @PostMapping("/{id}/lessons/complete")
    fun complete(
        @PathVariable id: String,
        @Valid @RequestBody req: LessonReq,
        @RequestHeader("X-Actor-Id", required = false) actor: String?,
    ) = consumptionService.completeLesson(id, req.slot, actor ?: "system")

    /** 补课消课 */
    @PostMapping("/{id}/lessons/makeup")
    fun makeup(
        @PathVariable id: String,
        @Valid @RequestBody req: LessonReq,
        @RequestHeader("X-Actor-Id", required = false) actor: String?,
    ) = consumptionService.makeupLesson(id, req.slot, actor ?: "system")

    /** 课程请假 */
    @PostMapping("/{id}/lessons/leave")
    fun leave(
        @PathVariable id: String,
        @Valid @RequestBody req: LeaveLessonReq,
        @RequestHeader("X-Actor-Id", required = false) actor: String?,
    ) = consumptionService.leaveLesson(id, req.slot, Instant.parse(req.requestedAt), req.reason, actor ?: "system")

    /** 学生缺席 */
    @PostMapping("/{id}/lessons/absent")
    fun absent(
        @PathVariable id: String,
        @Valid @RequestBody req: LessonReq,
        @RequestHeader("X-Actor-Id", required = false) actor: String?,
    ) = consumptionService.absentLesson(id, req.slot, actor ?: "system")

    /** 课消账户 */
    @GetMapping("/{id}/account")
    fun account(@PathVariable id: String) = consumptionService.accountOf(id)
}

@RestController
@RequestMapping("/api/teachers")
class TeacherController(private val classService: ClassService) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody req: CreateTeacherReq) = classService.createTeacher(
        req.name, req.subjects, req.levels, req.textbooks, req.campusIds, req.availableSlots,
    )

    @GetMapping
    fun list() = classService.listTeachers()

    @GetMapping("/{id}")
    fun get(@PathVariable id: String) = classService.getTeacher(id)

    /** 老师临时请假:联动取消试听与班级课次 */
    @PostMapping("/{id}/leave")
    fun leave(
        @PathVariable id: String,
        @Valid @RequestBody req: TeacherLeaveReq,
        @RequestHeader("X-Actor-Id", required = false) actor: String?,
    ) = classService.teacherLeave(id, req.slots, req.reason, actor ?: "system")
}
