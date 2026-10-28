package ai.trustshield.android.input.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.io.File

class OfflineSpeechEngineTest {
    @Test
    fun `model installer publishes only a complete size-verified directory`() {
        val root = createTempDir(prefix = "vosk-install-test")
        try {
            val target = File(root, "model-hi")
            AtomicModelInstaller.install(target) { temp ->
                File(temp, "am").mkdirs()
                File(temp, "am/final.mdl").writeBytes(byteArrayOf(1, 2, 3))
                File(temp, "conf").writeBytes(byteArrayOf(4, 5))
                5L
            }

            assertTrue(AtomicModelInstaller.isComplete(target))
            assertEquals(3L, File(target, "am/final.mdl").length())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `failed model copy never replaces the complete installed directory`() {
        val root = createTempDir(prefix = "vosk-install-failure-test")
        try {
            val target = File(root, "model-hi")
            AtomicModelInstaller.install(target) { temp ->
                File(temp, "am").mkdirs()
                File(temp, "am/final.mdl").writeBytes(byteArrayOf(1, 2, 3))
                3L
            }

            try {
                AtomicModelInstaller.install(target) { temp ->
                    File(temp, "am").mkdirs()
                    File(temp, "am/final.mdl").writeBytes(byteArrayOf(9))
                    throw IllegalStateException("simulated interrupted asset copy")
                }
            } catch (_: IllegalStateException) { }

            assertTrue(AtomicModelInstaller.isComplete(target))
            assertEquals(3L, File(target, "am/final.mdl").length())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `installer recovers a complete backup left by interrupted publication`() {
        val root = createTempDir(prefix = "vosk-install-recovery-test")
        try {
            val target = File(root, "model-hi")
            AtomicModelInstaller.install(target) { temp ->
                File(temp, "am").mkdirs()
                File(temp, "am/final.mdl").writeBytes(byteArrayOf(1, 2, 3))
                3L
            }
            assertTrue(target.renameTo(File(root, "model-hi.old")))

            try {
                AtomicModelInstaller.install(target) { throw IllegalStateException("retry interrupted") }
            } catch (_: IllegalStateException) { }

            assertTrue(AtomicModelInstaller.isComplete(target))
            assertEquals(3L, File(target, "am/final.mdl").length())
        } finally {
            root.deleteRecursively()
        }
    }

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
    fun `online preferred remains online when reachable and automatic follows reachability`() {
        val automatic = OfflineSpeechPolicy(networkReachable = true)
        automatic.selectOnlinePreferred()
        assertFalse(automatic.useOffline)
        automatic.updateNetworkReachable(false)
        assertTrue(automatic.useOffline)
        automatic.updateNetworkReachable(true)
        assertFalse(automatic.useOffline)

        val offlineOnly = OfflineSpeechPolicy(networkReachable = true, userSelected = true)
        offlineOnly.updateNetworkReachable(true)
        assertTrue(offlineOnly.useOffline)
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
    fun `silence errors do not trigger offline route fallback`() {
        val policy = OfflineSpeechPolicy(networkReachable = true)

        repeat(3) {
            policy.platformError(RecognizerErrorCode.SPEECH_TIMEOUT)
            policy.platformError(RecognizerErrorCode.NO_MATCH)
        }

        assertEquals(0, policy.consecutivePlatformErrors)
        assertFalse(policy.useOffline)
    }

    @Test
    fun `stop does not wait for a decoder that is closing`() {
        val pcm = FakePcmSource(emptyList())
        val closing = CountDownLatch(1)
        val engine = OfflineSpeechEngine(
            pcm = pcm,
            decoderFactory = VoskDecoderFactory {
                object : VoskDecoder {
                    override fun acceptWaveForm(pcm16le: ByteArray, length: Int) = false
                    override fun partialText() = ""
                    override fun finalText() = ""
                    override fun close() { closing.countDown(); Thread.sleep(500) }
                }
            },
            sink = SpeechTextSink { _, _ -> },
        )
        engine.start()
        assertTrue("worker reached microphone read", waitUntilStarted(pcm))

        val before = System.nanoTime()
        engine.stop()
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before)

        assertTrue("decoder close started", closing.await(1, TimeUnit.SECONDS))
        assertTrue("stop blocked for ${elapsedMs}ms", elapsedMs < 200)
    }

    private fun waitUntilStarted(pcm: FakePcmSource): Boolean = pcm.started.await(1, TimeUnit.SECONDS)

    @Test
    fun `fake PCM flows partial and final text then Stop releases decoder and source`() {
        val pcm = FakePcmSource(listOf(byteArrayOf(1, 2), byteArrayOf(3, 4)))
        val closed = AtomicInteger()
        val decoderClosed = CountDownLatch(1)
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
                    override fun close() { closed.incrementAndGet(); decoderClosed.countDown() }
                }
            },
            sink = SpeechTextSink { text, isFinal -> synchronized(results) { results += text to isFinal }; received.countDown() },
            onStarted = { started.incrementAndGet() },
        )

        engine.start()
        assertTrue(received.await(2, TimeUnit.SECONDS))
        engine.stop()
        assertTrue("decoder is closed asynchronously after Stop", decoderClosed.await(2, TimeUnit.SECONDS))

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
        val started = CountDownLatch(1)
        override fun start() { started.countDown() }
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
