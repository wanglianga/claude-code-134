package com.trialflow

import com.trialflow.api.ApiException
import com.trialflow.domain.Campus
import com.trialflow.domain.Classroom
import com.trialflow.domain.ClassType
import com.trialflow.domain.Level
import com.trialflow.domain.Role
import com.trialflow.domain.Subject
import com.trialflow.domain.Teacher
import com.trialflow.domain.Textbook
import com.trialflow.domain.UserAccount
import com.trialflow.infra.LockService
import com.trialflow.service.ClassService
import com.trialflow.service.LeadService
import com.trialflow.service.TrajectoryService
import com.trialflow.service.TrialService
import com.trialflow.store.DataStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * 试听排课匹配与冲突测试(Redis 不可用时降级为进程内锁)。
 */
class TrialServiceTest {

    private lateinit var store: DataStore
    private lateinit var trialService: TrialService
    private lateinit var classService: ClassService
    private lateinit var leadService: LeadService

    private val S1 = "2026-10-10T10:00"
    private val S2 = "2026-10-11T10:00"
    private val S3 = "2026-10-12T10:00"
    private val S4 = "2026-10-13T10:00"

    @BeforeEach
    fun setup() {
        store = DataStore()
        val trajectory = TrajectoryService(store)
        // Redis 连接失败 -> LockService 降级为进程内锁
        val redis = Mockito.mock(StringRedisTemplate::class.java)
        Mockito.`when`(redis.opsForValue()).thenThrow(RedisConnectionFailureException("redis down"))
        val locks = LockService(redis, false)
        trialService = TrialService(store, locks, trajectory)
        classService = ClassService(store, locks, trajectory, trialService)
        leadService = LeadService(store, trajectory)

        store.campuses["C1"] = Campus("C1", "校区一")
        store.campuses["C2"] = Campus("C2", "校区二")
        store.classrooms["R1"] = Classroom("R1", "C1", "C1-教室", 4)
        store.classrooms["R2"] = Classroom("R2", "C2", "C2-教室", 4)
        store.users["CS1"] = UserAccount("CS1", "顾问一", Role.CONSULTANT, setOf("C1"))
        // T1 为跨校区共享老师
        store.teachers["T1"] = Teacher(
            "T1", "共享老师",
            subjects = setOf(Subject.MATH), levels = setOf(Level.INTERMEDIATE),
            textbooks = setOf(Textbook.PEP), campusIds = setOf("C1", "C2"),
            availableSlots = mutableSetOf(S1, S2, S3, S4),
        )
        store.teachers["T2"] = Teacher(
            "T2", "校区二老师",
            subjects = setOf(Subject.MATH), levels = setOf(Level.INTERMEDIATE),
            textbooks = setOf(Textbook.PEP), campusIds = setOf("C2"),
            availableSlots = mutableSetOf(S1, S2, S3, S4),
        )
    }

    private fun newLead(campusId: String, textbook: Textbook = Textbook.PEP) = leadService.create(
        "小明", "四年级", Subject.MATH, Level.INTERMEDIATE, textbook,
        setOf(S1, S2, S3, S4), "自然到店", "CS1", campusId, false, "test",
    )

    @Test
    fun `同时段同老师不能被重复预约`() {
        val lead1 = newLead("C1")
        trialService.book(lead1.id, S1, null, null, null, "test")
        val lead2 = newLead("C1")
        val ex = assertThrows<ApiException> { trialService.book(lead2.id, S1, null, null, null, "test") }
        assertEquals("NO_MATCH", ex.code)
    }

    @Test
    fun `多校区共享老师跨校区同时段不可重复排课`() {
        val lead1 = newLead("C1")
        trialService.book(lead1.id, S1, null, null, null, "test")
        // C2 的线索同时段只能匹配到 T2,匹配不到已占用的共享老师 T1
        val lead2 = newLead("C2")
        val options = trialService.match(lead2.id, S1)
        assertTrue(options.none { it.teacherId == "T1" }, "共享老师 T1 同时段不应再被匹配")
        assertTrue(options.any { it.teacherId == "T2" })
    }

    @Test
    fun `随班试听占班级容量,容量满后不可再约`() {
        val group = classService.createClassGroup(
            "C2", Subject.MATH, Level.INTERMEDIATE, Textbook.PEP, ClassType.SMALL,
            "T2", "R2", 1, setOf(S3), "test",
        )
        val lead1 = newLead("C2")
        val booked = trialService.book(lead1.id, S3, null, null, group.id, "test")
        assertEquals(group.id, booked.classGroupId)
        val lead2 = newLead("C2")
        // 容量 1 已被试听占位,且 T2 该时段有常规班课不能排 1v1
        assertThrows<ApiException> { trialService.book(lead2.id, S3, null, null, group.id, "test") }
    }

    @Test
    fun `老师临时请假联动取消试听与班级课次`() {
        val lead = newLead("C1")
        val trial = trialService.book(lead.id, S2, null, null, null, "test")
        val group = classService.createClassGroup(
            "C1", Subject.MATH, Level.INTERMEDIATE, Textbook.PEP, ClassType.SMALL,
            "T1", "R1", 4, setOf(S4), "test",
        )
        val result = classService.teacherLeave("T1", setOf(S2, S4), "家中有事", "test")
        assertEquals(listOf(trial.id), result["cancelledTrials"])
        assertEquals(listOf("${group.id}@$S4"), result["cancelledClassSessions"])
        // 请假时段不可再约
        val lead2 = newLead("C1")
        assertThrows<ApiException> { trialService.book(lead2.id, S2, null, null, null, "test") }
    }

    @Test
    fun `多次改约记录风险轨迹`() {
        val lead = newLead("C1")
        val t1 = trialService.book(lead.id, S1, null, null, null, "test")
        val t2 = trialService.reschedule(t1.id, S2, "test")
        trialService.reschedule(t2.id, S3, "test")
        val events = store.trajectory[lead.id]!!.map { it.type }
        assertEquals(2, store.leads[lead.id]!!.rescheduleCount)
        assertTrue(events.contains("RISK_FLAG"), "多次改约应产生风险标记")
    }

    @Test
    fun `教材版本不匹配则无排课资源`() {
        val lead = newLead("C1", textbook = Textbook.SJ)
        assertThrows<ApiException> { trialService.book(lead.id, S1, null, null, null, "test") }
    }

    @Test
    fun `不在家长可上课时间内不能预约`() {
        val lead = newLead("C1")
        val ex = assertThrows<ApiException> {
            trialService.book(lead.id, "2026-12-01T10:00", null, null, null, "test")
        }
        assertEquals("SLOT_NOT_AVAILABLE", ex.code)
    }
}
