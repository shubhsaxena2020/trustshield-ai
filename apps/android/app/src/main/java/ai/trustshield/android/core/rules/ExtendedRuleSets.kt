package ai.trustshield.android.core.rules

/**
 * Stage aggregation layer for the rule modules added in the detection-quality wave.
 *
 * ## Why this file exists
 *
 * [StageRules] owns the five stage lists (`AUTHORITY_RULES`, `THREAT_RULES`, `URGENCY_RULES`,
 * `SECRECY_RULES`, `ASK_RULES`) and the ordering, guards and evidence budget that go with them. The
 * new rule modules — UPI/collect-refund ([UpiPaymentRules]), scam offers and prizes
 * ([ScamOfferRules]) and Devanagari coverage ([DevanagariRules]) — are authored in parallel. If each
 * of them appended itself to `StageRules.kt` directly, every module would edit the same five
 * expressions in the same file and they would conflict on every merge.
 *
 * This file is the single seam instead: each module owns its own file and exposes five
 * `internal val List<Rule>` lists (`<PREFIX>_AUTHORITY_RULES` … `<PREFIX>_ASK_RULES`, with
 * `emptyList()` for a stage it does not touch), and the [StageRules] file concatenates exactly one
 * of the constants below per stage. Adding a module means touching one line here, never the stage
 * lists themselves.
 *
 * ## Contract
 *
 *  * Concatenation only. These constants add rules; they never filter, reorder, rewrite or
 *    de-duplicate an existing rule, so no weight, severity, strength, `skip` or `requires` value can
 *    change by being aggregated.
 *  * Order is stable and documented: UPI, then offer, then Devanagari, for every stage. A rule
 *    module must not depend on its position in the list — [StageRules] collects at most
 *    `MAX_EVIDENCE_PER_STAGE` hits per stage, so the earliest-matching rule wins the quote.
 *  * A stage whose modules are all `emptyList()` still yields a non-empty constant (from another
 *    module); a stage where every module is empty yields `emptyList()` and behaves exactly as
 *    before, which keeps [StageRules] safe if a module ships later.
 */

/** Stage 1 (AUTHORITY) rules contributed by the parallel modules. */
internal val EXTENDED_AUTHORITY_RULES: List<Rule> =
    UPI_AUTHORITY_RULES + OFFER_AUTHORITY_RULES + DEV_AUTHORITY_RULES

/** Stage 2 (THREAT_OR_REWARD) rules contributed by the parallel modules. */
internal val EXTENDED_THREAT_RULES: List<Rule> =
    UPI_THREAT_RULES + OFFER_THREAT_RULES + DEV_THREAT_RULES

/** Stage 3 (URGENCY) rules contributed by the parallel modules. */
internal val EXTENDED_URGENCY_RULES: List<Rule> =
    UPI_URGENCY_RULES + OFFER_URGENCY_RULES + DEV_URGENCY_RULES

/** Stage 4 (SECRECY / isolation) rules contributed by the parallel modules. */
internal val EXTENDED_SECRECY_RULES: List<Rule> =
    UPI_SECRECY_RULES + OFFER_SECRECY_RULES + DEV_SECRECY_RULES

/** Stage 5 (ASK) rules contributed by the parallel modules. */
internal val EXTENDED_ASK_RULES: List<Rule> =
    UPI_ASK_RULES + OFFER_ASK_RULES + DEV_ASK_RULES
