package com.trialflow.service

import com.trialflow.api.badRequest
import com.trialflow.api.conflict
import com.trialflow.api.notFound
import com.trialflow.config.BusinessProps
import com.trialflow.domain.ChannelFee
import com.trialflow.domain.Commission
import com.trialflow.domain.ConsumptionAccount
import com.trialflow.domain.ConsumptionRules
import com.trialflow.domain.Contract
import com.trialflow.domain.ContractStatus
import com.trialflow.domain.Installment
import com.trialflow.domain.Invoice
import com.trialflow.domain.InvoiceStatus
import com.trialflow.domain.LeadStatus
import com.trialflow.domain.Promise
import com.trialflow.domain.PromiseCheck
import com.trialflow.domain.PromiseCheckStatus
import com.trialflow.domain.PromiseType
import com.trialflow.domain.Student
import com.trialflow.infra.IdempotencyService
import com.trialflow.infra.LockService
import com.trialflow.store.DataStore
import org.springframework.stereotype.Service
import java.time.Instant
import kotlin.math.roundToLong

@Service
class ContractService(
    private val store: DataStore,
    private val locks: LockService,
    private val idempotency: IdempotencyService,
    private val trajectory: TrajectoryService,
    private val props: BusinessProps,
) {

    data class CreateResult(val contract: Contract, val replayed: Boolean)

    /**
     * 报名转化:课包/优惠/赠课/分期/发票/班级/课消规则绑定到同一学员账户。
     * 签约前自动核对报名承诺与试听反馈,不一致且未确认时拒绝签约。
     */
    fun create(
        leadId: String, packageId: String, classGroupId: String,
        giftLessons: Int, discountCents: Long,
        installments: List<Installment>, paidInstallments: Int,
        textbookDelivered: Boolean,
        promises: List<Promise>,
        rules: ConsumptionRules,
        acknowledgeMismatch: Boolean,
        idempotencyKey: String?,
        actor: String,
    ): CreateResult {
        if (idempotencyKey != null) {
            idempotency.lookup(idempotencyKey)?.let { existingId ->
                val existing = store.contracts[existingId]
                if (existing != null) return CreateResult(existing, true)
                idempotency.evict(idempotencyKey) // 键陈旧(实体不存在),清理后继续
            }
        }
        val lead = store.leads[leadId] ?: notFound("线索", leadId)
        if (lead.status == LeadStatus.CONVERTED) conflict("LEAD_CONVERTED", "该线索已转化,不能重复报名")
        if (lead.status != LeadStatus.TRIAL_DONE) {
            conflict("NO_TRIAL", "未完成试听到课的线索不能报名(当前状态: ${lead.status})")
        }
        val pkg = store.packages[packageId] ?: notFound("课包", packageId)
        if (pkg.subject != lead.subject) {
            badRequest("SUBJECT_MISMATCH", "课包科目[${pkg.subject}]与线索目标科目[${lead.subject}]不一致")
        }
        if (giftLessons < 0 || discountCents < 0) badRequest("VALIDATION", "赠课数与优惠金额不能为负")
        if (discountCents > pkg.priceCents) badRequest("VALIDATION", "优惠金额不能超过课包价格")
        val totalPayable = pkg.priceCents - discountCents
        if (installments.isEmpty()) badRequest("VALIDATION", "至少需要一个分期(一次性缴费即单期)")
        if (installments.sumOf { it.amountCents } != totalPayable) {
            badRequest(
                "INSTALLMENT_MISMATCH", "分期总额必须等于应付金额(课包价-优惠)",
                mapOf("totalPayableCents" to totalPayable, "installmentSumCents" to installments.sumOf { it.amountCents }),
            )
        }
        if (paidInstallments !in 0..installments.size) badRequest("VALIDATION", "已缴分期数非法")

        // ---- 承诺一致性核对:试听反馈 vs 合同承诺 ----
        val check = checkPromises(leadId, promises)
        if (check.status != PromiseCheckStatus.CONSISTENT && !acknowledgeMismatch) {
            conflict(
                "PROMISE_MISMATCH",
                "报名承诺与试听反馈不一致,请确认后再签约(确认请置 acknowledgeMismatch=true)",
                mapOf("check" to check.status.name, "mismatches" to check.mismatches),
            )
        }

        return locks.withLocks(listOf("lead:$leadId", "class:$classGroupId")) {
            if (lead.status == LeadStatus.CONVERTED) conflict("LEAD_CONVERTED", "该线索已转化,不能重复报名")
            val group = store.classGroups[classGroupId] ?: notFound("班级", classGroupId)
            if (group.suspended) conflict("CLASS_SUSPENDED", "班级已停课")
            if (group.studentIds.size >= group.capacity) conflict("CLASS_FULL", "班级容量已满")
            if (group.subject != pkg.subject || group.level != pkg.level || group.textbook != pkg.textbook) {
                conflict("CLASS_MISMATCH", "班级与课包不匹配(科目/水平/教材版本需一致)")
            }

            val now = Instant.now().toString()
            val student = Student(store.nextId("STU"), lead.id, lead.studentName, lead.campusId, now)
            store.students[student.id] = student

            val paid = installments.take(paidInstallments)
            paid.forEach { it.paidAt = now }
            val channelFeeCents = (totalPayable * props.channelRate(lead.channel)).roundToLong()
            val contract = Contract(
                id = store.nextId("CT"), studentId = student.id, leadId = lead.id,
                consultantId = lead.consultantId, campusId = lead.campusId,
                packageId = pkg.id, classGroupId = group.id,
                totalLessons = pkg.lessons, giftLessons = giftLessons,
                priceCents = pkg.priceCents, discountCents = discountCents,
                textbookFeeCents = pkg.textbookFeeCents, textbookDelivered = textbookDelivered,
                channelFeeCents = channelFeeCents,
                gift = lead.experienceGift,
                installments = installments.toMutableList(),
                invoice = null,
                promises = promises, promiseCheck = check,
                rules = rules, createdAt = now,
            )
            store.contracts[contract.id] = contract
            store.accounts[student.id] = ConsumptionAccount(
                studentId = student.id, contractId = contract.id,
                paidTotal = pkg.lessons, giftTotal = giftLessons,
            )
            val commission = Commission(
                store.nextId("COM"), lead.consultantId, contract.id,
                (totalPayable * props.commissionRate).roundToLong(),
            )
            store.commissions[commission.id] = commission
            if (channelFeeCents > 0) {
                val fee = ChannelFee(store.nextId("CHF"), lead.channel, contract.id, channelFeeCents)
                store.channelFees[fee.id] = fee
            }
            group.studentIds.add(student.id)
            lead.status = LeadStatus.CONVERTED
            lead.studentId = student.id

            trajectory.record(
                lead.id, "CONVERTED", actor,
                "线索转化为正式学员:${student.id}",
                mapOf("studentId" to student.id, "contractId" to contract.id),
            )
            trajectory.record(
                student.id, "CONTRACT_SIGNED", actor,
                "合同签订:课包[${pkg.name}],正课${pkg.lessons}节+赠课${giftLessons}节," +
                    "原价${pkg.priceCents / 100.0}元,优惠${discountCents / 100.0}元," +
                    "应付$totalPayable 分,分期${installments.size}期(已缴$paidInstallments 期)," +
                    "渠道费${channelFeeCents / 100.0}元,顾问提成${commission.amountCents / 100.0}元",
                mapOf(
                    "contractId" to contract.id, "packageId" to pkg.id,
                    "totalLessons" to pkg.lessons, "giftLessons" to giftLessons,
                    "priceCents" to pkg.priceCents, "discountCents" to discountCents,
                    "channelFeeCents" to channelFeeCents, "commissionCents" to commission.amountCents,
                    "classGroupId" to group.id, "promiseCheck" to check.status.name,
                ),
            )
            if (check.status != PromiseCheckStatus.CONSISTENT) {
                trajectory.record(
                    student.id, "PROMISE_MISMATCH_ACKED", actor,
                    "承诺与试听反馈不一致,已人工确认: ${check.mismatches.joinToString("; ")}",
                    mapOf("mismatches" to check.mismatches),
                )
            }
            promises.forEach { p ->
                trajectory.record(
                    student.id, "PROMISE_RECORDED", actor,
                    "报名承诺[${p.type}]:${p.content}(承诺人:${p.promisedBy})",
                    mapOf("type" to p.type.name, "content" to p.content, "promisedBy" to p.promisedBy),
                )
            }
            idempotencyKey?.let { idempotency.store(it, contract.id) }
            CreateResult(contract, false)
        }
    }

    /** 承诺 vs 试听反馈 一致性核对 */
    fun checkPromises(leadId: String, promises: List<Promise>): PromiseCheck {
        val trials = store.trials.values.filter { it.leadId == leadId }
        val feedback = trials.mapNotNull { store.feedbacks[it.id] }.lastOrNull()
            ?: return PromiseCheck(PromiseCheckStatus.NO_FEEDBACK, listOf("未找到试听反馈,承诺无法核对"))
        val mismatches = mutableListOf<String>()
        for (p in promises) {
            when (p.type) {
                PromiseType.CLASS_TYPE -> {
                    val promised = p.refValue ?: p.content
                    if (!promised.equals(feedback.suitableClassType.name, ignoreCase = true)) {
                        mismatches.add(
                            "承诺班型[$promised]与老师建议班型[${feedback.suitableClassType}]不一致",
                        )
                    }
                }
                PromiseType.TEACHER -> {
                    val teacherId = p.refValue
                    if (teacherId != null) {
                        val teacher = store.teachers[teacherId]
                            ?: run {
                                mismatches.add("承诺老师不存在: $teacherId")
                                null
                            }
                        if (teacher != null) {
                            val lead = store.leads[leadId]
                            if (lead != null && (lead.subject !in teacher.subjects || lead.campusId !in teacher.campusIds)) {
                                mismatches.add("承诺老师[${teacher.name}]不满足该学员科目/校区授课条件")
                            }
                        }
                    }
                }
                else -> Unit
            }
        }
        if (feedback.riskWarnings.isNotEmpty() &&
            promises.none { it.type == PromiseType.OTHER && it.content.contains("风险") }
        ) {
            mismatches.add("试听反馈存在风险提醒(${feedback.riskWarnings.joinToString("/")}),合同中未见风险确认承诺")
        }
        return PromiseCheck(
            if (mismatches.isEmpty()) PromiseCheckStatus.CONSISTENT else PromiseCheckStatus.MISMATCH,
            mismatches,
        )
    }

    fun get(id: String): Contract = store.contracts[id] ?: notFound("合同", id)

    fun payInstallment(contractId: String, no: Int, actor: String): Contract {
        val contract = get(contractId)
        if (contract.status != ContractStatus.ACTIVE) conflict("CONTRACT_STATUS", "合同非生效状态,不能缴费: ${contract.status}")
        val inst = contract.installments.find { it.no == no } ?: notFound("分期", "第${no}期")
        if (inst.paidAt != null) conflict("ALREADY_PAID", "该分期已缴费")
        inst.paidAt = Instant.now().toString()
        trajectory.record(
            contract.studentId, "INSTALLMENT_PAID", actor,
            "缴纳第${no}期学费:${inst.amountCents / 100.0}元",
            mapOf("contractId" to contractId, "installmentNo" to no, "amountCents" to inst.amountCents),
        )
        return contract
    }

    fun issueInvoice(contractId: String, actor: String): Contract {
        val contract = get(contractId)
        if (contract.invoice != null && contract.invoice!!.status == InvoiceStatus.ISSUED) {
            conflict("INVOICE_EXISTS", "发票已开具")
        }
        val paid = contract.paidCents
        if (paid <= 0) conflict("NO_PAYMENT", "尚无已缴费用,不能开票")
        contract.invoice = Invoice("INV-${contract.id}", paid, Instant.now().toString())
        trajectory.record(
            contract.studentId, "INVOICE_ISSUED", actor,
            "开具发票:${paid / 100.0}元",
            mapOf("contractId" to contractId, "invoiceNo" to contract.invoice!!.no, "amountCents" to paid),
        )
        return contract
    }

    /** 停课(学员合同层面) */
    fun suspend(contractId: String, reason: String, actor: String): Contract {
        val contract = get(contractId)
        if (contract.status != ContractStatus.ACTIVE) conflict("CONTRACT_STATUS", "只有生效中合同可以停课")
        contract.status = ContractStatus.SUSPENDED
        trajectory.record(
            contract.studentId, "CONTRACT_SUSPENDED", actor, "合同停课:$reason",
            mapOf("contractId" to contractId, "reason" to reason),
        )
        return contract
    }

    fun resume(contractId: String, actor: String): Contract {
        val contract = get(contractId)
        if (contract.status != ContractStatus.SUSPENDED) conflict("CONTRACT_STATUS", "合同未处于停课状态")
        contract.status = ContractStatus.ACTIVE
        trajectory.record(contract.studentId, "CONTRACT_RESUMED", actor, "合同复课", mapOf("contractId" to contractId))
        return contract
    }
}
