package ai.trustshield.android.core.score

import ai.trustshield.android.core.model.Evidence
import ai.trustshield.android.core.model.StageId
import ai.trustshield.android.core.rules.AskType

/**
 * Snapshot of the rolling transcript after one [CallSessionState.appendUtterance].
 *
 * @property text the rolling transcript, oldest utterances dropped first, single-space separated.
 * @property utteranceCount how many utterances are currently retained.
 * @property droppedUtterances how many utterances have left the window since the session started
 *   (never names or content, just a counter, so the UI can say "purana hissa nikal gaya").
 * @property minAsrConfidence lowest ASR confidence among retained utterances (`1.0` when empty).
 * @property atMillis the session clock after this append.
 */
data class TranscriptWindow(
    val text: String,
    val utteranceCount: Int,
    val droppedUtterances: Int,
    val minAsrConfidence: Double,
    val atMillis: Long,
)

/**
 * In-memory conversation / Call Shield state (F05, report §6 — the "2 ghante ki call" requirement).
 *
 * Three jobs, all in RAM only:
 *
 *  1. **Rolling transcript.** [appendUtterance] keeps the newest words up to [maxTranscriptChars].
 *     Oldest utterances are dropped **whole**; a codepoint (surrogate pair) is never split. A single
 *     utterance longer than the whole budget is kept as its tail, cut at a codepoint boundary, because
 *     dropping it entirely would throw away the only thing the user just said.
 *  2. **Persisted stage memory.** [record] stores a stage id plus its quotes and a timestamp. Stages
 *     stay remembered after their words leave the window, which is what makes a stage detected twenty
 *     minutes ago still count on a two-hour call. [detectedStages] / [stageEvidence] only report
 *     stages **inside** [stageTtlMillis] ("live": they still count towards the fusion floor);
 *     [historicalStages] / [historicalQuotes] report everything still remembered, live or stale, for
 *     display ("yeh stage pehle aaya tha").
 *  3. **Bounded quotes.** At most [maxStageQuotes] quotes per stage; the oldest is dropped first, so a
 *     two-hour call cannot grow without limit.
 *
 * Deliberately absent: raw audio, decoded media, disk writes, identity/timestamps of any person,
 * network calls. [reset] releases everything, so nothing survives Stop or process death —
 * `android:allowBackup` and process state must not rehydrate this object (B05 owns that).
 *
 * Not thread-safe: a call session is driven from the Call Shield UI thread / its own scope.
 */
