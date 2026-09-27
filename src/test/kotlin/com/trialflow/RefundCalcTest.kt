package com.trialflow

import com.trialflow.domain.RefundInput
import com.trialflow.domain.computeRefund
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * 退费计算纯函数测试。
 * 基准合同:48 课时,原价 9600 元(单价 200 元),优惠 960 元, payable 8640 元(折后单价 180 元)。
 */
class RefundCalcTest {

    private fun base(
        consumedPaid: Int = 0,
        consumedGift: Int = 0,
        paidCents: Long = 864_000,
        textbookDelivered: Boolean = true,
        giftReturned: Boolean = true,
        upheldComplaint: Boolean = false,
        giftConsumedDeducts: Boolean = false,
    ) = RefundInput(
        priceCents = 960_000,
        payableCents = 864_000,
        paidCents = paidCents,
        totalLessons = 48,
        consumedPaid = consumedPaid,
        consumedGift = consumedGift,
        textbookFeeCents = 30_000,
        textbookDelivered = textbookDelivered,
        giftValueCents = 5_000,
        giftReturned = giftReturned,
        upheldComplaint = upheldComplaint,
        giftConsumedDeducts = giftConsumedDeducts,
    )

    @Test
    fun `未上课时退全部实缴`() {
        val q = computeRefund(base(consumedPaid = 0, textbookDelivered = false))
        assertEquals(864_000, q.refundCents)
        assertEquals(0, q.discountRecoveredCents)
    }

    @Test
    fun `已上课时按原价结算并回收优惠`() {
        // 上 10 节:按原价 200 结算 = 2000 元;教材已发扣 300 元;赠品未退扣 50 元
        val q = computeRefund(base(consumedPaid = 10, giftReturned = false))
        assertEquals(20_000, q.unitPriceCents)
        assertEquals(18_000, q.paidUnitCents)
        assertEquals(200_000, q.consumedValueCents)
        assertEquals(20_000, q.discountRecoveredCents) // 10 节 * (200-180)
        assertEquals(30_000, q.textbookDeductCents)
        assertEquals(5_000, q.giftDeductCents)
        assertEquals(864_000 - 200_000 - 30_000 - 5_000, q.refundCents)
    }

    @Test
    fun `教材未发放不扣教材费`() {
        val q = computeRefund(base(consumedPaid = 10, textbookDelivered = false))
        assertEquals(0, q.textbookDeductCents)
        assertEquals(864_000 - 200_000, q.refundCents)
    }

    @Test
    fun `投诉成立免除优惠回收与教材费`() {
        val q = computeRefund(base(consumedPaid = 10, upheldComplaint = true, giftReturned = false))
        assertEquals(180_000, q.consumedValueCents) // 按折后价结算
        assertEquals(0, q.discountRecoveredCents)
        assertEquals(0, q.textbookDeductCents)
        assertEquals(50_000, q.complaintWaiverCents) // 20000 优惠 + 30000 教材
        assertEquals(864_000 - 180_000 - 5_000, q.refundCents)
    }

    @Test
    fun `已消耗赠课默认不抵扣,政策开启后按原价抵扣`() {
        val off = computeRefund(base(consumedPaid = 10, consumedGift = 3))
        assertEquals(0, off.giftConsumedDeductCents)
        val on = computeRefund(base(consumedPaid = 10, consumedGift = 3, giftConsumedDeducts = true))
        assertEquals(60_000, on.giftConsumedDeductCents) // 3 * 200
    }

    @Test
    fun `分期未缴足且消耗超额时学员倒欠`() {
        // 只缴了 1800 元,但已上 20 节(原价结算 4000 元)
        val q = computeRefund(base(consumedPaid = 20, paidCents = 180_000, textbookDelivered = false))
        assertEquals(0, q.refundCents)
        assertEquals(220_000, q.studentOwesCents)
    }
}
