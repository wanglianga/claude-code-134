package com.trialflow.domain

// ---------------- 基础枚举 ----------------

enum class Subject { MATH, ENGLISH, CHINESE, PHYSICS, CHEMISTRY }

enum class Level { BEGINNER, ELEMENTARY, INTERMEDIATE, ADVANCED }

/** 教材版本 */
enum class Textbook { PEP, BNU, SJ, OTHER }

/** 班型 */
enum class ClassType { ONE_ON_ONE, SMALL, MEDIUM, LECTURE }

enum class Role { ADMIN, PRINCIPAL, CONSULTANT, TEACHER }

enum class LeadStatus {
    NEW,            // 新线索
    TRIAL_BOOKED,   // 已约试听
    TRIAL_DONE,     // 试听到课
    TRIAL_NO_SHOW,  // 试听缺席
    CONVERTED,      // 已转化为正式学员
    INVALID         // 无效线索
}

enum class TrialStatus { BOOKED, DONE, NO_SHOW, CANCELLED }

enum class ContractStatus { ACTIVE, SUSPENDED, CLOSED_REFUND, CLOSED_DONE }

enum class LessonKind { PAID, GIFT }

enum class LessonEventType {
    CONSUMED,             // 正常到课消课
    LEAVE_EXCUSED,        // 提前请假,不消课
    LEAVE_LATE_CONSUMED,  // 临时请假,按规则消课
    ABSENT_CONSUMED,      // 缺席,按规则消课
    ABSENT_EXCUSED,       // 缺席但不消课(规则允许)
    MAKEUP_CONSUMED,      // 补课消课
    TEACHER_CANCELLED,    // 老师请假,课程取消不消课
    GIFT_VOIDED           // 退费时剩余赠课作废
}

enum class CommissionStatus { ACCRUED, FROZEN, CLAWED_BACK }

enum class InvoiceStatus { ISSUED, RED_FLUSHED }

enum class ComplaintStatus { OPEN, UPHELD, REJECTED }

enum class PromiseCheckStatus { CONSISTENT, MISMATCH, NO_FEEDBACK }

enum class PromiseType { TEACHER, CLASS_TYPE, SCORE_TARGET, SCHEDULE, OTHER }

// ---------------- 组织与资源 ----------------

data class Campus(val id: String, val name: String)

data class Classroom(val id: String, val campusId: String, val name: String, val capacity: Int)

data class UserAccount(val id: String, val name: String, val role: Role, val campusIds: Set<String> = emptySet())

/** 老师:可多校区共享,busySlots 跨校区全局生效 */
class Teacher(
    val id: String,
    val name: String,
    val subjects: Set<Subject>,
    val levels: Set<Level>,
    val textbooks: Set<Textbook>,
    val campusIds: Set<String>,
    val availableSlots: MutableSet<String> = mutableSetOf(),
    val busySlots: MutableSet<String> = mutableSetOf(),
    val leaveSlots: MutableSet<String> = mutableSetOf(),
)

// ---------------- 线索与试听 ----------------

class Lead(
    val id: String,
    val studentName: String,
    val grade: String,
    val subject: Subject,
    val level: Level,
    val textbook: Textbook,
    val availableSlots: Set<String>,
    val channel: String,
    val consultantId: String,
    var campusId: String,
    val activityParticipated: Boolean,
    var status: LeadStatus = LeadStatus.NEW,
    var rescheduleCount: Int = 0,
    var studentId: String? = null,
    /** 试听到课时发放的体验课赠品 */
    var experienceGift: Gift? = null,
    val createdAt: String,
)

class ClassGroup(
    val id: String,
    val campusId: String,
    val subject: Subject,
    val level: Level,
    val textbook: Textbook,
    val type: ClassType,
    val teacherId: String,
    val classroomId: String,
    val capacity: Int,
    val slots: MutableSet<String>,
    val studentIds: MutableSet<String> = mutableSetOf(),
    var suspended: Boolean = false,
    /** 老师请假等原因取消的课次 */
    val cancelledSlots: MutableSet<String> = mutableSetOf(),
)

