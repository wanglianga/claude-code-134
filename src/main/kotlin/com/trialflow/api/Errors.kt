package com.trialflow.api

import com.trialflow.infra.LockConflictException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

class ApiException(
    val status: HttpStatus,
    val code: String,
    override val message: String,
    val details: Any? = null,
) : RuntimeException(message)

fun badRequest(code: String, msg: String, details: Any? = null): Nothing =
    throw ApiException(HttpStatus.BAD_REQUEST, code, msg, details)

fun notFound(what: String, id: String): Nothing =
    throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "$what 不存在: $id")

fun conflict(code: String, msg: String, details: Any? = null): Nothing =
    throw ApiException(HttpStatus.CONFLICT, code, msg, details)

data class ErrorBody(val code: String, val message: String, val details: Any? = null)
data class ErrorResponse(val error: ErrorBody)

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(ApiException::class)
    fun api(e: ApiException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(e.status).body(ErrorResponse(ErrorBody(e.code, e.message, e.details)))

    @ExceptionHandler(LockConflictException::class)
    fun lock(e: LockConflictException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse(ErrorBody("LOCK_CONFLICT", e.message ?: "并发冲突")))

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun validation(e: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest().body(
            ErrorResponse(
                ErrorBody(
                    "VALIDATION_FAILED",
                    e.bindingResult.fieldErrors.joinToString("; ") { "${it.field}: ${it.defaultMessage}" },
                ),
            ),
        )

    @ExceptionHandler(IllegalArgumentException::class)
    fun illegal(e: IllegalArgumentException): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest().body(ErrorResponse(ErrorBody("BAD_REQUEST", e.message ?: "参数错误")))
}
