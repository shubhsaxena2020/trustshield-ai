package ai.trustshield.android.ui.home

import ai.trustshield.android.core.normalize.HinglishNormalizer
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for P3-01 (issue #80): the real scan input screen's state model.
 *
 * No Compose, no Robolectric, no device: the app has **no** Compose UI-test dependency on the JVM
 * unit-test classpath (build files are Codex-owned), so every decision the screen makes lives in the
 * pure model at the top of `ScanScreen.kt` and is asserted here. The composable is only a renderer.
 *
 * Privacy note for reviewers: no test logs, stores or transmits the pasted text. Fixtures are short
 * synthetic strings; the only long ones are repeated filler characters.
 */
class ScanScreenTest {

    private val max = ScanInputRules.MAX_CHARS

    // --------------------------------------------------------------------------------------- helpers

    /** What the text field does on every keystroke/paste. */
    private fun typed(raw: String): ScanInputState =
        reduce(ScanInputState(), ScanInputEvent.TextChanged(raw))

    private fun filler(count: Int): String = "a".repeat(count)

    // ------------------------------------------------------------------------------- empty state

    @Test
    fun initialStateShowsEmptyHintAndDisablesCheck() {
        val state = ScanInputState()

        assertEquals("", state.text)
        assertNull(state.error)
        assertEquals(ScanInputSource.EMPTY, state.source)
        assertEquals(0, state.charCount)
        assertFalse("nothing typed is not analysable", state.isAnalysable)
        assertFalse("check must be disabled on an empty field", state.canCheck)
        assertFalse("no truncation happened", state.truncationNoticeVisible)
        assertFalse("clear is pointless on an empty field", state.canClear)
        assertEquals("0 / 20,000", state.counterLabel)

        // The empty state has real copy, in both languages — not a blank screen.
        assertTrue(ScanCopy.emptyHint.text(false).isNotBlank())
        assertTrue(ScanCopy.emptyHint.text(true).isNotBlank())
    }

    @Test
    fun whitespaceOnlyInputIsNotAnalysable() {
        val state = typed("   \n\t   ")

        assertEquals(
            "typing keeps the space the user just pressed - trimming it would merge the words",
            "   \n\t   ",
            state.text
        )
        assertEquals("3 spaces + newline + tab + 3 spaces", 8, state.charCount)
        assertFalse(state.isAnalysable)
        assertFalse(state.canCheck)
        assertNull("nothing to hand to the analyser", state.checkText())

        assertFalse(ScanInputRules.isAnalysable(""))
        assertFalse(ScanInputRules.isAnalysable("   \n "))
        assertFalse("a zero-width space is not content", ScanInputRules.isAnalysable("\u200B"))
    }

    @Test
    fun emojiOnlyInputIsNotAnalysable() {
        val state = typed("\uD83D\uDE00\uD83D\uDE00")

        assertEquals("two emoji = four UTF-16 chars", 4, state.charCount)
        assertFalse("UI_SPEC: emoji-only input is not analysable", state.isAnalysable)
        assertFalse(state.canCheck)
    }

    // ----------------------------------------------------------------------------------- typing

    @Test
    fun typingEnablesCheckAndCountsCharacters() {
        val message = "SBI: jo OTP aaya hai woh batao"
        val state = typed(message)

        assertEquals(message, state.text)
        assertEquals(message.length, state.charCount)
        assertEquals("${message.length} / 20,000", state.counterLabel)
        assertTrue(state.isAnalysable)
        assertTrue(state.canCheck)
        assertEquals(ScanInputSource.TYPED, state.source)
        assertNull(state.error)
    }

    // ----------------------------------------------------------------------------- char limit

    @Test
    fun overLimitInputIsTruncatedToMaxCharsAndShowsNotice() {
        val state = typed(filler(max + 5_000))

        assertEquals("cut to exactly the normalizer limit", max, state.text.length)
        assertEquals(max, state.charCount)
        assertTrue(state.isOverLimit)
        assertTrue("the truncation notice is visible", state.truncationNoticeVisible)
        assertEquals("20,000 / 20,000", state.counterLabel)
        assertEquals("raw length is kept for honesty about what was dropped", max + 5_000, state.truncatedFromLength)
        assertTrue("a truncated message is still analysable", state.canCheck)
    }

    @Test
    fun truncationNeverSplitsASurrogatePair() {
        // The emoji's two UTF-16 halves straddle the limit: index max-1 is the high surrogate.
        val raw = filler(max - 1) + "\uD83D\uDE42" + "tail"
        val state = typed(raw)

        assertEquals("backed off by one rather than splitting the pair", max - 1, state.text.length)
        assertFalse(
            "a lone high surrogate can never be the last character",
            Character.isHighSurrogate(state.text[state.text.length - 1])
        )
        assertTrue(state.text.none { Character.isSurrogate(it) })
        assertTrue(state.truncationNoticeVisible)
        assertTrue(state.isAnalysable)
    }

    @Test
    fun sanitizeTrimsEdgesBeforeApplyingTheLimit() {
        assertEquals("hello", ScanInputRules.sanitize(" \n hello \t "))
        assertEquals("", ScanInputRules.sanitize(""))
        assertEquals("", ScanInputRules.sanitize("    "))
        assertEquals(max, ScanInputRules.sanitize("  " + filler(max) + "  ").length)
        assertEquals(max, ScanInputRules.sanitize(filler(max)).length)
    }

    @Test
    fun maxCharsMatchesTheNormalizerLimit() {
        assertEquals(20_000, max)
        assertEquals(HinglishNormalizer.MAX_TEXT_CHARS, max)
    }

    // ---------------------------------------------------------------------------------- pasting

    @Test
    fun emptyClipboardRaisesPasteFailedAndTypingClearsIt() {
        val failed = reduce(ScanInputState(), ScanInputEvent.PasteRequested(null))

        assertEquals(ScanInputError.PASTE_FAILED, failed.error)
        assertEquals("nothing was inserted", "", failed.text)
        assertFalse(failed.canCheck)

        val recovered = reduce(failed, ScanInputEvent.TextChanged("aapka OTP batao"))
        assertNull("typing dismisses the error", recovered.error)
        assertTrue(recovered.canCheck)
    }

    @Test
    fun emptyClipboardKeepsAlreadyTypedText() {
        val existing = typed("pehle se likha hua message")
        val failed = reduce(existing, ScanInputEvent.PasteRequested(null))

        assertEquals(existing.text, failed.text)
        assertEquals(ScanInputError.PASTE_FAILED, failed.error)
        assertTrue("the text is still checkable", failed.canCheck)
    }

    @Test
    fun pasteWithTextFillsTheFieldAndMarksTheSourceAsPasted() {
        val pasted = reduce(
            ScanInputState(),
            ScanInputEvent.PasteRequested("   Rs 5,000 credited to A/c XX0123   ")
        )

        assertEquals("Rs 5,000 credited to A/c XX0123", pasted.text)
        assertEquals(ScanInputSource.PASTED, pasted.source)
        assertNull(pasted.error)
        assertTrue(pasted.canCheck)
    }

    @Test
    fun pastingBlankClipboardTextIsNotAPaste() {
        val pasted = reduce(ScanInputState(), ScanInputEvent.PasteRequested("   \n  "))

        assertNull("blank clipboard content is not a paste failure, it is just empty", pasted.error)
        assertEquals("", pasted.text)
        assertEquals(ScanInputSource.EMPTY, pasted.source)
    }

    // ------------------------------------------------------------------------------------ clear

    @Test
    fun clearEmptiesTextAndReDisablesCheck() {
        val cleared = reduce(typed("jo OTP aaya hai batao"), ScanInputEvent.ClearRequested)

        assertEquals("", cleared.text)
        assertEquals(0, cleared.charCount)
        assertEquals(ScanInputSource.EMPTY, cleared.source)
        assertNull(cleared.error)
        assertFalse(cleared.canCheck)
        assertFalse(cleared.isOverLimit)
        assertEquals("0 / 20,000", cleared.counterLabel)
    }

    // ---------------------------------------------------------------------------------- examples

    @Test
    fun exampleChipFillsTheFieldWithThatExactText() {
        val examples = ScanExamples().entries
        assertTrue("3-4 examples, per the package brief", examples.size in 3..4)

        for (example in examples) {
            val state = reduce(ScanInputState(), ScanInputEvent.ExampleSelected(example.text))
            assertEquals(example.text, state.text)
            assertEquals(ScanInputSource.EXAMPLE, state.source)
            assertNull(state.error)
            assertTrue("example '${example.id}' must be checkable", state.canCheck)
        }
    }

    @Test
    fun examplesAreSyntheticShortAndAnalysable() {
        val examples = ScanExamples().entries

        assertEquals(
            "the four required samples, one per scenario",
            listOf("sbi_kyc_otp", "electricity_bill", "bank_credit", "delivery_otp"),
            examples.map { it.id }
        )
        assertEquals("ids are unique", examples.size, examples.map { it.id }.toSet().size)

        for (example in examples) {
            assertTrue("'${example.id}' is short", example.text.length <= 300)
            assertTrue("'${example.id}' is analysable", ScanInputRules.isAnalysable(example.text))
            assertFalse(
                "'${example.id}' must not contain a real-looking OTP/account/number run",
                Regex("[0-9]{6,}").containsMatchIn(example.text)
            )
            assertTrue(example.label.text(false).isNotBlank())
            assertTrue(example.label.text(true).isNotBlank())
        }

        // Clearly labelled as examples, in both languages.
        assertTrue(ScanCopy.examplesHeading.text(false).isNotBlank())
        assertTrue(ScanCopy.examplesHeading.text(true).isNotBlank())
        assertTrue(ScanCopy.examplesNote.text(false).isNotBlank())
    }

    // ------------------------------------------------------------------------------------ check

    @Test
    fun checkOnEmptyInputSetsEmptyInputErrorAndAnalysesNothing() {
        val next = reduce(ScanInputState(), ScanInputEvent.CheckRequested)

        assertEquals(ScanInputError.EMPTY_INPUT, next.error)
        assertEquals("", next.text)
        assertNull("no analysable text is handed to the host", next.checkText())
    }

    @Test
    fun checkOnWhitespaceOnlyTextSetsEmptyInputError() {
        val next = reduce(typed("   \n  "), ScanInputEvent.CheckRequested)

        assertEquals(ScanInputError.EMPTY_INPUT, next.error)
        assertNull(next.checkText())
    }

    @Test
    fun checkOnOverlengthStateRefusesWithTooLongError() {
        // Defensive: a state restored from a bundle could bypass sanitize.
        val forced = ScanInputState(text = filler(max + 1))
        assertEquals(max + 1, forced.charCount)
        assertTrue(forced.isOverLimit)
        assertFalse("never check text that was not sanitized", forced.canCheck)

        val next = reduce(forced, ScanInputEvent.CheckRequested)
        assertEquals(ScanInputError.TOO_LONG_UNTRUNCATED, next.error)
        assertNull(next.checkText())
    }

    @Test
    fun checkOnValidTextClearsErrorAndKeepsText() {
        val withError = typed("jo OTP aaya hai batao").copy(error = ScanInputError.PASTE_FAILED)
        val next = reduce(withError, ScanInputEvent.CheckRequested)

        assertNull(next.error)
        assertEquals(withError.text, next.text)
        assertEquals("the host gets exactly the sanitized text", withError.text, next.checkText())
        assertEquals(ScanInputSource.TYPED, next.source)

        // Typed edge whitespace is kept for the field, but never handed to the analyser.
        val padded = typed("  jo OTP aaya hai batao  ")
        assertEquals("jo OTP aaya hai batao", reduce(padded, ScanInputEvent.CheckRequested).checkText())
    }

    // --------------------------------------------------------------------------- counter label

    @Test
    fun counterLabelFormatsThousands() {
        assertEquals("0 / 20,000", ScanInputRules.counterLabel(0))
        assertEquals("999 / 20,000", ScanInputRules.counterLabel(999))
        assertEquals("1,000 / 20,000", ScanInputRules.counterLabel(1_000))
        assertEquals("19,999 / 20,000", ScanInputRules.counterLabel(19_999))
        assertEquals("20,000 / 20,000", ScanInputRules.counterLabel(20_000))

        assertEquals("0 / 20,000", typed("").counterLabel)
        assertEquals("999 / 20,000", typed(filler(999)).counterLabel)
        assertEquals("1,000 / 20,000", typed(filler(1_000)).counterLabel)

        assertEquals("1,234", ScanInputRules.groupDigits(1_234))
        assertEquals("1,234,567", ScanInputRules.groupDigits(1_234_567))
        assertEquals("0", ScanInputRules.groupDigits(0))
    }

    @Test
    fun counterLabelIgnoresTheDeviceLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY) // would render "1.000" with a locale formatter
            assertEquals("1,000 / 20,000", ScanInputRules.counterLabel(1_000))
            Locale.setDefault(Locale.forLanguageTag("ar-EG")) // Arabic-Indic digits by default
            assertEquals("20,000 / 20,000", ScanInputRules.counterLabel(20_000))
        } finally {
            Locale.setDefault(previous)
        }
    }

    // --------------------------------------------------------------------------------- privacy

    @Test
    fun stateNeverGrowsBeyondTheLimitThroughAnyEvent() {
        val events = listOf(
            ScanInputEvent.TextChanged(filler(max + 100)),
            ScanInputEvent.PasteRequested(filler(max + 100)),
            ScanInputEvent.ExampleSelected(ScanExamples().entries.first().text),
            ScanInputEvent.ClearRequested,
            ScanInputEvent.CheckRequested
        )
        var state = ScanInputState()
        for (event in events) {
            state = reduce(state, event)
            assertTrue("text exceeded the limit after $event", state.text.length <= max)
        }
    }

    // ---------------------------------------------------------------------------- copy guards

    @Test
    fun everyVisibleStringExistsInBothLanguages() {
        val copy = ScanCopy.all()
        assertTrue("copy object must expose all strings", copy.size >= 15)

        for (pair in copy) {
            assertTrue("missing Hinglish in $pair", pair.hinglish.isNotBlank())
            assertTrue("missing English in $pair", pair.english.isNotBlank())
        }

        // Error copy is reachable from the enum, not only from the copy object.
        for (error in ScanInputError.entries) {
            assertTrue(error.copy.hinglish.isNotBlank())
            assertTrue(error.copy.english.isNotBlank())
        }
    }

    @Test
    fun noCopyMakesAnAbsoluteSafetyClaim() {
        val forbidden = listOf(
            "100% safe", "100 % safe", "100 percent safe", "absolutely safe", "completely safe",
            "fully safe", "totally safe", "guaranteed safe", "risk-free", "risk free", "zero risk",
            "bilkul safe", "poori tarah safe", "pura safe"
        )
        val visible = buildList {
            ScanCopy.all().forEach { add(it.hinglish); add(it.english) }
            ScanInputError.entries.forEach { add(it.copy.hinglish); add(it.copy.english) }
            ScanExamples().entries.forEach {
                add(it.text)
                add(it.label.hinglish)
                add(it.label.english)
            }
        }

        for (text in visible) {
            val flat = text.lowercase(Locale.ROOT)
            for (phrase in forbidden) {
                assertFalse("copy claims '$phrase': $text", flat.contains(phrase))
            }
        }
    }

    @Test
    fun reduceIsTotalAndDeterministic() {
        val states = listOf(
            ScanInputState(),
            typed("hi"),
            typed(filler(max + 10)),
            ScanInputState(text = "x", error = ScanInputError.PASTE_FAILED),
            reduce(ScanInputState(), ScanInputEvent.ExampleSelected(ScanExamples().entries[1].text))
        )
        val events = listOf(
            ScanInputEvent.TextChanged(""),
            ScanInputEvent.TextChanged("jo OTP aaya hai batao"),
            ScanInputEvent.PasteRequested(null),
            ScanInputEvent.PasteRequested("pasted text"),
            ScanInputEvent.ClearRequested,
            ScanInputEvent.ExampleSelected(ScanExamples().entries.first().text),
            ScanInputEvent.CheckRequested
        )

        for (state in states) {
            for (event in events) {
                val first = reduce(state, event)
                val second = reduce(state, event)
                assertEquals("reduce is deterministic for $event on $state", first, second)
                assertTrue(
                    "error state must stay a declared value or null",
                    first.error == null || first.error in ScanInputError.entries
                )
            }
        }
    }
}
