package com.trialflow.store

import com.trialflow.domain.*
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * 内存数据存储(演示用,接口与 Redis/DB 持久化解耦在服务层之外)。
 * 所有实体表 + 教室占用表 + 履约轨迹事件流。
 */
@Component
class DataStore {
    val campuses = ConcurrentHashMap<String, Campus>()
    val classrooms = ConcurrentHashMap<String, Classroom>()
    val users = ConcurrentHashMap<String, UserAccount>()
    val teachers = ConcurrentHashMap<String, Teacher>()
    val leads = ConcurrentHashMap<String, Lead>()
    val trials = ConcurrentHashMap<String, TrialClass>()
    val feedbacks = ConcurrentHashMap<String, TrialFeedback>() // key: trialId
    val classGroups = ConcurrentHashMap<String, ClassGroup>()
    val students = ConcurrentHashMap<String, Student>()
    val packages = ConcurrentHashMap<String, CoursePackage>()
    val contracts = ConcurrentHashMap<String, Contract>()
    val accounts = ConcurrentHashMap<String, ConsumptionAccount>() // key: studentId
    val commissions = ConcurrentHashMap<String, Commission>()
    val channelFees = ConcurrentHashMap<String, ChannelFee>()
    val complaints = ConcurrentHashMap<String, Complaint>()
    val giftDisputes = ConcurrentHashMap<String, GiftDispute>()
    val refunds = ConcurrentHashMap<String, RefundRecord>()

    /** 教室占用:classroomId -> 已占用时段 */
    val classroomBusy = ConcurrentHashMap<String, MutableSet<String>>()

    /** 随班试听占位:"classGroupId@slot" -> 试听ID 集合(占班级容量) */
    val classTrialSeats = ConcurrentHashMap<String, MutableSet<String>>()

    /** 履约轨迹:ownerKey(leadId/studentId) -> 事件流 */
    val trajectory = ConcurrentHashMap<String, CopyOnWriteArrayList<TrajectoryEvent>>()

    private val idGen = AtomicLong(1000)

    fun nextId(prefix: String): String = "$prefix-${idGen.incrementAndGet()}"

    fun record(event: TrajectoryEvent) {
        trajectory.computeIfAbsent(event.ownerKey) { CopyOnWriteArrayList() }.add(event)
    }

    fun reset() {
        listOf(
            campuses, classrooms, users, teachers, leads, trials, feedbacks, classGroups,
            students, packages, contracts, accounts, commissions, channelFees, complaints,
            giftDisputes, refunds,
        ).forEach { it.clear() }
        classroomBusy.clear()
        classTrialSeats.clear()
        trajectory.clear()
    }
}
