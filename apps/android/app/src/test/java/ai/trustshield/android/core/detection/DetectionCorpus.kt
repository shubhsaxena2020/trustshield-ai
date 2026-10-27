package ai.trustshield.android.core.detection

import ai.trustshield.android.core.model.RiskLabel

/**
 * Which side of the fence a [FixtureRow] sits on.
 *
 * [SCAM] rows are scripted scam text and must reach their floor; [CONTROL] rows are legitimate or
 * protective text and must stay at or under their ceiling. Neither kind is an accuracy claim - a
 * fixture pins the behaviour a red run should name, and the score on every row is measured by the
 * real engine, never written down here.
 */
enum class FixtureKind { SCAM, CONTROL }

/**
 * One detection fixture row. Scores are measured, never asserted as an accuracy claim.
 * SCAM rows must reach [minScore]; CONTROL rows must stay at or under [maxScore].
 *
 * @property id stable, human-readable row id (`digital_arrest_hinglish_01`); a red run quotes it.
 * @property category one of the fixed category strings the harness groups on.
 * @property language `hinglish`, `english`, `devanagari` or `mixed`.
 * @property kind [FixtureKind.SCAM] (floor expectation) or [FixtureKind.CONTROL] (ceiling).
 * @property text the raw message exactly as a user would see it; every evidence quote must be an
 *   exact substring of this string.
 * @property minScore SCAM floor. `0` means "no floor pinned on this row".
 * @property maxScore CONTROL ceiling. `100` means "no ceiling pinned on this row".
 * @property expectAsk `true`/`false` when the row pins whether a verified ask must survive quote
 *   verification; `null` when the row does not care.
 * @property note free-text remark carried into the TSV report.
 */
data class FixtureRow(
    val id: String,
    val category: String,
    val language: String,
    val kind: FixtureKind,
    val text: String,
    val minScore: Int = 0,
    val maxScore: Int = 100,
    val expectAsk: Boolean? = null,
    val note: String = "",
)

/**
 * Every fixture row in corpus order: authority, service, money, obfuscation.
 *
 * The four lists are authored as separate files ([CORPUS_AUTHORITY], [CORPUS_SERVICE],
 * [CORPUS_MONEY], [CORPUS_OBFUSCATION]) so they can be written in parallel; this is the only place
 * they are joined, and the order here is the order the report and every failure message use.
 */
internal val ALL_FIXTURES: List<FixtureRow> =
    CORPUS_AUTHORITY + CORPUS_SERVICE + CORPUS_MONEY + CORPUS_OBFUSCATION
