package lk.codegen.risime.calls

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Test

class RingbackToneTest {
    @Test
    fun concurrentUpdatesReleaseEachGeneratorExactlyOnce() {
        repeat(50) {
            val created = AtomicInteger()
            val released = AtomicInteger()
            val doubles = AtomicInteger()
            val rb = RingbackTone {
                created.incrementAndGet()
                object : RingTone {
                    private val done = AtomicInteger()
                    override fun stop() {}
                    override fun release() {
                        released.incrementAndGet()
                        if (done.incrementAndGet() > 1) doubles.incrementAndGet()
                    }
                }
            }
            val n = 32
            val pool = Executors.newFixedThreadPool(n)
            val start = CountDownLatch(1)
            val finished = CountDownLatch(n)
            rb.update(true)
            repeat(n) { i ->
                pool.execute {
                    start.await()
                    rb.update(i % 5 == 0)
                    rb.update(false)
                    finished.countDown()
                }
            }
            start.countDown()
            finished.await()
            pool.shutdown()
            rb.update(false)
            assertEquals(0, doubles.get())
            assertEquals(created.get(), released.get())
        }
    }
}
