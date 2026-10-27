package ai.trustshield.android.input.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class OfflineSpeechEngineTest {
    @Test
    fun `offline route is selected without network or after language pack error`() {
        assertTrue(OfflineSpeechPolicy(networkReachable = false).useOffline)
        val missingPack = OfflineSpeechPolicy(networkReachable = true)
        assertTrue(missingPack.platformError(RecognizerErrorCode.LANGUAGE_UNAVAILABLE))
        assertEquals(0, missingPack.consecutivePlatformErrors)
        missingPack.platformSuccess()
        assertEquals(0, missingPack.consecutivePlatformErrors)
    }

    @Test
    fun `network loss before start automatically selects offline engine`() {
        val policy = OfflineSpeechPolicy(networkReachable = true)
        assertFalse(policy.useOffline)
        policy.updateNetworkReachable(false)
        assertTrue(policy.useOffline)
    }

    @Test
    fun `two platform errors switch to offline and explicit choice is honored`() {
        val policy = OfflineSpeechPolicy(networkReachable = true)
        assertFalse(policy.platformError(RecognizerErrorCode.NETWORK))
        assertTrue(policy.platformError(RecognizerErrorCode.SERVER))
        val selected = OfflineSpeechPolicy(networkReachable = true)
        selected.selectOffline(true)
        assertTrue(selected.useOffline)
    }

    @Test
    fun `fake PCM flows partial and final text then Stop releases decoder and source`() {
        val pcm = FakePcmSource(listOf(byteArrayOf(1, 2), byteArrayOf(3, 4)))
        val closed = AtomicInteger()
        val results = mutableListOf<Pair<String, Boolean>>()
        val received = CountDownLatch(2)
        val started = AtomicInteger()
        val decodeCalls = AtomicInteger()
        val engine = OfflineSpeechEngine(
            pcm = pcm,
            decoderFactory = VoskDecoderFactory {
                object : VoskDecoder {
                    override fun acceptWaveForm(pcm16le: ByteArray, length: Int): Boolean = decodeCalls.incrementAndGet() == 2
                    override fun partialText() = "पुलिस"
                    override fun finalText() = "ओटीपी बताओ"
                    override fun close() { closed.incrementAndGet() }
                }
            },
            sink = SpeechTextSink { text, isFinal -> synchronized(results) { results += text to isFinal }; received.countDown() },
            onStarted = { started.incrementAndGet() },
        )

        engine.start()
        assertTrue(received.await(2, TimeUnit.SECONDS))
        engine.stop()

        assertEquals(listOf("पुलिस" to false, "ओटीपी बताओ" to true), synchronized(results) { results.toList() })
        assertEquals(1, pcm.stops.get())
        assertEquals(1, closed.get())
        assertEquals(1, started.get())
        assertFalse(engine.isRunning)
        assertTrue("PCM is consumed in memory only", pcm.reads.get() >= 2)
    }

    private class FakePcmSource(chunks: List<ByteArray>) : PcmSource {
        private val chunks = ArrayDeque(chunks)
        private val stopped = CountDownLatch(1)
        val stops = AtomicInteger()
        val reads = AtomicInteger()
        override fun start() = Unit
        override fun read(buffer: ByteArray): Int {
            val next = synchronized(chunks) { if (chunks.isEmpty()) null else chunks.removeFirst() }
            if (next == null) { stopped.await(); return -1 }
            next.copyInto(buffer)
            reads.incrementAndGet()
            return next.size
        }
        override fun stop() { stops.incrementAndGet(); stopped.countDown() }
    }
}
