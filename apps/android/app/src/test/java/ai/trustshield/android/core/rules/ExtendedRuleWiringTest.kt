package ai.trustshield.android.core.rules

import ai.trustshield.android.core.model.StageId
import ai.trustshield.android.core.normalize.HinglishNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Wiring gate for [ExtendedRuleSets] (issue: the detection-quality wave's rule modules).
 *
 * Three parallel modules — UPI/collect ([UpiPaymentRules]), scam offers ([ScamOfferRules]) and
 * Devanagari coverage ([DevanagariRules]) — each expose five `internal val List<Rule>` stage lists.
 * `StageRules.kt` must pick them up through exactly one concatenation per stage, and nothing else
 * may change when it does.
 *
 * ## What this test deliberately does *not* do
 *
 * It never asserts another module's wording, rule count or rule titles. Rule content belongs to the
 * module's own test; this file only proves the **join**:
 *
 *  1. Each `EXTENDED_*` constant is literally its three module lists put together — every rule of
 *     every module is present ([Collection.containsAll]) and the size is exactly the sum, so a
 *     dropped or duplicated module cannot pass.
 *  2. A stage that any module populates produces a non-empty aggregate, and at least one stage is
 *     populated at all (an aggregate of three empty stubs would leave the wave with nothing to
 *     score).
 *  3. An aggregated rule is actually **reachable through [StageRules.detect]** with the real
 *     [HinglishNormalizer] — not merely present in a list. The probe text is checked against the
 *     module's own patterns first ([assumeTrue]), and the assertion then demands that the emitted
 *     stage-5 evidence carries a *module* rule title, so a core rule firing on the same text cannot
 *     be mistaken for the module being wired.
 *
 * The reachability probes are the only wording-sensitive part. If a module renames its phrasing, the
 * probe skips with an explicit reason instead of failing the build — the wiring assertions above stay
 * hard. That split is intentional: a wrong probe proves nothing about the join, but a missing rule
 * does.
 */
class ExtendedRuleWiringTest {

    private val rules = StageRules(HinglishNormalizer())

    /** One stage of the join: the aggregate constant and the three module lists it must equal. */
    private class StagePair(
        val stage: StageId,
        val aggregate: List<Rule>,
        val modules: List<List<Rule>>,
    )

    private fun join(): List<StagePair> = listOf(
        StagePair(
            StageId.AUTHORITY,
            EXTENDED_AUTHORITY_RULES,
            listOf(UPI_AUTHORITY_RULES, OFFER_AUTHORITY_RULES, DEV_AUTHORITY_RULES),
        ),
        StagePair(
            StageId.THREAT_OR_REWARD,
            EXTENDED_THREAT_RULES,
            listOf(UPI_THREAT_RULES, OFFER_THREAT_RULES, DEV_THREAT_RULES),
        ),
        StagePair(
            StageId.URGENCY,
            EXTENDED_URGENCY_RULES,
            listOf(UPI_URGENCY_RULES, OFFER_URGENCY_RULES, DEV_URGENCY_RULES),
        ),
        StagePair(
            StageId.SECRECY,
            EXTENDED_SECRECY_RULES,
            listOf(UPI_SECRECY_RULES, OFFER_SECRECY_RULES, DEV_SECRECY_RULES),
        ),
        StagePair(StageId.ASK, EXTENDED_ASK_RULES, listOf(UPI_ASK_RULES, OFFER_ASK_RULES, DEV_ASK_RULES)),
    )

    // ---- 1. the join is exactly three lists, no additions, no losses ------------------------

    @Test
    fun everyAggregateIsExactlyItsThreeModuleLists() {
        for (pair in join()) {
            for (module in pair.modules) {
                assertTrue(
                    "${pair.stage}: aggregate dropped a rule from a module " +
                        "(aggregate=${pair.aggregate.size}, module=${module.size})",
                    pair.aggregate.containsAll(module),
                )
            }
            assertEquals(
                "${pair.stage}: aggregate size must be the sum of its module sizes",
                pair.modules.sumOf { it.size },
                pair.aggregate.size,
            )
        }
    }

    @Test
    fun aggregatesAreDisjointPerStage() {
        // A copy-paste slip in ExtendedRuleSets.kt (ASK list referenced from the SECRECY constant)
        // would make the same rule fire on the wrong stage. Identity is enough here: the module
        // lists are distinct objects, so a wrong wiring cannot be equal on both sides.
        for (pair in join()) {
            for (other in join()) {
                if (other.stage == pair.stage) continue
                for (module in pair.modules) {
                    for (rule in module) {
                        assertTrue(
                            "${other.stage}: aggregate contains a ${pair.stage} rule (${rule.titleHinglish})",
                            other.aggregate.none { it === rule },
                        )
                    }
                }
            }
        }
    }

    // ---- 2. populated stages stay non-empty ------------------------------------------------

    @Test
    fun populatedStagesProduceNonEmptyAggregates() {
        var populated = 0
        for (pair in join()) {
            if (pair.modules.sumOf { it.size } == 0) continue
            populated++
            assertTrue(
                "${pair.stage}: a module contributed rules but the aggregate is empty",
                pair.aggregate.isNotEmpty(),
            )
        }
        assertTrue(
            "no rule module contributed a single rule: UPI/OFFER/DEV stage lists are all empty",
            populated > 0,
        )
    }

    @Test
    fun aggregateTotalsCoverEveryModuleList() {
        val total = join().sumOf { it.aggregate.size }
        val contributed = join().sumOf { pair -> pair.modules.sumOf { it.size } }
        assertEquals("extended totals must equal everything the modules declare", contributed, total)
    }

    // ---- 3. reachability through the real detector ----------------------------------------

    /**
     * UPI collect-request probe. Verified against `UpiPaymentRules.kt`: it matches pattern 1 of
     * "UPI collect request accept karne ko kaha" (`UPI_COLLECT_NOUN` … `UPI_ACCEPT_VERB` … `UPI_IMPERATIVE`)
     * inside the `UPI_FRAME` precondition, carries no awareness/report skip phrase and no negation, and
     * is matched by **no** pre-existing stage-5 rule — so the hit it produces can only come from the
     * aggregated module list. Handed to the real [StageRules] + [HinglishNormalizer] path.
     */
    private val upiProbe = "UPI request bheji hai 4999 ki, accept karo turant warna request cancel " +
        "ho jayegi"

    /**
     * Scam-offer probe. Verified against `ScamOfferRules.kt`: it matches the job registration-fee ask
     * (`OFFER_AMOUNT` + `OFFER_FEE_DEMAND`/pay verb inside the `OFFER_JOB_FRAME` precondition, no
     * no-fee skip phrase). One base `StageRules` money rule also fires, which still leaves the
     * offer rule a slot inside the per-stage evidence budget.
     */
    private val offerProbe = "Congratulations! Aap work from home job ke liye select ho gaye hain. " +
        "Joining ke liye registration fee Rs 299 isi UPI par bhejo."

    /**
     * Devanagari probe. Verified against `DevanagariRules.kt`: it matches the `कोड` ask
     * (`DEV_CRED_NOUN` + `DEV_TELL_VERB`) and no pre-existing stage-5 rule — base `OTP_WORD` does
     * not carry `कोड`, which is the gap this module exists to close.
     */
    private val devanagariProbe = "आपका सुरक्षा कोड बताओ, वरना खाता बंद हो जाएगा।"

    @Test
    fun upiAskRuleIsReachableThroughTheDetector() {
        assumeTrue("UpiPaymentRules contributed no ask rule yet", UPI_ASK_RULES.isNotEmpty())
        assumeTrue(
            "UPI probe no longer matches any UpiPaymentRules ask pattern (module wording changed)",
            matchesAny(upiProbe, UPI_ASK_RULES),
        )
        assertModuleRuleFired(upiProbe, UPI_ASK_RULES, "UpiPaymentRules")
    }

    @Test
    fun offerAskRuleIsReachableThroughTheDetector() {
        assumeTrue("ScamOfferRules contributed no ask rule yet", OFFER_ASK_RULES.isNotEmpty())
        assumeTrue(
            "offer probe no longer matches any ScamOfferRules ask pattern (module wording changed)",
            matchesAny(offerProbe, OFFER_ASK_RULES),
        )
        assertModuleRuleFired(offerProbe, OFFER_ASK_RULES, "ScamOfferRules")
    }

    @Test
    fun devanagariAskRuleIsReachableThroughTheDetector() {
        assumeTrue("DevanagariRules contributed no ask rule yet", DEV_ASK_RULES.isNotEmpty())
        assumeTrue(
            "Devanagari probe no longer matches any DevanagariRules ask pattern (module wording changed)",
            matchesAny(devanagariProbe, DEV_ASK_RULES),
        )
        assertModuleRuleFired(devanagariProbe, DEV_ASK_RULES, "DevanagariRules")
    }

    /**
     * Stage-5 evidence produced for [text] must include a title declared by [module]. Matching on
     * the *emitted* evidence rather than on "some ask fired" is what makes this a wiring proof: a
     * core [StageRules] ask rule matching the same text would leave the module's own titles absent.
     */
    private fun assertModuleRuleFired(text: String, module: List<Rule>, name: String) {
        val moduleTitles = module.map { it.titleHinglish }.toSet()
        val emitted = rules.detect(text).stages
            .filter { it.stage == StageId.ASK }
            .flatMap { it.evidence }
            .map { it.titleHinglish }
            .toSet()
        assertTrue(
            "$name ask rule is in EXTENDED_ASK_RULES but never reached through StageRules.detect; " +
                "stage-5 titles emitted were $emitted, module titles are $moduleTitles",
            emitted.any { it in moduleTitles },
        )
    }

    /**
     * Same lowercase pass [StageRules] applies before matching, so a probe that would not match even
     * at pattern level skips instead of failing on guard/skip behaviour that is the module's test.
     */
    private fun matchesAny(text: String, module: List<Rule>): Boolean {
        val lower = text.lowercase()
        return module.any { rule -> rule.patterns.any { it.containsMatchIn(lower) } }
    }
}