class CallSessionState(
    private val maxTranscriptChars: Int = 4_000,
    private val maxStageQuotes: Int = 3,
    private val stageTtlMillis: Long = DEFAULT_STAGE_TTL_MILLIS,
) {

    init {
        require(maxTranscriptChars > 0) { "maxTranscriptChars must be > 0, was $maxTranscriptChars" }
        require(maxStageQuotes > 0) { "maxStageQuotes must be > 0, was $maxStageQuotes" }
        require(stageTtlMillis > 0) { "stageTtlMillis must be > 0, was $stageTtlMillis" }
    }

    private class Utterance(val text: String, val atMillis: Long, val asrConfidence: Double)

    private val utterances = ArrayDeque<Utterance>()
    private var textChars = 0
    private val quotesByStage = LinkedHashMap<StageId, ArrayDeque<Evidence>>()
    private val stageLastSeen = HashMap<StageId, Long>()
    private var askTypeSet: Set<AskType> = emptySet()
    private var askTypesMillis: Long = Long.MIN_VALUE

    /** Session clock: the largest [appendUtterance]/[record] timestamp seen so far. */
    var nowMillis: Long = 0L
        private set

    /** How many utterances have been dropped from the rolling window since the session started. */
    var droppedUtterances: Int = 0
        private set

    /** Characters currently retained in the rolling transcript (including separators). */
    val retainedChars: Int get() = transcript.length

    /** How many utterances the rolling transcript currently holds. */
    val utteranceCount: Int get() = utterances.size

    /** The rolling transcript, oldest utterances dropped first. */
    val transcript: String
        get() {
            if (utterances.isEmpty()) return ""
            val sb = StringBuilder(textChars + utterances.size)
            utterances.forEachIndexed { index, utterance ->
                if (index > 0) sb.append(' ')
                sb.append(utterance.text)
            }
            return sb.toString()
        }

    /**
     * Append one ASR chunk / spoken line.
     *
     * @param asrConfidence recognizer confidence for this chunk, clamped to `0.0..1.0`. It only ever
     *   lowers output confidence; it never raises the score.
     */
    fun appendUtterance(text: String, atMillis: Long, asrConfidence: Double = 1.0): TranscriptWindow {
        require(atMillis >= 0L) { "atMillis must be >= 0, was $atMillis" }
        nowMillis = maxOf(nowMillis, atMillis)
        if (text.isBlank()) return snapshot()

        val body = if (text.length > maxTranscriptChars) tailAtCodePointBoundary(text) else text
        utterances.addLast(Utterance(body, atMillis, asrConfidence.coerceIn(0.0, 1.0)))
        textChars += body.length
        trimToBudget()
        return snapshot()
    }

    /** Drop oldest utterances, whole, until the joined transcript fits [maxTranscriptChars]. */
    private fun trimToBudget() {
        while (utterances.size > 1 && joinedChars() > maxTranscriptChars) {
            textChars -= utterances.removeFirst().text.length
            droppedUtterances++
        }
    }

    private fun joinedChars(): Int =
        if (utterances.isEmpty()) 0 else textChars + utterances.size - 1

    /** Keep the tail of an over-long utterance without ever splitting a surrogate pair. */
    private fun tailAtCodePointBoundary(text: String): String {
        var start = text.length - maxTranscriptChars
        if (start > 0 && start < text.length && Character.isLowSurrogate(text[start])) start++
        return text.substring(start.coerceIn(0, text.length))
    }

    /**
     * Remember that [stage] was detected now, and keep its quotes (capped at [maxStageQuotes] per
     * stage, oldest dropped). Items without a span are ignored: every remembered quote was verified
     * against the text it came from.
     */
    fun record(stage: StageId, evidence: List<Evidence>, atMillis: Long) {
        record(stage, atMillis)
        val kept = quotesByStage.getOrPut(stage) { ArrayDeque() }
        for (item in evidence) {
            if (item.span == null || item.quote == null) continue
            if (kept.any { it.id == item.id || it.quote == item.quote }) continue
            kept.addLast(item)
            while (kept.size > maxStageQuotes) kept.removeFirst()
        }
    }

    /** Remember that [stage] was detected without carrying a quote (e.g. stage id already stored). */
    fun record(stage: StageId, atMillis: Long) {
        require(atMillis >= 0L) { "atMillis must be >= 0, was $atMillis" }
        nowMillis = maxOf(nowMillis, atMillis)
        val previous = stageLastSeen[stage]
        if (previous == null || atMillis > previous) stageLastSeen[stage] = atMillis
        quotesByStage.getOrPut(stage) { ArrayDeque() }
    }

    /** Remember which kinds of ask were heard, so a critical escalation can survive the words. */
    fun recordAskTypes(types: Set<AskType>, atMillis: Long) {
        if (types.isEmpty()) return
        require(atMillis >= 0L) { "atMillis must be >= 0, was $atMillis" }
        nowMillis = maxOf(nowMillis, atMillis)
        askTypeSet = askTypeSet + types
        askTypesMillis = maxOf(askTypesMillis, atMillis)
    }

    /** Stage ids currently inside the TTL, in playbook order. These still count towards the floor. */
    fun detectedStages(atMillis: Long): List<StageId> =
        StageId.playbookOrder.filter { isLive(it, atMillis) }

    /** Every stage id still remembered, stale or not, in playbook order. Display only. */
    fun historicalStages(): List<StageId> =
        StageId.playbookOrder.filter { quotesByStage.containsKey(it) }

    /** Quotes of live stages, capped per stage, in playbook order. */
    fun stageQuotes(atMillis: Long): List<Evidence> =
        stageEvidence(atMillis).values.flatten()

    /** Quotes of every remembered stage, stale or not. Display only. */
    fun historicalQuotes(): List<Evidence> =
        StageId.playbookOrder.flatMap { quotesByStage[it]?.toList().orEmpty() }

    /** Live stage evidence, capped per stage, in playbook order. */
    fun stageEvidence(atMillis: Long): Map<StageId, List<Evidence>> {
        val out = LinkedHashMap<StageId, List<Evidence>>(quotesByStage.size)
        for (stage in StageId.playbookOrder) {
            if (!isLive(stage, atMillis)) continue
            out[stage] = quotesByStage[stage]?.toList().orEmpty()
        }
        return out
    }

    /** Ask kinds heard inside the TTL. Empty once the ASK stage has expired. */
    fun askTypes(atMillis: Long): Set<AskType> =
        if (askTypesMillis == Long.MIN_VALUE || millisSince(askTypesMillis, atMillis) > stageTtlMillis) {
            emptySet()
        } else {
            askTypeSet
        }

    /**
     * Lowest ASR confidence among retained utterances (`1.0` when empty). One garbled chunk lowers the
     * estimate for the window; nothing here can raise the score.
     */
    fun asrQuality(atMillis: Long): Double =
        utterances.minOfOrNull { it.asrConfidence } ?: 1.0

    /** Stop / process death: everything goes. Nothing survives this call. */
    fun reset() {
        utterances.clear()
        textChars = 0
        droppedUtterances = 0
        quotesByStage.clear()
        stageLastSeen.clear()
        askTypeSet = emptySet()
        askTypesMillis = Long.MIN_VALUE
        nowMillis = 0L
    }

    private fun isLive(stage: StageId, atMillis: Long): Boolean {
        val lastSeen = stageLastSeen[stage] ?: return false
        return millisSince(lastSeen, atMillis) <= stageTtlMillis
    }

    /** Out-of-order timestamps (clock skew) count as "now", never as expired. */
    private fun millisSince(then: Long, now: Long): Long = if (now <= then) 0L else now - then

    private fun snapshot(): TranscriptWindow = TranscriptWindow(
        text = transcript,
        utteranceCount = utterances.size,
        droppedUtterances = droppedUtterances,
        minAsrConfidence = asrQuality(nowMillis),
        atMillis = nowMillis,
    )

    companion object {
        /** Rolling window cap, in characters (~2 minutes of speech). */
        const val DEFAULT_MAX_TRANSCRIPT_CHARS: Int = 4_000

        /** Quotes remembered per stage. */
        const val DEFAULT_MAX_STAGE_QUOTES: Int = 3

        /** 10 minutes: a stage detected earlier still corroborates a later ask within this window. */
        const val DEFAULT_STAGE_TTL_MILLIS: Long = 10L * 60L * 1000L
    }
}
