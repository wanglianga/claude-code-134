package com.trialflow.infra

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

class LockConflictException(message: String) : RuntimeException(message)

/**
 * 分布式锁:优先走 Redis(SET NX PX + Lua 对比删除),Redis 不可用时降级为进程内锁。
 * 用于老师时段、教室时段、班级容量、合同等关键资源的并发保护。
 */
@Component
class LockService(
    private val redis: StringRedisTemplate,
    @Value("\${app.redis.required:false}") private val redisRequired: Boolean,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val localLocks = ConcurrentHashMap<String, ReentrantLock>()

    @Volatile
    private var redisDown = false

    fun isRedisDown(): Boolean = redisDown

    fun <T> withLock(key: String, ttlMs: Long = 10_000, block: () -> T): T = withLocks(listOf(key), ttlMs, block)

    /** 多把锁按字典序获取,避免死锁 */
    fun <T> withLocks(keys: List<String>, ttlMs: Long = 10_000, block: () -> T): T {
        val sorted = keys.distinct().sorted()
        if (!redisDown) {
            try {
                return withRedisLocks(sorted, ttlMs, block)
            } catch (e: DataAccessException) {
                redisDown = true
                log.warn("Redis 不可用,降级为进程内锁: {}", e.message)
                if (redisRequired) throw LockConflictException("Redis 不可用且 app.redis.required=true")
            }
        }
        return withLocalLocks(sorted, block)
    }

    private fun <T> withRedisLocks(keys: List<String>, ttlMs: Long, block: () -> T): T {
        val token = UUID.randomUUID().toString()
        val acquired = mutableListOf<String>()
        try {
            for (key in keys) {
                val ok = acquireRedis(key, token, ttlMs)
                if (!ok) throw LockConflictException("资源被并发占用,请重试: $key")
                acquired.add(key)
            }
            return block()
        } finally {
            releaseRedis(acquired.reversed(), token)
        }
    }

    private fun acquireRedis(key: String, token: String, ttlMs: Long): Boolean {
        val fullKey = "lock:$key"
        // 时间预算内指数退避重试,预算随 TTL 收缩(短 TTL 锁快速失败)
        val deadline = System.currentTimeMillis() + minOf(ttlMs, 3_000)
        var backoff = 50L
        while (System.currentTimeMillis() < deadline) {
            val ok = redis.opsForValue().setIfAbsent(fullKey, token, Duration.ofMillis(ttlMs))
            if (ok == true) return true
            Thread.sleep(backoff)
            backoff = minOf(backoff * 2, 300)
        }
        return false
    }

    private fun releaseRedis(keys: List<String>, token: String) {
        if (keys.isEmpty()) return
        val script = DefaultRedisScript<List<*>>(
            "for i,k in ipairs(KEYS) do " +
                "if redis.call('get', k) == ARGV[1] then redis.call('del', k) end " +
                "end return KEYS",
            List::class.java,
        )
        try {
            redis.execute(script, keys.map { "lock:$it" }, token)
        } catch (e: DataAccessException) {
            log.warn("释放 Redis 锁失败(等待 TTL 过期): {}", e.message)
        }
    }

    private fun <T> withLocalLocks(keys: List<String>, block: () -> T): T {
        val locks = keys.map { localLocks.computeIfAbsent(it) { ReentrantLock() } }
        for (lock in locks) {
            if (!lock.tryLock(3, TimeUnit.SECONDS)) {
                locks.filter { it.isHeldByCurrentThread }.forEach { it.unlock() }
                throw LockConflictException("资源被并发占用(本地锁),请重试")
            }
        }
        try {
            return block()
        } finally {
            locks.asReversed().filter { it.isHeldByCurrentThread }.forEach { it.unlock() }
        }
    }
}

/**
 * 幂等键:同一 X-Idempotency-Key 的重复写请求直接返回首次创建的实体,避免重复扣款/重复建单。
 * 存储 key -> 实体ID,TTL 24h;Redis 不可用时降级为进程内 Map。
 */
@Component
class IdempotencyService(
    private val redis: StringRedisTemplate,
    private val lockService: LockService,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val local = ConcurrentHashMap<String, Pair<String, Long>>() // key -> (entityId, expireAtEpochMs)
    private val ttlMs: Long = Duration.ofHours(24).toMillis()

    /** 已存在返回实体ID,不存在返回 null */
    fun lookup(key: String): String? {
        if (!lockService.isRedisDown()) {
            try {
                redis.opsForValue().get("idem:$key")?.let { return it }
            } catch (e: DataAccessException) {
                log.warn("Redis 读取幂等键失败,使用本地降级: {}", e.message)
            }
        }
        val hit = local[key]
        if (hit != null && hit.second > System.currentTimeMillis()) return hit.first
        return null
    }

    fun store(key: String, entityId: String) {
        if (!lockService.isRedisDown()) {
            try {
                redis.opsForValue().setIfAbsent("idem:$key", entityId, Duration.ofMillis(ttlMs))
                return
            } catch (e: DataAccessException) {
                log.warn("Redis 写入幂等键失败,使用本地降级: {}", e.message)
            }
        }
        local[key] = entityId to System.currentTimeMillis() + ttlMs
    }

    /** 清除幂等键(指向的实体已不存在时,由调用方判定为陈旧并清理) */
    fun evict(key: String) {
        if (!lockService.isRedisDown()) {
            try {
                redis.delete("idem:$key")
            } catch (e: DataAccessException) {
                log.warn("Redis 删除幂等键失败: {}", e.message)
            }
        }
        local.remove(key)
    }
}
