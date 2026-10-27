package ai.trustshield.android.data.preferences

import ai.trustshield.android.core.payment.maskedUpi
import android.content.Context
import android.content.SharedPreferences
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The registered "expected" payees for QR Guard (B06) and the payment advisory, persisted.
 *
 * ## What is stored
 * Two comma-joined strings, and nothing else ([RegisteredPayeeStoreKeys]):
 *  - [RegisteredPayeeStoreKeys.IDS] — the HMAC-SHA256 hex identifier of every registered payee;
 *  - [RegisteredPayeeStoreKeys.MASKED] — the display form of each of those payees (`sho***@okaxis`),
 *    produced by [maskedUpi], at the same index as its id.
 *
 * There is no key, list or accessor anywhere in this class that can return a registered payee's raw
 * UPI id: [maskedList] is display-only, [matches] answers a boolean and [count] a number. A raw UPI id
 * is only ever held in the argument of [register] / [remove] / [matches] for the duration of the call,
 * and is never placed in a stored value, a returned value, a `toString()` or an exception message —
 * the same discipline as `input/qr/QrWatcher.SaltedPayeeRegistry`.
 *
 * ## Privacy model (binding, `docs/android/ARCHITECTURE.md` "boundaries")
 * Hashing a UPI id is *not* a privacy guarantee on its own: a VPA is low entropy and `handle@bank`
 * guesses are cheap. The guarantee comes from the **salt**:
 *  - [salt] must be at least 16 bytes and must come from a keystore-backed source in production
 *    (Phase 2 gate: `Keystore`/`EncryptedSharedPreferences`-backed secret). This class deliberately
 *    does not invent, derive or default a salt, and there is no constructor without one.
 *  - The key-value backing file is app-private (`MODE_PRIVATE`) but is treated as readable by anyone
 *    with the unlocked device, so a stolen file must not yield a dictionary-matchable payee.
 *  - Nothing user-typed beyond the payee identifier is written: no payee *name*, no amount, no note,
 *    no scan payload. The printed `pn` label is spoofable and is not evidence, so it is not kept.
 *  - Masked strings are stored, not derived from the id, because an HMAC id cannot be reversed to a
 *    display form; they are the only human-readable content in the file and are already masked.
 *
 * ## Normalisation
 * Input is trimmed and lowercased before hashing (same comparison key as `ui/qr/QrUiState`
 * `normalizePayeeId`), so `'Shop@OkAxis '` and `'shop@okaxis'` are one payee, and the stored masked
 * form is normalised too so re-registering the same payee in different case cannot change the display
 * string.
 *
 * ## Failure behaviour
 * Storage is read defensively: a missing, empty, truncated or hand-edited string parses to an empty
 * registry and never throws. Ids are validated as 64-character lowercase hex before being trusted, blank
 * and duplicate ids are skipped, and a masked entry that is missing (a torn write between the two
 * keys) falls back to `***` — the id, which is what [matches] uses, survives. Insertion order is the
 * list order.
 *
 * No `android.*` type is used by this class; the Android adapter is [SharedPreferencesPayeeStore] in
 * this file, which only supplies a [PayeeKeyValueStore] over `SharedPreferences`. The class is `open`
 * for that one subclass only — it has no other extension point and no `protected` surface.
 *
 * @param store the persistence primitive; see [PayeeKeyValueStore] for the contract.
 * @param salt at least 16 bytes, from a keystore-backed source in production. The array is not copied:
 *   as with `SaltedPayeeRegistry`, the caller owns it and must keep it alive and unchanged for the
 *   lifetime of this store.
 */
