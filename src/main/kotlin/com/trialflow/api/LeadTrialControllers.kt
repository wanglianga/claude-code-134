package com.trialflow.api

import com.trialflow.domain.LeadStatus
import com.trialflow.service.LeadService
import com.trialflow.service.TrialService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/leads")
class LeadController(private val leadService: LeadService) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @Valid @RequestBody req: CreateLeadReq,
        @RequestHeader("X-Actor-Id", required = false) actor: String?,
    ) = leadService.create(
        req.studentName, req.grade, req.subject, req.level, req.textbook,
        req.availableSlots, req.channel, req.consultantId, req.campusId,
        req.activityParticipated, actor ?: "system",
    )

    @GetMapping("/{id}")
    fun get(@PathVariable id: String) = leadService.get(id)

    @GetMapping
    fun list(
        @RequestParam(required = false) consultantId: String?,
        @RequestParam(required = false) campusId: String?,
        @RequestParam(required = false) status: LeadStatus?,
    ) = leadService.list(consultantId, campusId, status)

    /** 试听后换校区 */
    @PostMapping("/{id}/transfer-campus")
    fun transferCampus(
        @PathVariable id: String,
        @Valid @RequestBody req: TransferCampusReq,
        @RequestHeader("X-Actor-Id", required = false) actor: String?,
    ) = leadService.transferCampus(id, req.toCampusId, req.reason, actor ?: "system")

    @PostMapping("/{id}/invalidate")
    fun invalidate(
        @PathVariable id: String,
        @RequestBody req: ReasonReq,
        @RequestHeader("X-Actor-Id", required = false) actor: String?,
    ) = leadService.invalidate(id, req.reason ?: "未填写", actor ?: "system")
}

@RestController
@RequestMapping("/api/trials")
class TrialController(private val trialService: TrialService) {

    /** 试听匹配预览:老师空档 × 教室 × 班级容量 × 教材版本 × 学生水平 */
    @GetMapping("/match")
    fun match(@RequestParam leadId: String, @RequestParam slot: String) = trialService.match(leadId, slot)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun book(
        @Valid @RequestBody req: BookTrialReq,
        @RequestHeader("X-Actor-Id", required = false) actor: String?,
    ) = trialService.book(req.leadId, req.slot, req.teacherId, req.classroomId, req.classGroupId, actor ?: "system")

    @GetMapping("/{id}")
    fun get(@PathVariable id: String) = trialService.getTrial(id)

    /** 改约试听 */
    @PostMapping("/{id}/reschedule")
    fun reschedule(
        @PathVariable id: String,
        @Valid @RequestBody req: RescheduleTrialReq,
        @RequestHeader("X-Actor-Id", required = false) actor: String?,
    ) = trialService.reschedule(id, req.newSlot, actor ?: "system")

    /** 试听到课(可登记体验课赠品) */
    @PostMapping("/{id}/attend")
    fun attend(
        @PathVariable id: String,
        @RequestBody(required = false) req: AttendTrialReq?,
        @RequestHeader("X-Actor-Id", required = false) actor: String?,
    ) = trialService.attend(id, req?.giftName, req?.giftValueCents, actor ?: "system")

    /** 试听缺席 */
    @PostMapping("/{id}/absent")
    fun absent(
        @PathVariable id: String,
        @RequestHeader("X-Actor-Id", required = false) actor: String?,
    ) = trialService.absent(id, actor ?: "system")

    @PostMapping("/{id}/cancel")
    fun cancel(
        @PathVariable id: String,
        @RequestBody req: ReasonReq,
        @RequestHeader("X-Actor-Id", required = false) actor: String?,
    ) = trialService.cancel(id, req.reason ?: "未填写", actor ?: "system")

    /** 老师提交试听反馈 */
    @PostMapping("/{id}/feedback")
    @ResponseStatus(HttpStatus.CREATED)
    fun feedback(
        @PathVariable id: String,
        @Valid @RequestBody req: FeedbackReq,
        @RequestHeader("X-Actor-Id", required = false) actor: String?,
    ) = trialService.submitFeedback(
        id, req.teacherId, req.performance, req.assessmentScore,
        req.suitableClassType, req.riskWarnings, actor ?: "system",
    )

    @GetMapping("/{id}/feedback")
    fun getFeedback(@PathVariable id: String) =
        trialService.feedbackOf(id) ?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "该试听尚无反馈")
}
