package com.trialflow.api

import com.trialflow.domain.ClassType
import com.trialflow.domain.ConsumptionRules
import com.trialflow.domain.Installment
import com.trialflow.domain.Level
import com.trialflow.domain.Promise
import com.trialflow.domain.PromiseType
import com.trialflow.domain.Subject
import com.trialflow.domain.Textbook
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull

// ---------- 线索 ----------
data class CreateLeadReq(
    @field:NotBlank val studentName: String,
    @field:NotBlank val grade: String,
    @field:NotNull val subject: Subject,
    @field:NotNull val level: Level,
    val textbook: Textbook = Textbook.PEP,
    @field:NotEmpty val availableSlots: Set<String>,
    @field:NotBlank val channel: String,
    @field:NotBlank val consultantId: String,
    @field:NotBlank val campusId: String,
    val activityParticipated: Boolean = false,
)

data class TransferCampusReq(@field:NotBlank val toCampusId: String, val reason: String? = null)
data class ReasonReq(val reason: String? = null)

// ---------- 试听 ----------
data class BookTrialReq(
    @field:NotBlank val leadId: String,
    @field:NotBlank val slot: String,
    val teacherId: String? = null,
    val classroomId: String? = null,
    val classGroupId: String? = null,
)

data class RescheduleTrialReq(@field:NotBlank val newSlot: String)

data class AttendTrialReq(val giftName: String? = null, val giftValueCents: Long? = null)

data class FeedbackReq(
    @field:NotBlank val teacherId: String,
    @field:NotBlank val performance: String,
    @field:Min(0) val assessmentScore: Int,
    @field:NotNull val suitableClassType: ClassType,
    val riskWarnings: List<String> = emptyList(),
)

// ---------- 合同 ----------
data class InstallmentReq(@field:Min(1) val no: Int, @field:NotBlank val dueDate: String, @field:Min(1) val amountCents: Long) {
    fun toDomain() = Installment(no, dueDate, amountCents)
}

data class PromiseReq(
    @field:NotNull val type: PromiseType,
    @field:NotBlank val content: String,
    val refValue: String? = null,
    @field:NotBlank val promisedBy: String,
) {
    fun toDomain() = Promise(type, content, refValue, promisedBy)
}

data class ConsumptionRulesReq(
    val absentConsumes: Boolean = true,
    val leaveAdvanceHours: Long = 24,
    val giftFirst: Boolean = false,
    val makeupAllowed: Boolean = true,
) {
    fun toDomain() = ConsumptionRules(absentConsumes, leaveAdvanceHours, giftFirst, makeupAllowed)
}

data class CreateContractReq(
    @field:NotBlank val leadId: String,
    @field:NotBlank val packageId: String,
    @field:NotBlank val classGroupId: String,
    @field:Min(0) val giftLessons: Int = 0,
    @field:Min(0) val discountCents: Long = 0,
    @field:NotEmpty val installments: List<InstallmentReq>,
    /** 创建时已缴的分期数(从第 1 期起连续) */
    @field:Min(0) val paidInstallments: Int = 0,
    val textbookDelivered: Boolean = false,
    val promises: List<PromiseReq> = emptyList(),
    val rules: ConsumptionRulesReq = ConsumptionRulesReq(),
    /** 承诺与试听反馈不一致时,是否人工确认继续签约 */
    val acknowledgeMismatch: Boolean = false,
)

data class PayInstallmentReq(@field:Min(1) val no: Int)

// ---------- 班级 / 老师 ----------
data class CreateClassReq(
    @field:NotBlank val campusId: String,
    @field:NotNull val subject: Subject,
    @field:NotNull val level: Level,
    val textbook: Textbook = Textbook.PEP,
    val type: ClassType = ClassType.SMALL,
    @field:NotBlank val teacherId: String,
    @field:NotBlank val classroomId: String,
    @field:Min(1) val capacity: Int,
    @field:NotEmpty val slots: Set<String>,
)

data class InsertStudentReq(@field:NotBlank val studentId: String)
data class TransferStudentReq(@field:NotBlank val toClassGroupId: String)
data class TeacherLeaveReq(@field:NotEmpty val slots: Set<String>, @field:NotBlank val reason: String)

data class CreateTeacherReq(
    @field:NotBlank val name: String,
    @field:NotEmpty val subjects: Set<Subject>,
    @field:NotEmpty val levels: Set<Level>,
    @field:NotEmpty val textbooks: Set<Textbook>,
    @field:NotEmpty val campusIds: Set<String>,
    @field:NotEmpty val availableSlots: Set<String>,
)

// ---------- 课消 ----------
data class LessonReq(@field:NotBlank val slot: String)
data class LeaveLessonReq(
    @field:NotBlank val slot: String,
    /** 请假发起时间(ISO-8601),用于判定是否提前 leaveAdvanceHours 小时 */
    @field:NotBlank val requestedAt: String,
    val reason: String? = null,
)

// ---------- 退费 / 投诉 / 争议 ----------
data class RefundReq(
    @field:NotBlank val reason: String,
    /** 体验课赠品是否已退回 */
    val giftReturned: Boolean = false,
)

data class ComplaintReq(@field:NotBlank val content: String)
data class ResolveReq(@field:NotNull val upheld: Boolean?, val note: String? = null)
data class GiftDisputeReq(@field:Min(0) val claimedGiftLessons: Int, @field:NotBlank val reason: String)
data class ResolveDisputeReq(@field:NotNull val approve: Boolean?, val note: String? = null)
