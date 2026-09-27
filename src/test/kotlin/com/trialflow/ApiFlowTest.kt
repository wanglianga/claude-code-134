package com.trialflow

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * 全链路集成测试:线索 -> 试听(改约/到课/赠品)-> 反馈 -> 承诺核对 -> 报名
 * -> 分期/发票 -> 课消(到课/请假/缺席)-> 插班/转班/停课 -> 赠课争议
 * -> 投诉 -> 退费(提成冻结追回/发票红冲/赠课作废)-> 履约轨迹 -> 校长视图。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiFlowTest {

    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var om: ObjectMapper

    private fun postJson(url: String, payload: Map<String, Any?>, idemKey: String? = null): MvcResult {
        var req = post(url).contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(payload))
            .header("X-Actor-Id", "CS001")
        if (idemKey != null) req = req.header("X-Idempotency-Key", idemKey)
        return mvc.perform(req).andReturn()
    }

    private fun MvcResult.andExpectStatus(code: Int): MvcResult {
        if (response.status != code) {
            throw AssertionError("期望 HTTP $code,实际 ${response.status}: ${response.contentAsString}")
        }
        return this
    }

    private fun json(result: MvcResult): JsonNode = om.readTree(result.response.contentAsString)

    private fun getJson(url: String): JsonNode =
        om.readTree(mvc.perform(get(url)).andExpect(status().isOk).andReturn().response.contentAsString)

    /** 工作日 16:00 时段(避开种子班级的周末课次) */
    private fun weekdaySlot(daysAhead: Long): String {
        var d = LocalDate.now().plusDays(daysAhead)
        while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) d = d.plusDays(1)
        return "${d}T16:00"
    }

    private fun classSlots(campusId: String, subject: String, level: String): Pair<String, List<String>> {
        val classes = getJson("/api/classes?campusId=$campusId")
        val clazz = classes.first { it["subject"].asText() == subject && it["level"].asText() == level }
        return clazz["id"].asText() to clazz["slots"].map { it.asText() }.sorted()
    }

    @Test
    fun `完整履约链路,线索到退费结束`() {
        val w1 = weekdaySlot(3)
        val w2 = weekdaySlot(4)
        val w3 = weekdaySlot(5)
        val (zgcMathClass, zgcSlots) = classSlots("CAMP-ZGC", "MATH", "INTERMEDIATE")
        val (wjMathClass, wjSlots) = classSlots("CAMP-WJ", "MATH", "INTERMEDIATE")

        // 1. 线索
        val lead = json(
            postJson(
                "/api/leads",
                mapOf(
                    "studentName" to "王小明", "grade" to "四年级", "subject" to "MATH",
                    "level" to "INTERMEDIATE", "textbook" to "PEP",
                    "availableSlots" to listOf(w1, w2, w3),
                    "channel" to "大众点评", "consultantId" to "CS001", "campusId" to "CAMP-ZGC",
                    "activityParticipated" to true,
                ),
            ).andExpectStatus(201),
        )
        val leadId = lead["id"].asText()

        // 2. 试听匹配 + 预约(1v1)
        val options = getJson("/api/trials/match?leadId=$leadId&slot=$w1")
        assertTrue(options.size() > 0, "应有可排课选项")
        val trial = json(postJson("/api/trials", mapOf("leadId" to leadId, "slot" to w1)).andExpectStatus(201))
        val trialId = trial["id"].asText()
        assertEquals("T001", trial["teacherId"].asText())

        // 3. 两次改约 -> 风险标记
        val t2 = json(postJson("/api/trials/$trialId/reschedule", mapOf("newSlot" to w2)).andExpectStatus(200))
        val t3 = json(postJson("/api/trials/${t2["id"].asText()}/reschedule", mapOf("newSlot" to w3)).andExpectStatus(200))
        val finalTrialId = t3["id"].asText()

        // 4. 到课(发赠品)+ 老师反馈
        postJson("/api/trials/$finalTrialId/attend", mapOf("giftName" to "错题本礼盒", "giftValueCents" to 5000)).andExpectStatus(200)
        postJson(
            "/api/trials/$finalTrialId/feedback",
            mapOf(
                "teacherId" to "T001", "performance" to "互动积极,但计算粗心",
                "assessmentScore" to 78, "suitableClassType" to "SMALL",
                "riskWarnings" to listOf("计算基础薄弱"),
            ),
        ).andExpectStatus(201)

        // 5. 承诺与试听反馈不一致 -> 409;人工确认后签约成功
        val contractReq = mutableMapOf<String, Any?>(
            "leadId" to leadId, "packageId" to "PKG-MATH-48", "classGroupId" to zgcMathClass,
            "giftLessons" to 4, "discountCents" to 96000,
            "installments" to listOf(
                mapOf("no" to 1, "dueDate" to "2026-10-01", "amountCents" to 432000),
                mapOf("no" to 2, "dueDate" to "2026-11-01", "amountCents" to 432000),
            ),
            "paidInstallments" to 1,
            "textbookDelivered" to true,
            "promises" to listOf(
                mapOf("type" to "CLASS_TYPE", "content" to "承诺一对一教学", "refValue" to "ONE_ON_ONE", "promisedBy" to "CS001"),
                mapOf("type" to "OTHER", "content" to "风险已告知家长", "promisedBy" to "CS001"),
            ),
        )
        val mismatch = json(postJson("/api/contracts", contractReq).andExpectStatus(409))
        assertEquals("PROMISE_MISMATCH", mismatch["error"]["code"].asText())

        contractReq["acknowledgeMismatch"] = true
        val contract = json(postJson("/api/contracts", contractReq, idemKey = "flow-contract-1").andExpectStatus(201))
        val contractId = contract["id"].asText()
        val studentId = contract["studentId"].asText()
        assertEquals("MISMATCH", contract["promiseCheck"]["status"].asText())
        assertEquals(86400, contract["channelFeeCents"].asInt()) // 大众点评 10%
        assertEquals(432000, contract["paidCents"].asInt())      // 已缴 1 期

        // 幂等重放:同 key 重复提交返回同一合同
        val replay = postJson("/api/contracts", contractReq, idemKey = "flow-contract-1").andExpectStatus(200)
        assertEquals(contractId, json(replay)["id"].asText())
        assertEquals("true", replay.response.getHeader("X-Idempotent-Replay"))

        // 6. 缴清分期 + 开票
        postJson("/api/contracts/$contractId/pay-installment", mapOf("no" to 2)).andExpectStatus(200)
        val invoiced = json(postJson("/api/contracts/$contractId/invoice", emptyMap()).andExpectStatus(200))
        assertEquals(864000, invoiced["invoice"]["amountCents"].asInt())

        // 7. 课消:到课 x3、提前请假(不消)、临时请假(消)、缺席(消)
        repeat(3) { i ->
            postJson("/api/students/$studentId/lessons/complete", mapOf("slot" to zgcSlots[i])).andExpectStatus(200)
        }
        val earlyLeave = LocalDateTime.parse(zgcSlots[3]).minusHours(48).toInstant(ZoneOffset.UTC).toString()
        postJson(
            "/api/students/$studentId/lessons/leave",
            mapOf("slot" to zgcSlots[3], "requestedAt" to earlyLeave, "reason" to "外出比赛"),
        ).andExpectStatus(200)
        val lateLeave = LocalDateTime.parse(zgcSlots[4]).minusHours(2).toInstant(ZoneOffset.UTC).toString()
        postJson(
            "/api/students/$studentId/lessons/leave",
            mapOf("slot" to zgcSlots[4], "requestedAt" to lateLeave, "reason" to "临时发烧"),
        ).andExpectStatus(200)
        postJson("/api/students/$studentId/lessons/absent", mapOf("slot" to zgcSlots[5])).andExpectStatus(200)

        var account = getJson("/api/students/$studentId/account")
        assertEquals(5, account["consumedPaid"].asInt()) // 3 到课 + 1 临时请假 + 1 缺席
        assertEquals(2, account["leaveCount"].asInt())
        assertEquals(1, account["absentCount"].asInt())

        // 8. 赠课争议:主张 6 节,成立补 2 节
        val dispute = json(
            postJson(
                "/api/contracts/$contractId/gift-disputes",
                mapOf("claimedGiftLessons" to 6, "reason" to "顾问口头承诺 6 节赠课"),
            ).andExpectStatus(201),
        )
        postJson("/api/gift-disputes/${dispute["id"].asText()}/resolve", mapOf("approve" to true)).andExpectStatus(200)
        account = getJson("/api/students/$studentId/account")
        assertEquals(6, account["giftTotal"].asInt())

        // 9. 转班(跨校区)-> 在新校区上课
        postJson("/api/students/$studentId/transfer", mapOf("toClassGroupId" to wjMathClass)).andExpectStatus(200)
        postJson("/api/students/$studentId/lessons/complete", mapOf("slot" to wjSlots[0])).andExpectStatus(200)
        account = getJson("/api/students/$studentId/account")
        assertEquals(6, account["consumedPaid"].asInt())

        // 10. 停课期间不能课消,复课恢复
        postJson("/api/contracts/$contractId/suspend", mapOf("reason" to "家中有事暂停")).andExpectStatus(200)
        postJson("/api/students/$studentId/lessons/complete", mapOf("slot" to wjSlots[1])).andExpectStatus(409)
        postJson("/api/contracts/$contractId/resume", emptyMap()).andExpectStatus(200)

        // 11. 投诉成立 -> 退费免除优惠回收与教材费
        val complaint = json(
            postJson("/api/students/$studentId/complaints", mapOf("content" to "承诺一对一实际为小班课")).andExpectStatus(201),
        )
        postJson("/api/complaints/${complaint["id"].asText()}/resolve", mapOf("upheld" to true, "note" to "情况属实")).andExpectStatus(200)

        // 12. 退费试算:已上 6 节按折后价 180 结算(投诉成立),赠品未退扣 50 元
        val quote = getJson("/api/contracts/$contractId/refund-quote?giftReturned=false")
        assertEquals(6, quote["consumedPaidLessons"].asInt())
        assertEquals(108000, quote["consumedValueCents"].asInt())
        assertEquals(42000, quote["complaintWaiverCents"].asInt()) // 优惠回收 12000 + 教材费 30000
        assertEquals(5000, quote["giftDeductCents"].asInt())
        assertEquals(751000, quote["refundCents"].asInt())

        // 13. 执行退费(幂等)
        val refund = json(
            postJson(
                "/api/contracts/$contractId/refund",
                mapOf("reason" to "服务与承诺不符", "giftReturned" to false),
                idemKey = "flow-refund-1",
            ).andExpectStatus(201),
        )
        assertEquals(751000, refund["quote"]["refundCents"].asInt())
        val refundReplay = postJson(
            "/api/contracts/$contractId/refund",
            mapOf("reason" to "服务与承诺不符", "giftReturned" to false),
            idemKey = "flow-refund-1",
        ).andExpectStatus(200)
        assertEquals(refund["id"].asText(), json(refundReplay)["id"].asText())

        // 14. 状态联动:合同关闭、发票红冲、赠课作废、提成追回
        val closed = getJson("/api/contracts/$contractId")
        assertEquals("CLOSED_REFUND", closed["status"].asText())
        assertEquals("RED_FLUSHED", closed["invoice"]["status"].asText())
        account = getJson("/api/students/$studentId/account")
        assertEquals(0, account["remainingGift"].asInt())
        val perf = getJson("/api/consultants/CS001/performance")
        assertEquals(37550, perf["commission"]["clawedBackCents"].asInt()) // 43200 * 751000/864000

        // 15. 履约轨迹完整
        val trajectory = getJson("/api/students/$studentId/trajectory")
        val types = trajectory["trajectory"].map { it["type"].asText() }.toSet()
        listOf(
            "LEAD_CREATED", "TRIAL_BOOKED", "TRIAL_RESCHEDULED", "RISK_FLAG", "TRIAL_ATTENDED",
            "TRIAL_FEEDBACK", "CONVERTED", "CONTRACT_SIGNED", "PROMISE_MISMATCH_ACKED",
            "INSTALLMENT_PAID", "INVOICE_ISSUED", "LESSON_CONSUMED", "LESSON_LEAVE",
            "LESSON_LEAVE_LATE", "LESSON_ABSENT", "GIFT_DISPUTE_RESOLVED", "CLASS_TRANSFERRED",
            "CONTRACT_SUSPENDED", "CONTRACT_RESUMED", "COMPLAINT_FILED", "COMPLAINT_RESOLVED",
            "COMMISSION_FROZEN", "COMMISSION_CLAWED_BACK", "INVOICE_RED_FLUSHED", "REFUND_EXECUTED",
        ).forEach { assertTrue(types.contains(it), "轨迹缺少事件: $it") }

        // 16. 校长经营视图
        val overview = getJson("/api/principal/overview")
        assertTrue(overview["funnel"]["converted"].asInt() >= 1)
        assertTrue(overview["finance"]["refundedCents"].asLong() >= 751000)
    }

    @Test
    fun `报名后插班与班级停课`() {
        val w1 = weekdaySlot(6)
        // 先取种子班(避免与本测试新建的班级混淆)
        val (zgcM2, m2Slots) = classSlots("CAMP-ZGC", "MATH", "ELEMENTARY")

        // 第二个学员:ELEMENTARY 数学
        val lead = json(
            postJson(
                "/api/leads",
                mapOf(
                    "studentName" to "李小红", "grade" to "二年级", "subject" to "MATH",
                    "level" to "ELEMENTARY", "textbook" to "PEP",
                    "availableSlots" to listOf(w1),
                    "channel" to "老带新", "consultantId" to "CS001", "campusId" to "CAMP-ZGC",
                    "activityParticipated" to false,
                ),
            ).andExpectStatus(201),
        )
        val leadId = lead["id"].asText()
        val trial = json(postJson("/api/trials", mapOf("leadId" to leadId, "slot" to w1)).andExpectStatus(201))
        postJson("/api/trials/${trial["id"].asText()}/attend", emptyMap<String, Any?>()).andExpectStatus(200)
        postJson(
            "/api/trials/${trial["id"].asText()}/feedback",
            mapOf(
                "teacherId" to "T001", "performance" to "基础扎实", "assessmentScore" to 88,
                "suitableClassType" to "SMALL", "riskWarnings" to emptyList<String>(),
            ),
        ).andExpectStatus(201)

        // 新建 ELEMENTARY 小班并签约
        val newClass = json(
            postJson(
                "/api/classes",
                mapOf(
                    "campusId" to "CAMP-ZGC", "subject" to "MATH", "level" to "ELEMENTARY",
                    "textbook" to "PEP", "type" to "SMALL",
                    "teacherId" to "T001", "classroomId" to "ROOM-ZGC-1",
                    "capacity" to 2, "slots" to listOf(w1),
                ),
            ).andExpectStatus(201),
        )
        val contract = json(
            postJson(
                "/api/contracts",
                mapOf(
                    "leadId" to leadId, "packageId" to "PKG-MATH-24", "classGroupId" to newClass["id"].asText(),
                    "giftLessons" to 2, "discountCents" to 0,
                    "installments" to listOf(mapOf("no" to 1, "dueDate" to "2026-10-01", "amountCents" to 480000)),
                    "paidInstallments" to 1, "textbookDelivered" to false,
                    "promises" to listOf(
                        mapOf("type" to "CLASS_TYPE", "content" to "小班教学", "refValue" to "SMALL", "promisedBy" to "CS001"),
                    ),
                ),
            ).andExpectStatus(201),
        )
        val studentId = contract["studentId"].asText()
        assertEquals("CONSISTENT", contract["promiseCheck"]["status"].asText())

        // 插班到种子班 CLASS-ZGC-M2(ELEMENTARY)
        postJson("/api/classes/$zgcM2/insert", mapOf("studentId" to studentId)).andExpectStatus(200)
        val afterInsert = getJson("/api/classes/$zgcM2")
        assertTrue(afterInsert["studentIds"].any { it.asText() == studentId })

        // 班级停课 -> 不能课消;复课 -> 恢复
        postJson("/api/classes/$zgcM2/suspend", mapOf("reason" to "教室装修")).andExpectStatus(200)
        postJson("/api/students/$studentId/lessons/complete", mapOf("slot" to m2Slots[0])).andExpectStatus(409)
        postJson("/api/classes/$zgcM2/resume", emptyMap<String, Any?>()).andExpectStatus(200)
        postJson("/api/students/$studentId/lessons/complete", mapOf("slot" to m2Slots[0])).andExpectStatus(200)
    }
}