class TrialClass(
    val id: String,
    val leadId: String,
    val campusId: String,
    val teacherId: String,
    val classroomId: String,
    val classGroupId: String?,
    val slot: String,
    var status: TrialStatus = TrialStatus.BOOKED,
    var cancelReason: String? = null,
    val bookedAt: String,
)

/** 试听后老师提交的反馈 */
class TrialFeedback(
    val trialId: String,
    val teacherId: String,
    val performance: String,
    val assessmentScore: Int,
    val suitableClassType: ClassType,
    val riskWarnings: List<String>,
    val submittedAt: String,
)

// ---------------- 学员 / 合同 / 课消 ----------------

class Student(
    val id: String,
    val leadId: String,
    val name: String,
    var campusId: String,
    val createdAt: String,
)

class CoursePackage(
    val id: String,
    val name: String,
    val subject: Subject,
    val level: Level,
    val textbook: Textbook,
    val lessons: Int,
    val priceCents: Long,
    val textbookFeeCents: Long,
)

class Gift(val name: String, val valueCents: Long, var returned: Boolean = false)

class Installment(val no: Int, val dueDate: String, val amountCents: Long, var paidAt: String? = null)

class Invoice(
    val no: String,
    val amountCents: Long,
    val issuedAt: String,
    var status: InvoiceStatus = InvoiceStatus.ISSUED,
    var redFlushedAt: String? = null,
)

/** 报名承诺记录(销售对家长的承诺,用于与试听反馈核对) */
class Promise(val type: PromiseType, val content: String, val refValue: String? = null, val promisedBy: String)

class PromiseCheck(val status: PromiseCheckStatus, val mismatches: List<String>)

/** 课消规则,绑定在合同上 */
class ConsumptionRules(
    val absentConsumes: Boolean = true,     // 缺席是否照常消课
    val leaveAdvanceHours: Long = 24,       // 提前多少小时请假不消课
    val giftFirst: Boolean = false,         // 是否优先消耗赠课
    val makeupAllowed: Boolean = true,      // 请假后是否允许补课
)

class Contract(
    val id: String,
    val studentId: String,
    val leadId: String,
    val consultantId: String,
    val campusId: String,
    val packageId: String,
    var classGroupId: String,
    val totalLessons: Int,
    val giftLessons: Int,
    val priceCents: Long,
    val discountCents: Long,
    val textbookFeeCents: Long,
    val textbookDelivered: Boolean,
    val channelFeeCents: Long,
    val gift: Gift?,
    val installments: MutableList<Installment>,
    var invoice: Invoice?,
    val promises: List<Promise>,
    var promiseCheck: PromiseCheck,
    val rules: ConsumptionRules,
    var status: ContractStatus = ContractStatus.ACTIVE,
    val createdAt: String,
) {
    /** 实收金额 = 已缴分期之和 */
    val paidCents: Long
        get() = installments.filter { it.paidAt != null }.sumOf { it.amountCents }
}

class LessonEvent(
    val id: String,
    val type: LessonEventType,
    val kind: LessonKind?,
    val slot: String,
    val at: String,
    val note: String? = null,
)

/** 学员课消账户:与合同一对一 */
class ConsumptionAccount(
    val studentId: String,
    val contractId: String,
    val paidTotal: Int,
    var giftTotal: Int,
    var consumedPaid: Int = 0,
    var consumedGift: Int = 0,
    var leaveCount: Int = 0,
    var absentCount: Int = 0,
    val events: MutableList<LessonEvent> = mutableListOf(),
) {
    val remainingPaid: Int
        get() = paidTotal - consumedPaid

    val remainingGift: Int
        get() = giftTotal - consumedGift
}

// ---------------- 财务 / 业绩 / 争议 ----------------

class Commission(
    val id: String,
    val consultantId: String,
    val contractId: String,
    val amountCents: Long,
    var status: CommissionStatus = CommissionStatus.ACCRUED,
    var clawedBackCents: Long = 0,
)

class ChannelFee(val id: String, val channel: String, val contractId: String, val amountCents: Long)

class Complaint(
    val id: String,
    val studentId: String,
    val content: String,
    var status: ComplaintStatus = ComplaintStatus.OPEN,
    val createdAt: String,
    var resolvedAt: String? = null,
)

class GiftDispute(
    val id: String,
    val contractId: String,
    val claimedGiftLessons: Int,
    val reason: String,
    var status: ComplaintStatus = ComplaintStatus.OPEN,
    var adjustedLessons: Int = 0,
)