open class RegisteredPayeeStore(
    private val store: PayeeKeyValueStore,
    salt: ByteArray
) {
    // Own an immutable snapshot: callers must not be able to silently change every lookup key
    // after persisted entries have been written.
    private val salt: ByteArray = salt.copyOf()

    init {
        require(this.salt.size >= MINIMUM_SALT_BYTES) { "salt must be at least 16 bytes" }
    }

    /**
     * Registers [upi] as an expected payee. Idempotent: registering the same payee twice (in any case
     * or surrounding whitespace) leaves exactly one entry.
     *
     * @throws IllegalArgumentException when [upi] is not of the form `local@handle`. The message is a
     *   fixed string and never contains the rejected value.
     */
    fun register(upi: String) {
        val identifier = identifierOf(upi)
        val entries = readEntries()
        if (entries.containsKey(identifier)) return
        entries[identifier] = maskedUpi(normalize(upi))
        persist(entries)
    }

    /** Forgets [upi]. Unknown or non-UPI values are a no-op. */
    fun remove(upi: String) {
        val identifier = identifierOrNull(upi) ?: return
        val entries = readEntries()
        if (!entries.containsKey(identifier)) return
        entries.remove(identifier)
        persist(entries)
    }

    /**
     * True when [upi] hashes to a registered identifier.
     *
     * Lenient by design: a value that cannot be a UPI id is simply not registered, so this returns
     * `false` instead of throwing. The camera path calls this on every decoded payee, and a garbage
     * decode must never crash the watcher (a malformed decode is "no decode", never an exception).
     */
    fun matches(upi: String): Boolean {
        val identifier = identifierOrNull(upi) ?: return false
        return readEntries().containsKey(identifier)
    }

    /** Number of registered payees. */
    fun count(): Int = readEntries().size

    /** True when nothing is registered (QR Guard then shows its Setup state). */
    fun isEmpty(): Boolean = readEntries().isEmpty()

    /**
     * Registered payees in insertion order, masked for display only: `["sho***@okaxis"]`.
     *
     * This is the only human-readable view of the registry. It can never reconstruct a payee: the
     * local part is truncated to three characters by [maskedUpi] and the HMAC id is not exposed.
     */
    fun maskedList(): List<String> = readEntries().values.toList()

    /** Forgets every registered payee and removes both keys from the backing store. */
    fun clear() {
        store.remove(RegisteredPayeeStoreKeys.IDS)
        store.remove(RegisteredPayeeStoreKeys.MASKED)
    }

    // ------------------------------------------------------------------------------- internals ----

    /** Parses both stored strings into id -> masked, insertion-ordered, never throwing. */
    private fun readEntries(): LinkedHashMap<String, String> {
        val identifiers = splitEncoded(store.getString(RegisteredPayeeStoreKeys.IDS))
        val masked = splitEncoded(store.getString(RegisteredPayeeStoreKeys.MASKED))
        val entries = LinkedHashMap<String, String>()
        for ((index, rawIdentifier) in identifiers.withIndex()) {
            val identifier = rawIdentifier.trim()
            if (!isIdentifier(identifier)) continue
            if (entries.containsKey(identifier)) continue
            val display = masked.getOrNull(index)?.trim().orEmpty()
            // Preferences are editable/corruptible input. Never surface an arbitrary stored value as
            // display text: accept only the shape emitted by maskedUpi for a UPI-shaped value.
            entries[identifier] = display.takeIf(::isSafeMaskedDisplay) ?: UNKNOWN_MASKED
        }
        return entries
    }

    private fun persist(entries: Map<String, String>) {
        if (entries.isEmpty()) {
            store.remove(RegisteredPayeeStoreKeys.IDS)
            store.remove(RegisteredPayeeStoreKeys.MASKED)
            return
        }
        store.putString(
            RegisteredPayeeStoreKeys.IDS,
            entries.keys.joinToString(SEPARATOR) { encode(it) }
        )
        store.putString(
            RegisteredPayeeStoreKeys.MASKED,
            entries.values.joinToString(SEPARATOR) { encode(it) }
        )
    }

    private fun identifierOf(upi: String): String {
        val normalized = normalize(upi)
        // Never echo the value in the message: exception text must not carry a raw UPI id.
        require(isUpiShaped(normalized)) { INVALID_UPI_MESSAGE }
        return hmacHex(normalized)
    }

    private fun identifierOrNull(upi: String): String? {
        val normalized = normalize(upi)
        if (!isUpiShaped(normalized)) return null
        return hmacHex(normalized)
    }

    private fun hmacHex(normalizedUpi: String): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(salt, HMAC_ALGORITHM))
        return toHex(mac.doFinal(normalizedUpi.toByteArray(Charsets.UTF_8)))
    }

    private fun toHex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v < 0x10) out.append('0')
            out.append(v.toString(16))
        }
        return out.toString()
    }

    /** Splits an encoded list; `null`, blank and separator-only strings yield an empty list. */
    private fun splitEncoded(raw: String?): List<String> {
        if (raw.isNullOrEmpty()) return emptyList()
        return raw.split(SEPARATOR).map { decode(it) }
    }

    /**
     * A masked display string contains `@`, `*` and whatever the user typed, so `,` (our separator)
     * and `%` (our escape) are percent-escaped on write and restored on read. Ids are hex, for which
     * both are a no-op.
     */
    private fun encode(value: String): String =
        value.replace(ESCAPE, ESCAPED_ESCAPE).replace(SEPARATOR, ESCAPED_SEPARATOR)

    private fun decode(value: String): String =
        value.replace(ESCAPED_SEPARATOR, SEPARATOR).replace(ESCAPED_ESCAPE, ESCAPE)

    companion object {

        /** `docs/android/ARCHITECTURE.md`: a keystore-backed salt, at least 128 bits. */
        const val MINIMUM_SALT_BYTES: Int = 16

        /** Shown when a stored masked entry is missing; still never the raw payee. */
        const val UNKNOWN_MASKED: String = "***"

        private const val HMAC_ALGORITHM = "HmacSHA256"
        private const val IDENTIFIER_HEX_LENGTH = 64 // SHA-256 = 32 bytes = 64 hex chars
        private const val SEPARATOR = ","
        private const val ESCAPE = "%"
        private const val ESCAPED_SEPARATOR = "%2C"
        private const val ESCAPED_ESCAPE = "%25"
        private const val INVALID_UPI_MESSAGE =
            "payee identifier must be a UPI id of the form local@handle"

        /** Comparison key; mirrors `normalizePayeeId` in `ui/qr/QrUiState.kt`. */
        private fun normalize(upi: String): String = upi.trim().lowercase()

        /** Accept one non-empty local part and handle; reject whitespace and extra separators. */
        private fun isUpiShaped(value: String): Boolean {
            val at = value.indexOf('@')
            return at > 0 && at == value.lastIndexOf('@') && at < value.lastIndex &&
                value.none { it.isWhitespace() || it.isISOControl() }
        }

        /** Integrity check for a persisted id; a corrupt string must not count as a payee. */
        private fun isIdentifier(value: String): Boolean =
            value.length == IDENTIFIER_HEX_LENGTH &&
                value.all { it in '0'..'9' || it in 'a'..'f' }

        /** Reject arbitrary preference content, including raw IDs and injected UI text. */
        private fun isSafeMaskedDisplay(value: String): Boolean {
            val at = value.indexOf('@')
            if (at !in 4 until value.lastIndex || value.indexOf('@', at + 1) >= 0) return false
            val prefix = value.substring(0, at)
            val localPrefix = prefix.removeSuffix("***")
            return prefix.endsWith("***") && localPrefix.length in 1..3 &&
                localPrefix.none { it.isWhitespace() || it.isISOControl() } &&
                value.substring(at + 1).isNotBlank() &&
                value.none { it.isWhitespace() || it.isISOControl() }
        }
    }
}

