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
import java.util.concurrent.atomic.AtomicReference

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

internal enum class OfflineSpeechFailure { MODEL, MICROPHONE, DECODER }

/** Publishes a model directory only after the copy and its byte count have been verified. */
internal object AtomicModelInstaller {
    private const val COMPLETE_MARKER = ".trustshield-model-bytes"

    fun isComplete(target: File): Boolean {
        if (!target.isDirectory || !File(target, "am/final.mdl").let { it.isFile && it.length() > 0L }) return false
        val expected = File(target, COMPLETE_MARKER).takeIf(File::isFile)?.readText()?.toLongOrNull() ?: return false
        return expected > 0L && modelBytes(target) == expected
    }

    fun install(target: File, copyInto: (File) -> Long) {
        val parent = requireNotNull(target.parentFile)
        parent.mkdirs()
        val temporary = File(parent, "${target.name}.tmp")
        val backup = File(parent, "${target.name}.old")
        if (!target.exists() && backup.exists()) {
            if (isComplete(backup)) {
                check(backup.renameTo(target)) { "could not restore existing model backup" }
            } else {
                backup.deleteRecursively()
            }
        }
        temporary.deleteRecursively()
        backup.deleteRecursively()
        check(temporary.mkdirs()) { "could not create temporary model directory" }
        try {
            val expectedBytes = copyInto(temporary)
            val copiedBytes = modelBytes(temporary)
            check(expectedBytes > 0L && copiedBytes == expectedBytes) { "model copy size mismatch" }
            check(File(temporary, "am/final.mdl").let { it.isFile && it.length() > 0L }) { "model acoustic file missing" }
            File(temporary, COMPLETE_MARKER).writeText(expectedBytes.toString())
            if (target.exists()) check(target.renameTo(backup)) { "could not back up existing model" }
            if (!temporary.renameTo(target)) {
                backup.renameTo(target)
                error("could not publish copied model")
            }
            backup.deleteRecursively()
        } catch (failure: Throwable) {
            temporary.deleteRecursively()
            if (!target.exists() && backup.exists()) backup.renameTo(target)
            throw failure
        }
    }

    private fun modelBytes(directory: File): Long =
        directory.walkTopDown().filter { it.isFile && it.name != COMPLETE_MARKER }.sumOf(File::length)
}

/** One microphone reader and one Vosk decoder, owned by a single worker thread. */
internal class OfflineSpeechEngine(
    private val pcm: PcmSource,
    private val decoderFactory: VoskDecoderFactory,
    private val sink: SpeechTextSink,
    private val onFailure: (OfflineSpeechFailure) -> Unit = {},
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
            var stage = OfflineSpeechFailure.MODEL
            try {
                val activeDecoder = decoderFactory.open()
                decoder = activeDecoder
                if (stopped.get()) return@Thread
                stage = OfflineSpeechFailure.MICROPHONE
                pcm.start()
                onStarted()
                stage = OfflineSpeechFailure.DECODER
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
                if (!stopped.get()) onFailure(stage)
            } finally {
                stopPcm()
                runCatching { decoder?.close() }
                decoder = null
            }
        }, "trustshield-vosk").apply { start() }
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        worker = null
        stopPcm()
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
    private val onFailure: (OfflineSpeechFailure) -> Unit,
    private val onReady: () -> Unit,
) : RecognizerHandle {
    @Volatile private var engine: OfflineSpeechEngine? = null

    override fun start() {
        if (engine != null) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onFailure(OfflineSpeechFailure.MICROPHONE); return
        }
        val localModel = File(context.filesDir, "model-hi")
        val active = OfflineSpeechEngine(
            pcm = AndroidPcmSource(),
            decoderFactory = VoskDecoderFactory {
                if (!AtomicModelInstaller.isComplete(localModel)) {
                    AtomicModelInstaller.install(localModel) { temporary ->
                        copyAssets(context.assets, "model-hi", temporary)
                    }
                }
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

    private fun copyAssets(assets: android.content.res.AssetManager, path: String, target: File): Long {
        target.mkdirs()
        return assets.list(path).orEmpty().sumOf { name ->
            val childPath = "$path/$name"
            val child = File(target, name)
            val isDirectory = assets.list(childPath)?.isNotEmpty() == true
            if (isDirectory) copyAssets(assets, childPath, child)
            else assets.open(childPath).use { input ->
                child.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        total += count
                    }
                    total
                }
            }
        }
    }
}

private class AndroidPcmSource : PcmSource {
    private val released = AtomicBoolean(false)
    private val record = AtomicReference<AudioRecord?>()
    override fun start() {
        check(!released.get()) { "Microphone already stopped" }
        val minBytes = AudioRecord.getMinBufferSize(
            16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBytes > 0) { "16 kHz microphone unavailable" }
        for (source in intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
            val candidate = runCatching {
                AudioRecord(source, 16_000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, minBytes.coerceAtLeast(4096))
            }.getOrNull() ?: continue
            val started = runCatching {
                if (candidate.state != AudioRecord.STATE_INITIALIZED) false
                else { candidate.startRecording(); candidate.recordingState == AudioRecord.RECORDSTATE_RECORDING }
            }.getOrDefault(false)
            if (started) {
                record.set(candidate)
                if (released.get()) {
                    record.getAndSet(null)?.release()
                    error("Microphone already stopped")
                }
                return
            }
            runCatching { candidate.release() }
        }
        error("Microphone could not start")
    }
    override fun read(buffer: ByteArray): Int = record.get()?.read(buffer, 0, buffer.size) ?: -1
    override fun stop() {
        if (!released.compareAndSet(false, true)) return
        record.getAndSet(null)?.let { active ->
            runCatching { if (active.recordingState == AudioRecord.RECORDSTATE_RECORDING) active.stop() }
            active.release()
        }
    }
}

/** Automatic route policy is pure Kotlin and covered without Android or Vosk. */
internal class OfflineSpeechPolicy(networkReachable: Boolean, userSelected: Boolean = false) {
    private var networkReachable = networkReachable
    private var offlineOnly = userSelected
    var useOffline: Boolean = !networkReachable || userSelected
        private set
    var consecutivePlatformErrors: Int = 0
        private set

    fun platformError(code: Int): Boolean {
        if (code == RecognizerErrorCode.SPEECH_TIMEOUT || code == RecognizerErrorCode.NO_MATCH) return useOffline
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
        useOffline = offlineOnly || !reachable
    }
    fun selectOffline(selected: Boolean) {
        offlineOnly = selected
        useOffline = !networkReachable || selected
        consecutivePlatformErrors = 0
    }
    fun selectOnlinePreferred() {
        offlineOnly = false
        useOffline = !networkReachable
        consecutivePlatformErrors = 0
    }
}
