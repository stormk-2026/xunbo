package com.stormg.xunbo.core.testing

import com.stormg.xunbo.core.model.RemoteKey
import com.stormg.xunbo.core.navigation.SerialLearning
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningTest {
    @Test fun fragmentedPacketCompletesWithoutAdditionalCommand() =
        runTest {
            val fragments = ArrayDeque(listOf(byteArrayOf(1), byteArrayOf(), byteArrayOf(2, -1)))
            val code =
                SerialLearning.read(RemoteKey.UP) {
                    delay(10)
                    fragments.removeFirst()
                }
            assertEquals(255, code.commandCode)
            assertTrue(fragments.isEmpty())
        }

    @Test fun incompletePacketTimesOutAtFiveSeconds() =
        runTest {
            var timeout = false
            var reads = 0
            try {
                SerialLearning.read(RemoteKey.UP) {
                    delay(100)
                    if (reads++ == 0) byteArrayOf(1) else byteArrayOf()
                }
            } catch (_: TimeoutCancellationException) {
                timeout = true
            }
            assertTrue(timeout)
            assertEquals(5000, testScheduler.currentTime)
        }

    @Test fun cancellationDiscardsFragmentsAndNextSessionStartsEmpty() =
        runTest {
            var returned = false
            val job =
                launch {
                    SerialLearning.read(RemoteKey.UP) {
                        delay(100)
                        byteArrayOf(1)
                    }
                    returned = true
                }
            yield()
            job.cancel()
            job.join()
            assertFalse(returned)
            assertEquals(9, SerialLearning.read(RemoteKey.LEFT) { byteArrayOf(7, 8, 9) }.commandCode)
        }
}