/** The keys this store owns. Closed set: nothing else may be written to the payee file. */
object RegisteredPayeeStoreKeys {

    /** App-private `SharedPreferences` file for the registered payees. */
    const val FILE_NAME: String = "trustshield_registered_payees"

    /** Comma-joined HMAC-SHA256 hex ids, insertion order. */
    const val IDS: String = "payee_ids"

    /** Comma-joined masked display forms, parallel to [IDS]. */
    const val MASKED: String = "payee_masked"
}

/**
 * The minimal key-value primitive [RegisteredPayeeStore] needs. Pure (no `android.*`) so the
 * persistence path can be unit-tested on a plain JVM with an in-memory fake.
 *
 * Contract: [getString] returns `null` for an absent key, [putString] replaces that single key and
 * [remove] makes the key absent again.
 */
interface PayeeKeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}

/**
 * Android adapter: a [RegisteredPayeeStore] over `Context.getSharedPreferences(FILE_NAME,
 * MODE_PRIVATE)`.
 *
 * This class holds no rules and no crypto of its own — every decision (normalisation, hashing,
 * encoding, parsing) lives in [RegisteredPayeeStore]. It exists only so the pure store can be built
 * from a `Context`.
 *
 * The salt is **not** produced here: pass one from a keystore-backed source (Phase 2 gate). Writes use
 * [SharedPreferences.Editor.commit] rather than `apply` because registering a payee is a rare,
 * user-initiated action where the ~1 ms of strictness is cheaper than a store that lies about what it
 * holds (same reasoning as `SharedPreferencesUserPreferencesStore`).
 */
class SharedPreferencesPayeeStore(
    context: Context,
    salt: ByteArray
) : RegisteredPayeeStore(
    SharedPreferencesPayeeKeyValueStore(
        (context.applicationContext ?: context)
            .getSharedPreferences(RegisteredPayeeStoreKeys.FILE_NAME, Context.MODE_PRIVATE)
    ),
    salt
)

/** Single-key adapter over one `SharedPreferences` file. No rules, no crypto, no logging. */
private class SharedPreferencesPayeeKeyValueStore(
    private val prefs: SharedPreferences
) : PayeeKeyValueStore {

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String) {
        check(prefs.edit().putString(key, value).commit()) { WRITE_FAILED_MESSAGE }
    }

    override fun remove(key: String) {
        check(prefs.edit().remove(key).commit()) { WRITE_FAILED_MESSAGE }
    }

    private companion object {
        // Do not include the key or value: either may reveal persistence details/content.
        const val WRITE_FAILED_MESSAGE = "registered payee storage write failed"
    }
}
