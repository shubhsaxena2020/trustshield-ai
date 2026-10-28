package ai.trustshield.android.ui.history

import android.content.Context
import ai.trustshield.android.core.model.RiskLabel

/** A privacy-safe history row. It deliberately has no field that can hold source content. */
data class MetadataCheck(
    val checkedAtMillis: Long,
    val verdict: RiskLabel,
    val score: Int?
)

/** SharedPreferences store of timestamp, verdict and score only. */
class MetadataCheckHistory(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun read(): List<MetadataCheck> = decode(preferences.getString(KEY, null))

    fun add(entry: MetadataCheck) {
        preferences.edit().putString(KEY, encode((listOf(entry) + read()).take(MAX_ENTRIES))).apply()
    }

    fun clear() {
        preferences.edit().remove(KEY).apply()
    }

    companion object {
        private const val PREFERENCES = "trustshield_check_history"
        private const val KEY = "metadata"
        private const val MAX_ENTRIES = 30

        internal fun encode(entries: List<MetadataCheck>): String = entries.joinToString("\n") {
            "${it.checkedAtMillis}|${it.verdict.wire}|${it.score?.coerceIn(0, 100) ?: "-"}"
        }

        internal fun decode(raw: String?): List<MetadataCheck> = raw.orEmpty()
            .lineSequence()
            .mapNotNull { line ->
                val fields = line.split('|')
                if (fields.size != 3) return@mapNotNull null
                val timestamp = fields[0].toLongOrNull() ?: return@mapNotNull null
                val verdict = RiskLabel.fromWire(fields[1]) ?: return@mapNotNull null
                val score = if (fields[2] == "-") null else fields[2].toIntOrNull()?.takeIf { it in 0..100 }
                MetadataCheck(timestamp, verdict, score)
            }
            .take(MAX_ENTRIES)
            .toList()
    }
}
