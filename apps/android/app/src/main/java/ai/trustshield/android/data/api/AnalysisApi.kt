package ai.trustshield.android.data.api

import ai.trustshield.android.core.model.AnalysisResult
import ai.trustshield.android.core.model.InputType
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException

/**
 * Backend enrichment is **off** unless a caller opts in explicitly.
 *
 * Why this constant exists: the optional Python backend (`/api/v1/analyze` endpoints) keeps analysis
 * results in server memory and, when a user is signed in, writes scan rows to Supabase
 * (`services/history/store.py`). That conflicts with N01 (no raw message persistence) and F09 says
 * Android enrichment stays disabled until the privacy audit (A07) clears both signed-in history and
 * server retention. Shipping with `true` would silently contradict the product's privacy promise,
 * so the default is `false` and the default is asserted in tests.
 */
const val ENRICHMENT_ENABLED_BY_DEFAULT: Boolean = false

/** A raw transport response. [body] is null when the transport had no body to offer. */
data class HttpResult(val code: Int, val body: String?)

/**
 * The only way this package talks to a server.
 *
 * Deliberately a `fun interface` over a single `post` call: there is no `get(url)` here, so nothing
 * in this package can fetch a URL from a payload. The real implementation belongs to A08/Codex
 * (HTTPS + INTERNET permission, which this package must not add to the manifest); tests use fakes.
 *
 * [timeoutMillis] is passed by the caller and enforced by the transport. This package never blocks
 * on a socket itself, so a transport that ignores the timeout is a transport bug, not something the
 * adapter can fix - see [AnalysisApi.timeoutMillis].
 */
fun interface HttpTransport {
    fun post(path: String, body: String, contentType: String, timeoutMillis: Long): HttpResult
}

/**
 * Outcome of an optional enrichment attempt. Exactly one of three things happened, and the UI can
 * render all three without a try/catch:
 *
 *  * [Disabled] - we did not even try (the shipped default);
 *  * [Unavailable] - we tried and failed, with a machine-readable [Unavailable.reason] and whether a
 *    retry could help;
 *  * [Ok] - we have an adapted [AnalysisResult] that crossed the schema boundary safely.
 */
sealed class BackendOutcome {
    /** Enrichment is off. No transport call was made. */
    object Disabled : BackendOutcome()

    /**
     * Enrichment failed. [reason] is one of [REASON_OFFLINE], [REASON_TIMEOUT], [REASON_RATE_LIMITED],
     * [REASON_SERVER_ERROR], [REASON_SCHEMA_DRIFT], [REASON_BAD_BODY], [REASON_BAD_REQUEST].
     * [retryable] is a hint only: nothing here retries on its own.
     */
    data class Unavailable(val reason: String, val retryable: Boolean) : BackendOutcome()

    /** Enrichment succeeded and the payload was adapted to the A01 contract. */
    data class Ok(val result: AnalysisResult) : BackendOutcome()
}

/**
 * Thin, synchronous adapter for the backend's text and URL analysis endpoints.
 *
 * Contract of this class:
 *  * **Expected transport failures become outcomes.** Interruption and cancellation propagate so
 *    callers can stop work; interruption status is restored before it propagates.
 *  * **Never opens a URL.** `analyzeUrl` sends the string as JSON; it never resolves, follows or
 *    previews it. The transport interface has no fetch method at all.
 *  * **Never invents a verdict.** Malformed or drifted payloads are rejected
 *    ([BackendOutcome.Unavailable] with [REASON_SCHEMA_DRIFT]) instead of becoming a low-risk result.
 *  * **Caller-enforced timeout.** [timeoutMillis] is handed to [HttpTransport.post]; the transport
 *    owns enforcement (socket connect/read timeouts). It is validated to be within
 *    `1..10_000` ms in the constructor. A transport that hangs forever is a transport bug.
 *
 * Threading: [analyzeText] and [analyzeUrl] block for up to the transport timeout and must be
 * called off the main thread.
 */
