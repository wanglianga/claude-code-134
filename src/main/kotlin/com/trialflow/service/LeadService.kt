package com.trialflow.service

import com.trialflow.api.badRequest
import com.trialflow.api.conflict
import com.trialflow.api.notFound
import com.trialflow.domain.Lead
import com.trialflow.domain.LeadStatus
import com.trialflow.domain.Level
import com.trialflow.domain.Subject
import com.trialflow.domain.Textbook
import com.trialflow.store.DataStore
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class LeadService(
    private val store: DataStore,
    private val trajectory: TrajectoryService,
) {

    fun create(
        studentName: String, grade: String, subject: Subject, level: Level, textbook: Textbook,
        availableSlots: Set<String>, channel: String, consultantId: String, campusId: String,
        activityParticipated: Boolean, actor: String,
    ): Lead {
        if (availableSlots.isEmpty()) badRequest("VALIDATION", "可上课时间不能为空")
        store.users[consultantId] ?: notFound("顾问", consultantId)
        store.campuses[campusId] ?: notFound("校区", campusId)
        val lead = Lead(
            id = store.nextId("LEAD"),
            studentName = studentName, grade = grade, subject = subject, level = level,
            textbook = textbook, availableSlots = availableSlots, channel = channel,
            consultantId = consultantId, campusId = campusId,
            activityParticipated = activityParticipated,
            createdAt = Instant.now().toString(),
        )
        store.leads[lead.id] = lead
        trajectory.record(
            lead.id, "LEAD_CREATED", actor,
            "线索创建:${studentName}/${grade}/${subject},渠道[$channel],校区[${campusId}]",
            mapOf(
                "grade" to grade, "subject" to subject.name, "level" to level.name,
                "channel" to channel, "consultantId" to consultantId, "campusId" to campusId,
                "activityParticipated" to activityParticipated,
            ),
        )
        return lead
    }

    fun get(id: String): Lead = store.leads[id] ?: notFound("线索", id)

    fun list(consultantId: String?, campusId: String?, status: LeadStatus?): List<Lead> =
        store.leads.values.filter {
            (consultantId == null || it.consultantId == consultantId) &&
                (campusId == null || it.campusId == campusId) &&
                (status == null || it.status == status)
        }.sortedBy { it.createdAt }

    /** 试听后换校区 */
    fun transferCampus(leadId: String, toCampusId: String, reason: String?, actor: String): Lead {
        val lead = get(leadId)
        store.campuses[toCampusId] ?: notFound("校区", toCampusId)
        if (lead.status == LeadStatus.CONVERTED) conflict("LEAD_CONVERTED", "线索已转化,请通过学员转班/换校区流程处理")
        if (lead.campusId == toCampusId) badRequest("SAME_CAMPUS", "目标校区与当前校区相同")
        val from = lead.campusId
        lead.campusId = toCampusId
        trajectory.record(
            lead.id, "LEAD_CAMPUS_TRANSFERRED", actor,
            "试听后换校区:$from -> $toCampusId${reason?.let { ",原因:$it" } ?: ""}",
            mapOf("fromCampusId" to from, "toCampusId" to toCampusId, "reason" to reason),
        )
        return lead
    }

    fun invalidate(leadId: String, reason: String, actor: String): Lead {
        val lead = get(leadId)
        if (lead.status == LeadStatus.CONVERTED) conflict("LEAD_CONVERTED", "已转化线索不能作废")
        lead.status = LeadStatus.INVALID
        trajectory.record(lead.id, "LEAD_INVALIDATED", actor, "线索作废:$reason", mapOf("reason" to reason))
        return lead
    }
}
