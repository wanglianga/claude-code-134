package com.trialflow.api

import com.trialflow.service.QueryService
import com.trialflow.service.RefundService
import com.trialflow.store.DataStore
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
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
@RequestMapping("/api")
class RefundController(private val refundService: RefundService) {

    /** 退费试算(不生效) */
    @GetMapping("/contracts/{id}/refund-quote")
    fun quote(
        @PathVariable id: String,
        @RequestParam(defaultValue = "false") giftReturned: Boolean,
    ) = refundService.quote(id, giftReturned)

    /** 执行退费:同步合同/课消/发票/提成/班级状态 */
    @PostMapping("/contracts/{id}/refund")
    fun refund(
        @PathVariable id: String,
        @Valid @RequestBody req: RefundReq,
        @RequestHeader("X-Idempotency-Key", required = false) idemKey: String?,
        @RequestHeader("X-Actor-Id", required = false) actor: String?,
    ): ResponseEntity<Any> {
        val result = refundService.execute(id, req.reason, req.giftReturned, idemKey, actor ?: "system")
        val status = if (result.replayed) HttpStatus.OK else HttpStatus.CREATED
        return ResponseEntity.status(status)
            .header("X-Idempotent-Replay", result.replayed.toString())
            .body(result.record as Any)
    }

    @PostMapping("/students/{id}/complaints")
    @ResponseStatus(HttpStatus.CREATED)
    fun fileComplaint(
        @PathVariable id: String,
        @Valid @RequestBody req: ComplaintReq,
        @RequestHeader("X-Actor-Id", required = false) actor: String?,
    ) = refundService.fileComplaint(id, req.content, actor ?: "system")

    @PostMapping("/complaints/{id}/resolve")
    fun resolveComplaint(
        @PathVariable id: String,
        @Valid @RequestBody req: ResolveReq,
        @RequestHeader("X-Actor-Id", required = false) actor: String?,
    ) = refundService.resolveComplaint(id, req.upheld!!, req.note, actor ?: "system")

    /** 赠课争议 */
    @PostMapping("/contracts/{id}/gift-disputes")
    @ResponseStatus(HttpStatus.CREATED)
    fun fileGiftDispute(
        @PathVariable id: String,
        @Valid @RequestBody req: GiftDisputeReq,
        @RequestHeader("X-Actor-Id", required = false) actor: String?,
    ) = refundService.fileGiftDispute(id, req.claimedGiftLessons, req.reason, actor ?: "system")

    @PostMapping("/gift-disputes/{id}/resolve")
    fun resolveGiftDispute(
        @PathVariable id: String,
        @Valid @RequestBody req: ResolveDisputeReq,
        @RequestHeader("X-Actor-Id", required = false) actor: String?,
    ) = refundService.resolveGiftDispute(id, req.approve!!, req.note, actor ?: "system")
}

@RestController
@RequestMapping("/api")
class QueryController(private val queryService: QueryService) {

    /** 校长视角:学员从线索到退费结束的完整履约轨迹 */
    @GetMapping("/students/{id}/trajectory")
    fun trajectory(@PathVariable id: String) = queryService.studentTrajectory(id)

    /** 校长视角:经营漏斗与财务汇总 */
    @GetMapping("/principal/overview")
    fun overview(@RequestParam(required = false) campusId: String?) = queryService.principalOverview(campusId)

    /** 顾问业绩与提成(含冻结/追回) */
    @GetMapping("/consultants/{id}/performance")
    fun performance(@PathVariable id: String) = queryService.consultantPerformance(id)
}

/** 基础数据查询(校区/教室/老师/课包/账号/渠道费率) */
@RestController
@RequestMapping("/api/meta")
class MetaController(private val store: DataStore) {

    @GetMapping
    fun all() = mapOf(
        "campuses" to store.campuses.values.sortedBy { it.id },
        "classrooms" to store.classrooms.values.sortedBy { it.id },
        "teachers" to store.teachers.values.map {
            mapOf(
                "id" to it.id, "name" to it.name, "subjects" to it.subjects, "levels" to it.levels,
                "textbooks" to it.textbooks, "campusIds" to it.campusIds,
                "availableSlots" to it.availableSlots.sorted(), "busySlots" to it.busySlots.sorted(),
                "leaveSlots" to it.leaveSlots.sorted(),
            )
        }.sortedBy { it["id"] as String },
        "packages" to store.packages.values.sortedBy { it.id },
        "users" to store.users.values.sortedBy { it.id },
    )
}