// ---------------- 退费 ----------------

data class RefundInput(
    val priceCents: Long,
    /** 应付总额 = 原价 - 优惠 */
    val payableCents: Long,
    /** 实缴金额(分期可能未缴足) */
    val paidCents: Long,
    val totalLessons: Int,
    val consumedPaid: Int,
    val consumedGift: Int,
    val textbookFeeCents: Long,
    val textbookDelivered: Boolean,
    val giftValueCents: Long,
    val giftReturned: Boolean,
    val upheldComplaint: Boolean,
    val giftConsumedDeducts: Boolean = false,
)

data class RefundQuote(
    val unitPriceCents: Long,          // 原价课时单价
    val paidUnitCents: Long,           // 折后课时单价
    val consumedPaidLessons: Int,
    val consumedGiftLessons: Int,
    val consumedValueCents: Long,      // 已上课时结算金额
    val discountRecoveredCents: Long,  // 优惠回收金额
    val textbookDeductCents: Long,     // 教材费扣减
    val giftDeductCents: Long,         // 体验课赠品未退回扣减
    val giftConsumedDeductCents: Long, // 已消耗赠课抵扣(按机构政策)
    val complaintWaiverCents: Long,    // 投诉成立减免金额
    val refundCents: Long,             // 应退金额(>=0)
    val studentOwesCents: Long,        // 学员倒欠金额(分期未缴足且消耗超额时)
)

/**
 * 退费计算(纯函数,便于单测):
 *  - 提前退费时,已消耗正课按"原价"结算,即回收报名优惠;
 *  - 教材已发放则扣教材费;
 *  - 赠课无现金价值:剩余赠课作废;已消耗赠课默认不扣减(可由政策开启);
 *  - 体验课赠品未退回按赠品价值扣减;
 *  - 存在成立投诉时,视为机构责任:免除优惠回收与教材费扣减。
 */
fun computeRefund(input: RefundInput): RefundQuote {
    require(input.totalLessons > 0) { "totalLessons must be positive" }
    val unit = input.priceCents / input.totalLessons
    val paidUnit = input.payableCents / input.totalLessons
    val consumedAtOriginal = input.consumedPaid * unit
    val consumedAtPaid = input.consumedPaid * paidUnit
    var discountRecovered = consumedAtOriginal - consumedAtPaid
    var textbookDeduct = if (input.textbookDelivered) input.textbookFeeCents else 0L
    var waiver = 0L
    val consumedValue: Long
    if (input.upheldComplaint) {
        waiver = discountRecovered + textbookDeduct
        discountRecovered = 0L
        textbookDeduct = 0L
        consumedValue = consumedAtPaid
    } else {
        consumedValue = consumedAtOriginal
    }
    val giftDeduct = if (!input.giftReturned) input.giftValueCents else 0L
    val giftConsumedDeduct = if (input.giftConsumedDeducts) input.consumedGift * unit else 0L
    val raw = input.paidCents - consumedValue - textbookDeduct - giftDeduct - giftConsumedDeduct
    return RefundQuote(
        unitPriceCents = unit,
        paidUnitCents = paidUnit,
        consumedPaidLessons = input.consumedPaid,
        consumedGiftLessons = input.consumedGift,
        consumedValueCents = consumedValue,
        discountRecoveredCents = discountRecovered,
        textbookDeductCents = textbookDeduct,
        giftDeductCents = giftDeduct,
        giftConsumedDeductCents = giftConsumedDeduct,
        complaintWaiverCents = waiver,
        refundCents = maxOf(0L, raw),
        studentOwesCents = maxOf(0L, -raw),
    )
}

class RefundRecord(
    val id: String,
    val contractId: String,
    val studentId: String,
    val reason: String,
    val quote: RefundQuote,
    val createdAt: String,
)

// ---------------- 履约轨迹 ----------------

class TrajectoryEvent(
    val id: String,
    /** 归属键:leadId 或 studentId,学员视图会合并两者 */
    val ownerKey: String,
    val at: String,
    val type: String,
    val actor: String,
    val summary: String,
    val details: Map<String, Any?> = emptyMap(),
)
