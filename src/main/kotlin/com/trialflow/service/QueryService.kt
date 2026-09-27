package com.trialflow.service

import com.trialflow.api.notFound
import com.trialflow.domain.CommissionStatus
import com.trialflow.domain.ComplaintStatus
import com.trialflow.domain.ContractStatus
import com.trialflow.domain.LeadStatus
import com.trialflow.store.DataStore
import org.springframework.stereotype.Service

/** 校长视角:完整履约轨迹 + 经营漏斗 + 顾问业绩 */
@Service
class QueryService(
    private val store: DataStore,
    private val trajectory: TrajectoryService,
) {

    /** 学员完整履约轨迹:线索 -> 试听 -> 反馈 -> 合同承诺 -> 上课课消 -> 退费/结束 */
    fun studentTrajectory(studentId: String): Map<String, Any?> {
        val student = store.students[studentId] ?: notFound("学员", studentId)
        val lead = store.leads[student.leadId]
        val contracts = store.contracts.values.filter { it.studentId == studentId }
        val account = store.accounts[studentId]
        val events = trajectory.trajectoryOfStudent(studentId)
        val feedbacks = store.trials.values.filter { it.leadId == student.leadId }
            .mapNotNull { store.feedbacks[it.id] }
        return mapOf(
            "student" to student,
            "lead" to lead,
            "trialFeedbacks" to feedbacks,
            "contracts" to contracts.map { c ->
                mapOf(
                    "contract" to c,
                    "promiseCheck" to c.promiseCheck,
                    "paidCents" to c.paidCents,
                    "refund" to store.refunds.values.find { it.contractId == c.id },
                )
            },
            "consumption" to account?.let {
                mapOf(
                    "paidTotal" to it.paidTotal, "giftTotal" to it.giftTotal,
                    "consumedPaid" to it.consumedPaid, "consumedGift" to it.consumedGift,
                    "remainingPaid" to it.remainingPaid, "remainingGift" to it.remainingGift,
                    "leaveCount" to it.leaveCount, "absentCount" to it.absentCount,
                )
            },
            "complaints" to store.complaints.values.filter { it.studentId == studentId },
            "eventCount" to events.size,
            "trajectory" to events,
        )
    }

    /** 经营漏斗:线索 -> 试听 -> 到课 -> 报名 -> 退费,按校区汇总 */
    fun principalOverview(campusId: String?): Map<String, Any?> {
        val leads = store.leads.values.filter { campusId == null || it.campusId == campusId }
        val contracts = store.contracts.values.filter { campusId == null || it.campusId == campusId }
        val contractIds = contracts.map { it.id }.toSet()
        val commissions = store.commissions.values.filter { it.contractId in contractIds }
        val refunds = store.refunds.values.filter { it.contractId in contractIds }
        val studentIds = contracts.map { it.studentId }.toSet()
        return mapOf(
            "campusId" to (campusId ?: "ALL"),
            "funnel" to mapOf(
                "leads" to leads.size,
                "trialBooked" to leads.count { it.status in setOf(LeadStatus.TRIAL_BOOKED, LeadStatus.TRIAL_DONE, LeadStatus.TRIAL_NO_SHOW, LeadStatus.CONVERTED) },
                "trialDone" to leads.count { it.status in setOf(LeadStatus.TRIAL_DONE, LeadStatus.CONVERTED) },
                "converted" to leads.count { it.status == LeadStatus.CONVERTED },
                "multiRescheduleLeads" to leads.count { it.rescheduleCount >= 2 },
            ),
            "finance" to mapOf(
                "signedCents" to contracts.sumOf { it.priceCents - it.discountCents },
                "receivedCents" to contracts.sumOf { it.paidCents },
                "refundedCents" to refunds.sumOf { it.quote.refundCents },
                "channelFeeCents" to store.channelFees.values.filter { it.contractId in contractIds }.sumOf { it.amountCents },
                "commissionAccruedCents" to commissions.filter { it.status == CommissionStatus.ACCRUED }.sumOf { it.amountCents },
                "commissionClawedBackCents" to commissions.sumOf { it.clawedBackCents },
            ),
            "contracts" to mapOf(
                "active" to contracts.count { it.status == ContractStatus.ACTIVE },
                "suspended" to contracts.count { it.status == ContractStatus.SUSPENDED },
                "closedRefund" to contracts.count { it.status == ContractStatus.CLOSED_REFUND },
                "promiseMismatches" to contracts.count {
                    it.promiseCheck.status == com.trialflow.domain.PromiseCheckStatus.MISMATCH
                },
            ),
            "complaints" to mapOf(
                "open" to store.complaints.values.count { it.studentId in studentIds && it.status == ComplaintStatus.OPEN },
                "upheld" to store.complaints.values.count { it.studentId in studentIds && it.status == ComplaintStatus.UPHELD },
            ),
        )
    }

    /** 顾问业绩:合同额、实收、提成(含冻结/追回) */
    fun consultantPerformance(consultantId: String): Map<String, Any?> {
        store.users[consultantId] ?: notFound("顾问", consultantId)
        val contracts = store.contracts.values.filter { it.consultantId == consultantId }
        val commissions = store.commissions.values.filter { it.consultantId == consultantId }
        return mapOf(
            "consultantId" to consultantId,
            "leads" to store.leads.values.count { it.consultantId == consultantId },
            "convertedLeads" to store.leads.values.count { it.consultantId == consultantId && it.status == LeadStatus.CONVERTED },
            "contracts" to contracts.map {
                mapOf(
                    "contractId" to it.id, "studentId" to it.studentId, "status" to it.status,
                    "signedCents" to it.priceCents - it.discountCents, "paidCents" to it.paidCents,
                )
            },
            "commission" to mapOf(
                "accruedCents" to commissions.filter { it.status == CommissionStatus.ACCRUED }.sumOf { it.amountCents },
                "frozenCents" to commissions.filter { it.status == CommissionStatus.FROZEN }.sumOf { it.amountCents },
                "clawedBackCents" to commissions.sumOf { it.clawedBackCents },
            ),
        )
    }
}