class AnalysisApi(
    private val transport: HttpTransport,
    private val enabled: Boolean = ENRICHMENT_ENABLED_BY_DEFAULT,
    /**
     * Passed verbatim to every transport call. Bounded by [MAX_TIMEOUT_MILLIS] so a caller cannot
     * make an analysis wait indefinitely; must be positive so "no timeout" is not expressible.
     */
    val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
    init {
        require(timeoutMillis in 1..MAX_TIMEOUT_MILLIS) {
            "timeoutMillis must be within 1..$MAX_TIMEOUT_MILLIS ms, was $timeoutMillis"
        }
    }

    /** False by default. When false, no transport call happens at all. */
    val isEnabled: Boolean get() = enabled

    /** Sends `{"text": ...}` to `/api/v1/analyze/text`. The text is never interpreted locally here. */
    fun analyzeText(text: String): BackendOutcome =
        send(PATH_TEXT, body("text", text), InputType.TEXT)

    /**
     * Sends `{"url": ...}` to `/api/v1/analyze/url`.
     *
     * The URL is a *string* in a JSON body. This adapter never fetches it, never follows it and never
     * puts it into the adapted result: opening an attacker-supplied link on device would be an SSRF
     * and privacy problem, and the backend already owns static URL analysis.
     */
    fun analyzeUrl(url: String): BackendOutcome =
        send(PATH_URL, body("url", url), InputType.URL)

    private fun send(path: String, payload: String, expectedInputType: InputType): BackendOutcome {
        // Privacy gate first: disabled means zero network activity, not "call and ignore".
        if (!enabled) return BackendOutcome.Disabled

        val response = try {
            transport.post(path, payload, CONTENT_TYPE_JSON, timeoutMillis)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            // Socket and other expected I/O failures are safe to show as an unavailable enrichment.
            val timeout = isTimeout(error)
            return BackendOutcome.Unavailable(
                if (timeout) REASON_TIMEOUT else REASON_OFFLINE,
                retryable = true,
            )
        } catch (error: TimeoutException) {
            return BackendOutcome.Unavailable(REASON_TIMEOUT, retryable = true)
        }

        return when {
            response.code == 200 -> adapt(response.body, expectedInputType)
            response.code == 429 -> BackendOutcome.Unavailable(REASON_RATE_LIMITED, retryable = true)
            response.code == 408 -> BackendOutcome.Unavailable(REASON_TIMEOUT, retryable = true)
            response.code in 500..599 -> BackendOutcome.Unavailable(REASON_SERVER_ERROR, retryable = true)
            // Any other status (400/401/404/422...) is a request our client got wrong or does not
            // model; retrying the same body would just repeat it.
            else -> BackendOutcome.Unavailable(REASON_BAD_REQUEST, retryable = false)
        }
    }

    private fun adapt(body: String?, expectedInputType: InputType): BackendOutcome {
        if (body == null || body.isBlank()) {
            // No document to adapt. Treated as a transport-level absence, which is what it is.
            return BackendOutcome.Unavailable(REASON_OFFLINE, retryable = true)
        }
        val parsed = MiniJson.parse(body)
            ?: return BackendOutcome.Unavailable(REASON_SCHEMA_DRIFT, retryable = false)
        val objectBody = parsed as? Map<*, *>
            // Valid JSON, but not the object shape we can adapt (e.g. a bare array or number).
            ?: return BackendOutcome.Unavailable(REASON_BAD_BODY, retryable = false)

        val wire = LinkedHashMap<String, Any?>(objectBody.size)
        for ((key, value) in objectBody) {
            if (key is String) wire[key] = value
        }

        return when (val outcome = ResultAdapter.fromWireSafely(wire, expectedInputType)) {
            is ResultAdapter.AdaptOutcome.Ok -> BackendOutcome.Ok(outcome.result)
            is ResultAdapter.AdaptOutcome.Drift ->
                BackendOutcome.Unavailable(REASON_SCHEMA_DRIFT, retryable = false)
        }
    }

    private fun body(field: String, value: String): String =
        "{" + MiniJson.encodeString(field) + ":" + MiniJson.encodeString(value) + "}"

    /**
     * Timeouts surface as whatever the transport throws. We look at the whole cause chain and the
     * class simple name so a fake or a platform-specific timeout type still classifies as TIMEOUT
     * instead of a generic offline error.
     */
    private fun isTimeout(error: Throwable): Boolean {
        var current: Throwable? = error
        var guard = 0
        while (current != null && guard < 8) {
            val name = current.javaClass.simpleName
            if (current is java.net.SocketTimeoutException ||
                current is java.util.concurrent.TimeoutException ||
                current is InterruptedException ||
                name.contains("Timeout", ignoreCase = true)
            ) {
                return true
            }
            current = current.cause
            guard++
        }
        return false
    }

    companion object {
        const val PATH_TEXT: String = "/api/v1/analyze/text"
        const val PATH_URL: String = "/api/v1/analyze/url"
        const val CONTENT_TYPE_JSON: String = "application/json"

        /** Default upper bound for one enrichment attempt. Local analysis never waits on this. */
        const val DEFAULT_TIMEOUT_MILLIS: Long = 4_000L

        /** Hard ceiling. Longer than this and a scan feels hung, so it is not allowed. */
        const val MAX_TIMEOUT_MILLIS: Long = 10_000L

        const val REASON_OFFLINE: String = "OFFLINE"
        const val REASON_TIMEOUT: String = "TIMEOUT"
        const val REASON_RATE_LIMITED: String = "RATE_LIMITED"
        const val REASON_SERVER_ERROR: String = "SERVER_ERROR"
        const val REASON_SCHEMA_DRIFT: String = "SCHEMA_DRIFT"
        const val REASON_BAD_BODY: String = "BAD_BODY"

        /**
         * Non-2xx status outside the mapped set (400/401/404/422...). Documented extension of the
         * required reason list: it says "the request was wrong", which is neither offline nor drift,
         * and it is never retried.
         */
        const val REASON_BAD_REQUEST: String = "BAD_REQUEST"
    }
}
