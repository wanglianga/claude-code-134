package com.trialflow.service

import com.trialflow.domain.TrajectoryEvent
import com.trialflow.store.DataStore
import org.springframework.stereotype.Service
import java.time.Instant

/** 履约轨迹:从线索进入到退费结束的完整事件流 */
@Service
class TrajectoryService(private val store: DataStore) {

    fun record(
        ownerKey: String,
        type: String,
        actor: String,
        summary: String,
        details: Map<String, Any?> = emptyMap(),
        at: Instant = Instant.now(),
    ): TrajectoryEvent {
        val event = TrajectoryEvent(store.nextId("EVT"), ownerKey, at.toString(), type, actor, summary, details)
        store.record(event)
        return event
    }

    /** 学员完整轨迹 = 线索阶段事件 + 学员阶段事件,按时间排序 */
    fun trajectoryOfStudent(studentId: String): List<TrajectoryEvent> {
        val student = store.students[studentId] ?: return emptyList()
        val events = mutableListOf<TrajectoryEvent>()
        store.trajectory[student.leadId]?.let { events.addAll(it) }
        store.trajectory[studentId]?.let { events.addAll(it) }
        return events.sortedBy { it.at }
    }

    fun trajectoryOfLead(leadId: String): List<TrajectoryEvent> =
        store.trajectory[leadId]?.sortedBy { it.at } ?: emptyList()
}
