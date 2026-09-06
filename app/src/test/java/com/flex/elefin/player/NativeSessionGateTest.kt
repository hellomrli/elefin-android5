package com.flex.elefin.player

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NativeSessionGateTest {
    @Test fun callbacksBeforeCreateAndAfterReleaseNeverReachNative() {
        val gate = NativeSessionGate()
        val view = Any()
        var calls = 0
        assertNull(gate.call(view) { calls++ })
        gate.destroy(view) { fail("No native handle exists") }
        gate.create(view) { calls++ }
        gate.call(view) { calls++ }
        gate.destroy(view) { calls++ }
        assertNull(gate.call(view) { calls++ })
        gate.destroy(view) { fail("Double native destroy") }
        assertEquals(3, calls)
    }

    @Test fun oldTrailerCallbackCannotControlNewPlayer() {
        val gate = NativeSessionGate()
        val old = Any()
        val current = Any()
        gate.create(old) {}
        gate.destroy(old) {}
        gate.create(current) {}
        assertNull(gate.call(old) { fail("Stale audio-add reached new instance") })
        gate.destroy(old) { fail("Old view destroyed the new instance") }
        assertEquals("ready", gate.call(current) { "ready" })
    }

    @Test fun destroyCallbacksDoNotDeadlockAndNoNewInstanceCanStartDuringDestroy() {
        val gate = NativeSessionGate()
        val view = Any()
        gate.create(view) {}
        gate.destroy(view) {
            assertNull(gate.call(view) { fail("Native call during shutdown") })
            try {
                gate.create(Any()) { fail("New handle while native thread is joining") }
                fail("Concurrent creation must be rejected")
            } catch (_: IllegalStateException) { }
        }
        gate.create(Any()) {}
    }

    @Test(timeout = 5000) fun releaseWaitsForInflightPropertyRead() {
        val gate = NativeSessionGate()
        val view = Any()
        val reading = CountDownLatch(1)
        val allowRead = CountDownLatch(1)
        val destroying = CountDownLatch(1)
        val destroyed = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        gate.create(view) {}
        try {
            val read = pool.submit {
                gate.call(view) { reading.countDown(); check(allowRead.await(3, TimeUnit.SECONDS)) }
            }
            assertTrue(reading.await(1, TimeUnit.SECONDS))
            val release = pool.submit {
                destroying.countDown()
                gate.destroy(view) { destroyed.countDown() }
            }
            assertTrue(destroying.await(1, TimeUnit.SECONDS))
            assertFalse(destroyed.await(50, TimeUnit.MILLISECONDS))
            allowRead.countDown()
            read.get(1, TimeUnit.SECONDS)
            release.get(1, TimeUnit.SECONDS)
            assertEquals(0, destroyed.count)
        } finally {
            allowRead.countDown()
            pool.shutdownNow()
        }
    }
}
