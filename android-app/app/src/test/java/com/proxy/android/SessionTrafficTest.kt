package com.proxy.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTrafficTest {
    private val traffic = SessionTraffic()

    @Test fun derivesBothRatesFromElapsedTimeAndIgnoresDuplicateSamples() {
        traffic.start(100)
        traffic.record(500, 80, 90)
        assertTrue(traffic.samplesAt(500).isEmpty())
        traffic.record(2_000, 3_080, 6_090)
        traffic.record(2_000, 90_000, 90_000)
        traffic.record(3_000, 3_080, 7_090)
        assertEquals(listOf(SessionTraffic.Rate(2_000, 2_000, 4_000), SessionTraffic.Rate(3_000, 0, 1_000)),
            traffic.samplesAt(3_000))
    }

    @Test fun missingSamplesCounterRollbacksAndSleepStartFreshBaselines() {
        traffic.start(0)
        traffic.record(0, 0, 0)
        traffic.record(1_000, 1_000, 2_000)
        assertTrue(traffic.samplesAt(6_000).isEmpty())
        traffic.record(6_000, 25_000, 25_000)
        assertTrue(traffic.samplesAt(6_000).isEmpty())
        traffic.record(7_000, 0, 0)
        assertTrue(traffic.samplesAt(7_000).isEmpty())
        traffic.record(8_000, 500, 1_000)
        assertEquals(SessionTraffic.Rate(8_000, 500, 1_000), traffic.samplesAt(8_000).single())
        traffic.record(9_000, -1, -1)
        traffic.record(10_000, 30_000, 40_000)
        assertTrue(traffic.samplesAt(10_000).isEmpty())
        assertEquals(0L, traffic.startedAtMs)
    }

    @Test fun keepsOnlyTheCurrentSessionAndThirtySecondsOfHistory() {
        traffic.start(0)
        for (second in 0..60L) traffic.record(second * 1000, second * 2, second * 3)
        val visible = traffic.samplesAt(60_010)
        assertEquals(30, visible.size)
        assertEquals(31_000L, visible.first().atMs)
        traffic.stop()
        traffic.record(61_000, 3_000, 4_000)
        assertNull(traffic.startedAtMs)
        assertTrue(traffic.samplesAt(61_000).isEmpty())
        traffic.start(62_000)
        traffic.record(63_000, 7, 11)
        assertTrue(traffic.samplesAt(63_000).isEmpty())
        assertEquals(62_000L, traffic.startedAtMs)
    }
}
