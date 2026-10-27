package ai.trustshield.android.input.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Minimal seams for deterministic lifecycle tests; PCM and transcript are never written to disk. */
internal interface PcmSource {
    fun start()
    fun read(buffer: ByteArray): Int
    fun stop()
}

internal interface VoskDecoder {
    fun acceptWaveForm(pcm16le: ByteArray, length: Int): Boolean
    fun partialText(): String
    fun finalText(): String
    fun close()
}

internal fun interface VoskDecoderFactory { fun open(): VoskDecoder }
internal fun interface SpeechTextSink {
    fun onText(text: String, isFinal: Boolean)
}

/** One microphone reader and one Vosk decoder, owned by a single worker thread. */
internal class OfflineSpeechEngine(
    private val pcm: PcmSource,
    private val decoderFactory: VoskDecoderFactory,
    private val sink: SpeechTextSink,
    private val onFailure: () -> Unit = {},
    private val onStarted: () -> Unit = {},
) {
    private val stopped = AtomicBoolean(false)
    private val pcmStopped = AtomicBoolean(false)
    @Volatile private var decoder: VoskDecoder? = null
    @Volatile private var worker: Thread? = null
    val isRunning: Boolean get() = worker?.isAlive == true

    fun start() {
        check(worker == null) { "engine already started" }
        worker = Thread({
            try {
                val activeDecoder = decoderFactory.open()
                decoder = activeDecoder
                pcm.start()
                onStarted()
                val buffer = ByteArray(4096)
                while (!stopped.get()) {
                    val count = pcm.read(buffer)
                    if (stopped.get()) break
                    if (count < 0) throw IllegalStateException("PCM source stopped unexpectedly")
                    if (count == 0) continue
                    if (activeDecoder.acceptWaveForm(buffer, count)) {
                        emit(activeDecoder.finalText(), true)
                    } else {
                        emit(activeDecoder.partialText(), false)
                    }
                }
            } catch (_: Exception) {
                onFailure()
            } finally {
                stopPcm()
                runCatching { decoder?.close() }
                decoder = null
            }
        }, "trustshield-vosk").apply { start() }
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        stopPcm()
        worker?.takeIf { it !== Thread.currentThread() }?.join()
        worker = null
    }

    private fun emit(json: String, isFinal: Boolean) {
        val text = json.trim()
        if (text.isNotEmpty()) sink.onText(text, isFinal)
    }

    private fun stopPcm() { if (pcmStopped.compareAndSet(false, true)) pcm.stop() }
}

/** Bundled Vosk Hindi model; copied from APK assets to app-private files on first use. */
internal class VoskSpeechRecognizerHandle(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onFailure: () -> Unit,
    private val onReady: () -> Unit,
) : RecognizerHandle {
    @Volatile private var engine: OfflineSpeechEngine? = null

    override fun start() {
        if (engine != null) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onFailure(); return
        }
        val localModel = File(context.filesDir, "model-hi")
        val active = OfflineSpeechEngine(
            pcm = AndroidPcmSource(),
            decoderFactory = VoskDecoderFactory {
                if (!File(localModel, "am/final.mdl").isFile) copyAssets(context.assets, "model-hi", localModel)
                val model = Model(localModel.absolutePath)
                val recognizer = Recognizer(model, 16_000.0f)
                object : VoskDecoder {
                    override fun acceptWaveForm(pcm16le: ByteArray, length: Int) = recognizer.acceptWaveForm(pcm16le, length)
                    override fun partialText() = JSONObject(recognizer.partialResult).optString("partial")
                    override fun finalText() = JSONObject(recognizer.result).optString("text")
                    override fun close() { recognizer.close(); model.close() }
                }
            },
            sink = SpeechTextSink { text, isFinal -> if (isFinal) onFinal(text) else onPartial(text) },
            onFailure = onFailure,
            onStarted = onReady,
        )
        engine = active
        active.start()
    }

    override fun destroy() {
        engine?.also { engine = null; it.stop() }
    }

    private fun copyAssets(assets: android.content.res.AssetManager, path: String, target: File) {
        target.mkdirs()
        assets.list(path).orEmpty().forEach { name ->
            val childPath = "$path/$name"
            val child = File(target, name)
            val isDirectory = assets.list(childPath)?.isNotEmpty() == true
            if (isDirectory) copyAssets(assets, childPath, child)
            else assets.open(childPath).use { input -> child.outputStream().use { input.copyTo(it) } }
        }
    }
}

private class AndroidPcmSource : PcmSource {
    private val released = AtomicBoolean(false)
    private val minBytes = AudioRecord.getMinBufferSize(
        16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
    ).coerceAtLeast(4096)
    private val record = AudioRecord(
        MediaRecorder.AudioSource.VOICE_RECOGNITION, 16_000, AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT, minBytes,
    )
    override fun start() {
        check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord unavailable" }
        record.startRecording()
    }
    override fun read(buffer: ByteArray): Int = record.read(buffer, 0, buffer.size)
    override fun stop() {
        if (!released.compareAndSet(false, true)) return
        runCatching { if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop() }
        record.release()
    }
}

/** Automatic route policy is pure Kotlin and covered without Android or Vosk. */
internal class OfflineSpeechPolicy(networkReachable: Boolean, userSelected: Boolean = false) {
    private var networkReachable = networkReachable
    var useOffline: Boolean = !networkReachable || userSelected
        private set
    var consecutivePlatformErrors: Int = 0
        private set

    fun platformError(code: Int): Boolean {
        if (code == RecognizerErrorCode.LANGUAGE_UNAVAILABLE) {
            consecutivePlatformErrors = 0
            useOffline = true
            return true
        }
        consecutivePlatformErrors++
        if (consecutivePlatformErrors >= 2) useOffline = true
        return useOffline
    }

    fun platformSuccess() { consecutivePlatformErrors = 0 }
    fun updateNetworkReachable(reachable: Boolean) {
        networkReachable = reachable
        if (!reachable) useOffline = true
    }
    fun selectOffline(selected: Boolean) { useOffline = !networkReachable || selected; consecutivePlatformErrors = 0 }
}
