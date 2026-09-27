package com.trialflow

import com.trialflow.infra.IdempotencyService
import com.trialflow.infra.LockConflictException
import com.trialflow.infra.LockService
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * 真实 Redis 集成测试:启动本地 redis-server(随机端口),验证分布式锁互斥与幂等键。
 * 若环境无 redis-server 二进制则跳过。
 */
class RedisLockIntegrationTest {

    companion object {
        private var redisProcess: Process? = null
        private var port: Int = -1
        private lateinit var redis: StringRedisTemplate
        private lateinit var locks: LockService
        private lateinit var idem: IdempotencyService

        @JvmStatic
        @BeforeAll
        fun startRedis() {
            val binary = listOf("/usr/bin/redis-server", "/usr/local/bin/redis-server", "redis-server")
                .firstOrNull {
                    try {
                        Runtime.getRuntime().exec(arrayOf(it, "--version")).waitFor() == 0
                    } catch (e: Exception) {
                        false
                    }
                }
            org.junit.jupiter.api.Assumptions.assumeTrue(binary != null, "无 redis-server,跳过")
            port = ServerSocket(0).use { it.localPort }
            redisProcess = ProcessBuilder(binary, "--port", port.toString(), "--save", "", "--appendonly", "no")
                .redirectErrorStream(true)
                .start()
            // 等待就绪
            val factory = LettuceConnectionFactory("localhost", port)
            factory.afterPropertiesSet()
            var ready = false
            for (i in 1..50) {
                try {
                    factory.connection.use { c -> c.ping() }
                    ready = true
                    break
                } catch (e: Exception) {
                    Thread.sleep(100)
                }
            }
            org.junit.jupiter.api.Assumptions.assumeTrue(ready, "redis-server 未就绪,跳过")
            redis = StringRedisTemplate(factory)
            locks = LockService(redis, true)
            idem = IdempotencyService(redis, locks)
        }

        @JvmStatic
        @AfterAll
        fun stopRedis() {
            redisProcess?.destroy()
        }
    }

    @Test
    fun `Redis 分布式锁互斥,并发临界区串行执行`() {
        val threads = 8
        val counter = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val entered = AtomicInteger(0)
        val latch = CountDownLatch(1)
        val workers = (1..threads).map {
            thread {
                latch.await()
                locks.withLock("test:mutex") {
                    val cur = entered.incrementAndGet()
                    maxConcurrent.updateAndGet { m -> maxOf(m, cur) }
                    Thread.sleep(30)
                    counter.incrementAndGet()
                    entered.decrementAndGet()
                }
            }
        }
        latch.countDown()
        workers.forEach { it.join(10_000) }
        assertEquals(threads, counter.get())
        assertEquals(1, maxConcurrent.get(), "临界区内不应出现并发")
        assertFalse(locks.isRedisDown(), "Redis 可用时不应降级")
    }

    @Test
    fun `锁被持有时同 key 加锁冲突`() {
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val t = thread {
            locks.withLock("test:held", ttlMs = 5_000) {
                held.countDown()
                release.await(3, TimeUnit.SECONDS)
            }
        }
        held.await(2, TimeUnit.SECONDS)
        assertThrows<LockConflictException> { locks.withLock("test:held", ttlMs = 200) { } }
        release.countDown()
        t.join(5_000)
    }

    @Test
    fun `幂等键存取`() {
        assertNull(idem.lookup("k-not-exists"))
        idem.store("k-1", "CT-1001")
        assertEquals("CT-1001", idem.lookup("k-1"))
    }
}
