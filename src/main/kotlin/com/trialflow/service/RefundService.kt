package com.trialflow.service

import com.trialflow.api.conflict
import com.trialflow.api.notFound
import com.trialflow.config.BusinessProps
import com.trialflow.domain.CommissionStatus
import com.trialflow.domain.Complaint
import com.trialflow.domain.ComplaintStatus
import com.trialflow.domain.ContractStatus
import com.trialflow.domain.GiftDispute
import com.trialflow.domain.InvoiceStatus
import com.trialflow.domain.LessonEvent
import com.trialflow.domain.LessonEventType
import com.trialflow.domain.RefundInput
import com.trialflow.domain.RefundQuote
import com.trialflow.domain.RefundRecord
import com.trialflow.domain.computeRefund
import com.trialflow.infra.IdempotencyService
import com.trialflow.infra.LockService
import com.trialflow.store.DataStore
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class RefundService(
    private val store: DataStore,
    private val locks: LockService,
    private val idempotency: IdempotencyService,
    private val trajectory: TrajectoryService,
    private val props: BusinessProps,
) {

    /** 退费试算:已上课时/赠课抵扣/教材费/优惠回收/赠品/投诉减免 */
    fun quote(contractId: String, giftReturned: Boolean): RefundQuote {
        val contract = store.contracts[contractId] ?: notFound("合同", contractId)
        val account = store.accounts[contract.studentId] ?: notFound("课消账户", contract.studentId)
        val upheld = store.complaints.values.any { it.studentId == contract.studentId && it.status == ComplaintStatus.UPHELD }
        return computeRefund(
            RefundInput(
                priceCents = contract.priceCents,
                payableCents = contract.priceCents - contract.discountCents,
                paidCents = contract.paidCents,
                totalLessons = contract.totalLessons,
                consumedPaid = account.consumedPaid,
                consumedGift = account.consumedGift,
                textbookFeeCents = contract.textbookFeeCents,
                textbookDelivered = contract.textbookDelivered,
                giftValueCents = contract.gift?.valueCents ?: 0L,
                giftReturned = giftReturned || contract.gift == null,
                upheldComplaint = upheld,
                giftConsumedDeducts = props.giftConsumedDeducts,
            ),
        )
    }

    data class RefundResult(val record: RefundRecord, val replayed: Boolean)

    /**
     * 执行退费:合同关闭、剩余赠课作废、发票红冲、顾问提成冻结并追回、学员退出班级。
     * 幂等:同一 Idempotency-Key 重复提交返回首次结果。
     */
    fun execute(
        contractId: String, reason: String, giftReturned: Boolean,
        idempotencyKey: String?, actor: String,
    ): RefundResult {
        if (idempotencyKey != null) {
            idempotency.lookup(idempotencyKey)?.let { existingId ->
                store.refunds[existingId]?.let { return RefundResult(it, true) }
                idempotency.evict(idempotencyKey) // 键陈旧(实体不存在),清理后继续
            }
        }
        return locks.withLock("contract:$contractId") {
            val contract = store.contracts[contractId] ?: notFound("合同", contractId)
            if (contract.status !in setOf(ContractStatus.ACTIVE, ContractStatus.SUSPENDED)) {
                conflict("CONTRACT_STATUS", "合同已关闭,不能重复退费: ${contract.status}")
            }
            val quote = quote(contractId, giftReturned)
            val studentId = contract.studentId
            val account = store.accounts.getValue(studentId)
            val now = Instant.now().toString()

            // 1) 提成冻结 -> 按退费比例追回
            val commission = store.commissions.values.find { it.contractId == contractId }
            if (commission != null && commission.status == CommissionStatus.ACCRUED) {
                commission.status = CommissionStatus.FROZEN
                trajectory.record(
                    studentId, "COMMISSION_FROZEN", actor,
                    "退费触发顾问提成冻结:${commission.amountCents / 100.0}元",
                    mapOf("commissionId" to commission.id, "amountCents" to commission.amountCents),
                )
            }
            if (commission != null && contract.paidCents > 0) {
                val clawback = commission.amountCents * quote.refundCents / contract.paidCents
                commission.clawedBackCents = clawback
                commission.status = CommissionStatus.CLAWED_BACK
                trajectory.record(
                    studentId, "COMMISSION_CLAWED_BACK", actor,
                    "顾问提成追回:${clawback / 100.0}元(按退费比例)",
                    mapOf("commissionId" to commission.id, "clawedBackCents" to clawback),
                )
            }

            // 2) 剩余赠课作废
            val voidedGift = account.remainingGift
            if (voidedGift > 0) {
                account.consumedGift = account.giftTotal
                account.events.add(LessonEvent(store.nextId("LE"), LessonEventType.GIFT_VOIDED, null, "-", now, "退费作废剩余赠课${voidedGift}节"))
            }

            // 3) 发票红冲
            contract.invoice?.let { inv ->
                if (inv.status == InvoiceStatus.ISSUED) {
                    inv.status = InvoiceStatus.RED_FLUSHED
                    inv.redFlushedAt = now
                    trajectory.record(
                        studentId, "INVOICE_RED_FLUSHED", actor,
                        "发票红冲:${inv.no}",
                        mapOf("invoiceNo" to inv.no, "amountCents" to inv.amountCents),
                    )
                }
            }

            // 4) 退出班级 + 合同关闭
            store.classGroups[contract.classGroupId]?.studentIds?.remove(studentId)
            contract.status = ContractStatus.CLOSED_REFUND

            val record = RefundRecord(store.nextId("REF"), contractId, studentId, reason, quote, now)
            store.refunds[record.id] = record
            trajectory.record(
                studentId, "REFUND_EXECUTED", actor,
                "退费完成:应退${quote.refundCents / 100.0}元" +
                    "(已上正课${quote.consumedPaidLessons}节结算${quote.consumedValueCents / 100.0}元," +
                    "优惠回收${quote.discountRecoveredCents / 100.0}元,教材费扣${quote.textbookDeductCents / 100.0}元," +
                    "赠品扣${quote.giftDeductCents / 100.0}元,投诉减免${quote.complaintWaiverCents / 100.0}元," +
                    "剩余赠课${voidedGift}节作废),原因:$reason",
                mapOf(
                    "refundId" to record.id, "reason" to reason,
                    "refundCents" to quote.refundCents, "studentOwesCents" to quote.studentOwesCents,
                    "consumedPaidLessons" to quote.consumedPaidLessons,
                    "consumedGiftLessons" to quote.consumedGiftLessons,
                    "discountRecoveredCents" to quote.discountRecoveredCents,
                    "textbookDeductCents" to quote.textbookDeductCents,
                    "giftDeductCents" to quote.giftDeductCents,
                    "complaintWaiverCents" to quote.complaintWaiverCents,
                    "voidedGiftLessons" to voidedGift,
                ),
            )
            idempotencyKey?.let { idempotency.store(it, record.id) }
            RefundResult(record, false)
        }
    }

    // ---------------- 投诉 ----------------

    fun fileComplaint(studentId: String, content: String, actor: String): Complaint {
        store.students[studentId] ?: notFound("学员", studentId)
        val complaint = Complaint(store.nextId("CPL"), studentId, content, createdAt = Instant.now().toString())
        store.complaints[complaint.id] = complaint
        trajectory.record(
            studentId, "COMPLAINT_FILED", actor, "家长投诉:$content",
            mapOf("complaintId" to complaint.id, "content" to content),
        )
        return complaint
    }

    fun resolveComplaint(complaintId: String, upheld: Boolean, note: String?, actor: String): Complaint {
        val complaint = store.complaints[complaintId] ?: notFound("投诉", complaintId)
        if (complaint.status != ComplaintStatus.OPEN) conflict("COMPLAINT_STATUS", "投诉已处理")
        complaint.status = if (upheld) ComplaintStatus.UPHELD else ComplaintStatus.REJECTED
        complaint.resolvedAt = Instant.now().toString()
        trajectory.record(
            complaint.studentId, "COMPLAINT_RESOLVED", actor,
            "投诉处理:${if (upheld) "成立(退费将免除优惠回收与教材费扣减)" else "不成立"}${note?.let { ",$it" } ?: ""}",
            mapOf("complaintId" to complaintId, "upheld" to upheld, "note" to note),
        )
        return complaint
    }

    // ---------------- 赠课争议 ----------------

    fun fileGiftDispute(contractId: String, claimedGiftLessons: Int, reason: String, actor: String): GiftDispute {
        val contract = store.contracts[contractId] ?: notFound("合同", contractId)
        val dispute = GiftDispute(store.nextId("GD"), contractId, claimedGiftLessons, reason)
        store.giftDisputes[dispute.id] = dispute
        trajectory.record(
            contract.studentId, "GIFT_DISPUTE_FILED", actor,
            "赠课争议:家长主张${claimedGiftLessons}节赠课(合同${contract.giftLessons}节),$reason",
            mapOf("disputeId" to dispute.id, "claimed" to claimedGiftLessons, "contractGift" to contract.giftLessons),
        )
        return dispute
    }

    /** 争议处理:成立则把差额赠课补入课消账户 */
    fun resolveGiftDispute(disputeId: String, approve: Boolean, note: String?, actor: String): GiftDispute {
        val dispute = store.giftDisputes[disputeId] ?: notFound("赠课争议", disputeId)
        if (dispute.status != ComplaintStatus.OPEN) conflict("DISPUTE_STATUS", "争议已处理")
        dispute.status = if (approve) ComplaintStatus.UPHELD else ComplaintStatus.REJECTED
        val contract = store.contracts.getValue(dispute.contractId)
        if (approve) {
            val account = store.accounts[contract.studentId] ?: notFound("课消账户", contract.studentId)
            val delta = (dispute.claimedGiftLessons - account.giftTotal).coerceAtLeast(0)
            if (delta > 0) {
                account.giftTotal += delta
                dispute.adjustedLessons = delta
            }
            trajectory.record(
                contract.studentId, "GIFT_DISPUTE_RESOLVED", actor,
                "赠课争议成立,补入赠课${dispute.adjustedLessons}节${note?.let { ",$it" } ?: ""}",
                mapOf("disputeId" to disputeId, "adjustedLessons" to dispute.adjustedLessons),
            )
        } else {
            trajectory.record(
                contract.studentId, "GIFT_DISPUTE_RESOLVED", actor,
                "赠课争议不成立${note?.let { ",$it" } ?: ""}",
                mapOf("disputeId" to disputeId),
            )
        }
        return dispute
    }
}
