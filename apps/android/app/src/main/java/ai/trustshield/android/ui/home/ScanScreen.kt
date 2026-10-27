package ai.trustshield.android.ui.home

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ai.trustshield.android.core.normalize.HinglishNormalizer
import ai.trustshield.android.R
import ai.trustshield.android.data.preferences.AppLanguage
import ai.trustshield.android.ui.theme.ToneCopy
import ai.trustshield.android.ui.theme.TsTokens
import ai.trustshield.android.ui.theme.TrustShieldTheme

/**
 * P3-01 (issue #80) — the real Home / Scan input screen.
 *
 * **Split for testability.** The app has no Compose UI-test dependency on the JVM unit-test
 * classpath (build files are Codex-owned), so every decision this screen makes lives in the pure
 * model below — [ScanInputState], [ScanInputEvent], [reduce], [ScanInputRules], [ScanExamples] — and
 * is covered by `ScanScreenTest` on a plain JVM. [ScanScreen] is a thin renderer: it reads state,
 * raises events and never decides anything itself.
 *
 * **Privacy.** The message text lives only in [ScanInputState.text], in memory, for as long as the
 * screen is composed. Nothing here logs, stores, formats into a file or transmits it; there is no
 * network call and no analytics in this file. The clipboard is read **only** when the user presses
 * Paste, through the injected `clipboardText` lambda — never during composition.
 *
 * **Honesty.** The primary action says the verdict is computed on the device without internet
 * (it is: the local deterministic rules). The image action says it is OCR-based and that OCR can
 * misread. The share line is information, not a button that does nothing. There is no absolute-safety
 * claim anywhere (see `ScanScreenTest.noCopyMakesAnAbsoluteSafetyClaim`).
 *
 * `// SEAM:` navigation is P3-06. This screen is **not yet reachable in the running app** — no
 * route, no `HomePlaceholder` replacement and no clipboard wiring exist yet. P3-06 supplies
 * `onCheck` (feeding P3-02's `LocalScanPipeline`), `onPickImage` (P3-05), a `LocalClipboardManager`
 * backed `clipboardText` lambda and `onOpenSettings`. P3-05 also owns the share-entry handoff.
 */

// ---------------------------------------------------------------------------------------------
// Copy — one object, every string twice (Hinglish default, English optional)
// ---------------------------------------------------------------------------------------------

/** Renders a bilingual pair for the current language. Hinglish is the default (`english == false`). */
internal fun ToneCopy.text(english: Boolean): String = if (english) this.english else this.hinglish

private fun ToneCopy.text(language: AppLanguage): String = when (language) {
    AppLanguage.SYSTEM -> forLanguage(AppLanguage.SYSTEM)
    AppLanguage.HINGLISH -> hinglish
    AppLanguage.ENGLISH -> english
    AppLanguage.HINDI -> hindi
}

/**
 * Every user-facing string on this screen, in both languages. Hinglish is the default (HEAD_ORDERS
 * section 2 rule 6), plain language, no marketing tone, and no absolute-safety claim anywhere.
 *
 * `internal` rather than `private` on purpose: `ScanScreenTest` enumerates [all] so a future edit
 * cannot add a one-language string or an unsafe claim without failing the suite.
 */
internal object ScanCopy {

    val title = ToneCopy("Scan karein", "Check a message")
    val subtitle = ToneCopy(
        "Koi bhi suspicious message, link ya SMS text yahan paste karein. Verdict device par hi banta hai.",
        "Paste any suspicious message, link or SMS text here. The verdict is worked out on this device."
    )
    val offlineNotice = ToneCopy(
        "Local rules internet ke bina bhi chalte hain. Online check abhi available nahi hai, " +
            "isliye result sirf device par bane rules se aata hai.",
        "Local rules work without internet. Online checks are not available yet, so the result " +
            "comes only from rules that run on the device."
    )

    val fieldLabel = ToneCopy("Message yahan paste karein", "Paste the message here")
    val fieldPlaceholder = ToneCopy(
        "Poora message jaisa aaya hai waisa hi chipkayein",
        "Paste the whole message exactly as it arrived"
    )
    val counterPrefix = ToneCopy("Characters:", "Characters:")

    val pasteAction = ToneCopy("Paste", "Paste")
    val pasteActionDescription = ToneCopy(
        "Clipboard se message paste karein",
        "Paste a message from the clipboard"
    )
    val clearAction = ToneCopy("Clear", "Clear")
    val clearActionDescription = ToneCopy(
        "Message hata kar field khaali karein",
        "Clear the message field"
    )

    val emptyHint = ToneCopy(
        "Check karne layak text nahi mila. Pehle message paste ya type karein.",
        "There is no text to check yet. Paste or type a message first."
    )
    val truncationNotice = ToneCopy(
        "Message bahut lamba hai - sirf pehle 20,000 characters check honge",
        "The message is too long - only the first 20,000 characters will be checked"
    )

    val examplesHeading = ToneCopy("Examples (nakli sample)", "Examples (synthetic samples)")
    val examplesNote = ToneCopy(
        "Yeh sirf demo ke liye banaye gaye nakli messages hain. Inme kisi ka asli data nahi hai.",
        "These are synthetic demo messages. They contain no real person's data."
    )
    val exampleChipDescription = ToneCopy("Example bharein:", "Fill in the example:")

    val checkAction = ToneCopy("Check karein (offline)", "Check (offline)")
    val checkActionDescription = ToneCopy(
        "Message ko isi device par check karein, internet ke bina",
        "Check the message on this device, without internet"
    )
    val checkOfflineNote = ToneCopy(
        "Verdict isi phone par banta hai — internet ki zarurat nahi.",
        "The verdict is computed on this phone — no internet needed."
    )

    val imageAction = ToneCopy("Screenshot se check karein", "Check from a screenshot")
    val imageActionDescription = ToneCopy(
        "Screenshot chunein; OCR text padh kar wahi local check chalega",
        "Pick a screenshot; OCR reads the text and the same local check runs"
    )
    val imageNote = ToneCopy(
        "Screenshot ka text OCR se nikala jata hai. OCR kabhi galat padh sakta hai, " +
            "isliye bharosa kam ho sakta hai.",
        "Screenshot text is read by OCR. OCR can misread, so confidence may be lower."
    )

    val shareEntryNote = ToneCopy(
        "Kisi bhi app se Share karke bhejein — message yahan khud aa jayega.",
        "Share from any app and the message lands here by itself."
    )
    val settingsAction = ToneCopy("Settings", "Settings")
    val settingsActionDescription = ToneCopy(
        "Bhasha aur sensitivity ki settings kholein",
        "Open the language and sensitivity settings"
    )
    val privacyNote = ToneCopy(
        "Aapka message sirf isi phone par check hota hai. App use save, share ya upload nahi karta.",
        "Your message is checked only on this phone. The app does not save, share or upload it."
    )

    val errorLabel = ToneCopy("Dhyan dein", "Error")
    val errorDismissHint = ToneCopy(
        "Type karte hi yeh error hat jayega.",
        "Typing clears this error."
    )

    // Error copy lives here too, so every visible string is in one place and in both languages.
    val errorEmptyInput = ToneCopy(
        "Khaali message check nahi ho sakta. Pehle text paste ya type karein.",
        "An empty message cannot be checked. Paste or type some text first."
    )
    val errorTooLong = ToneCopy(
        "Message limit se lamba hai. Text chhota karein ya dobara paste karein.",
        "The message is longer than the limit. Shorten it or paste it again."
    )
    val errorPasteFailed = ToneCopy(
        "Clipboard khaali hai ya text copy nahi hua. Message copy karke Paste dobara dabayein.",
        "The clipboard is empty or nothing was copied. Copy the message and tap Paste again."
    )

    /** Every string this screen can show. Used by the copy guards in `ScanScreenTest`. */
    fun all(): List<ToneCopy> = listOf(
        title, subtitle, offlineNotice,
        fieldLabel, fieldPlaceholder, counterPrefix,
        pasteAction, pasteActionDescription, clearAction, clearActionDescription,
        emptyHint, truncationNotice,
        examplesHeading, examplesNote, exampleChipDescription,
        checkAction, checkActionDescription, checkOfflineNote,
        imageAction, imageActionDescription, imageNote,
        shareEntryNote, settingsAction, settingsActionDescription, privacyNote,
        errorLabel, errorDismissHint,
        errorEmptyInput, errorTooLong, errorPasteFailed
    )
}

// ---------------------------------------------------------------------------------------------
// Errors
// ---------------------------------------------------------------------------------------------

/**
 * The three ways this screen can refuse or fail. Each carries its bilingual copy from [ScanCopy]
 * so the enum is the single source of truth for what is shown.
 *
 * [TOO_LONG_UNTRUNCATED] is defensive: the reducer always sanitizes, so it can only appear when a
 * state bypassed the reducer (e.g. restored by a caller). Checking unchecked text would break the
 * "bounded input" guarantee, so it is refused rather than silently cut.
 */
enum class ScanInputError(val copy: ToneCopy) {
    EMPTY_INPUT(ScanCopy.errorEmptyInput),
    TOO_LONG_UNTRUNCATED(ScanCopy.errorTooLong),
    PASTE_FAILED(ScanCopy.errorPasteFailed)
}

// ---------------------------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------------------------

/** Where the current text came from. Presentation only — it never influences the verdict. */
enum class ScanInputSource { EMPTY, TYPED, PASTED, EXAMPLE }

/**
 * Everything [ScanScreen] renders. Immutable; every derived value is a pure computation, so the
 * whole screen can be asserted on a JVM without Compose.
 *
 * Invariants (tested):
 *  - [text] never exceeds [ScanInputRules.MAX_CHARS] when it came through [reduce];
 *  - [truncatedFromLength] is only set when something was actually dropped;
 *  - [error] is cleared by any accepted input change.
 */
data class ScanInputState(
    val text: String = "",
    val error: ScanInputError? = null,
    val source: ScanInputSource = ScanInputSource.EMPTY,
    /** Length of the raw input that had to be cut, or `null` when nothing was dropped. */
    val truncatedFromLength: Int? = null
) {

    /** Characters currently in the field (UTF-16 units, the same units the limit counts). */
    val charCount: Int get() = text.length

    /** True when the input exceeded the limit and was cut. Drives the truncation notice. */
    val isOverLimit: Boolean
        get() = truncatedFromLength != null || charCount > ScanInputRules.MAX_CHARS

    /** False for blank, whitespace-only, emoji-only and punctuation-only text (UI_SPEC). */
    val isAnalysable: Boolean get() = ScanInputRules.isAnalysable(text)

    val truncationNoticeVisible: Boolean get() = isOverLimit

    /** Locale-independent, self-computed grouping: `1,234 / 20,000`. */
    val counterLabel: String get() = ScanInputRules.counterLabel(charCount)

    /** The primary action is only available for bounded, analysable text. */
    val canCheck: Boolean get() = isAnalysable && charCount <= ScanInputRules.MAX_CHARS

    val canClear: Boolean get() = charCount > 0

    /** Why the primary action is unavailable, or `null` when it can run. */
    val checkBlockedReason: ScanInputError? get() = ScanInputRules.checkBlockingError(this)

    /**
     * The exact text the host should analyse for a Check press, or `null` when no check may run.
     * Always sanitized (trimmed, bounded) — the host never has to clean it up.
     */
    fun checkText(): String? =
        if (ScanInputRules.checkBlockingError(this) == null) ScanInputRules.sanitize(text) else null
}

/** Every input the screen can raise. */
sealed interface ScanInputEvent {

    /** The field changed (typing, deleting, IME). [text] is the field's whole new content. */
    data class TextChanged(val text: String) : ScanInputEvent

    /**
     * The Paste button was pressed. [clipboard] is the clipboard's text at that moment, or `null`
     * when the clipboard had nothing usable.
     */
    data class PasteRequested(val clipboard: String?) : ScanInputEvent

    data object ClearRequested : ScanInputEvent

    /** An example chip was tapped; [text] is that example's exact message. */
    data class ExampleSelected(val text: String) : ScanInputEvent

    /** The primary action was pressed. */
    data object CheckRequested : ScanInputEvent
}

// ---------------------------------------------------------------------------------------------
// Rules — pure, total, no I/O
// ---------------------------------------------------------------------------------------------

object ScanInputRules {

    /** Same bound the on-device normalizer accepts, so nothing is checked un-normalized. */
    val MAX_CHARS: Int = HinglishNormalizer.MAX_TEXT_CHARS

    /**
     * Trims the edges and cuts to [MAX_CHARS] **without splitting a surrogate pair**.
     *
     * Used for paste and example input, where edge whitespace is clipboard noise. Typing goes
     * through [bounded] instead: trimming on every keystroke would swallow the space the user just
     * pressed and merge two words.
     */
    fun sanitize(raw: String): String = bounded(raw.trim())

    /** Cuts [raw] to [MAX_CHARS] without trimming and without splitting a surrogate pair. */
    internal fun bounded(raw: String): String {
        if (raw.length <= MAX_CHARS) return raw
        var cut = MAX_CHARS
        if (cut > 0 && Character.isHighSurrogate(raw[cut - 1])) cut -= 1
        return raw.substring(0, cut)
    }

    /**
     * True when there is something a rule could actually match: at least one letter or digit after
     * trimming. Emoji-only and punctuation-only input is not analysable (UI_SPEC lists it with the
     * empty state), and blank text never is.
     */
    fun isAnalysable(raw: String): Boolean = raw.trim().any { Character.isLetterOrDigit(it) }

    /** Why a check must not run on [state], or `null` when it may. */
    fun checkBlockingError(state: ScanInputState): ScanInputError? = when {
        state.charCount > MAX_CHARS -> ScanInputError.TOO_LONG_UNTRUNCATED
        !state.isAnalysable -> ScanInputError.EMPTY_INPUT
        else -> null
    }

    /**
     * Thousands separator computed by hand, never `String.format` / `NumberFormat`: the label must
     * read `1,234` on every device, whatever the system locale (Arabic-Indic digits, `1.234`, ...).
     */
    fun groupDigits(value: Int): String {
        val digits = value.toLong().toString()
        val negative = digits.startsWith("-")
        val body = if (negative) digits.substring(1) else digits
        val out = StringBuilder(body.length + body.length / 3)
        for (index in body.indices) {
            if (index > 0 && (body.length - index) % 3 == 0) out.append(',')
            out.append(body[index])
        }
        return if (negative) "-$out" else out.toString()
    }

    /** `1,234 / 20,000`. */
    fun counterLabel(chars: Int): String = groupDigits(chars) + " / " + groupDigits(MAX_CHARS)
}

// ---------------------------------------------------------------------------------------------
// Reducer — pure and total
// ---------------------------------------------------------------------------------------------

/**
 * Pure reducer: every event, in every state, yields a valid [ScanInputState].
 *
 * Note what it deliberately does **not** do: it never performs the check. `CheckRequested` only
 * records a refusal when the input cannot be analysed; the host reads [ScanInputState.checkText]
 * and runs the deterministic engine (P3-02). No I/O, no clock, no logging, so it is safe to call
 * from any thread and safe to test on a JVM.
 */
fun reduce(state: ScanInputState, event: ScanInputEvent): ScanInputState = when (event) {
    // Typing keeps edge whitespace (see ScanInputRules.sanitize) but is still bounded.
    is ScanInputEvent.TextChanged -> accept(event.text, ScanInputSource.TYPED, trim = false)

    is ScanInputEvent.PasteRequested -> {
        val clipboard = event.clipboard
        if (clipboard == null) {
            // Keep whatever the user already had; a failed paste must not destroy their text.
            state.copy(error = ScanInputError.PASTE_FAILED)
        } else {
            accept(clipboard, ScanInputSource.PASTED, trim = true)
        }
    }

    ScanInputEvent.ClearRequested -> ScanInputState()

    is ScanInputEvent.ExampleSelected -> accept(event.text, ScanInputSource.EXAMPLE, trim = true)

    ScanInputEvent.CheckRequested -> {
        val blocked = ScanInputRules.checkBlockingError(state)
        state.copy(error = blocked)
    }
}

/**
 * Accepts new field content: sanitizes/bounds it, resets the error (typing dismisses it), and
 * records how much was dropped so the notice can be shown honestly.
 */
private fun accept(raw: String, source: ScanInputSource, trim: Boolean): ScanInputState {
    val text = if (trim) ScanInputRules.sanitize(raw) else ScanInputRules.bounded(raw)
    val rawLength = raw.trim().length
    val truncatedFrom = if (rawLength > text.length) rawLength else null
    return ScanInputState(
        text = text,
        error = null,
        source = if (text.isEmpty()) ScanInputSource.EMPTY else source,
        truncatedFromLength = truncatedFrom
    )
}

// ---------------------------------------------------------------------------------------------
// Examples — synthetic, short, clearly labelled
// ---------------------------------------------------------------------------------------------

/** One example chip: a short label for the chip and the exact message it fills in. */
data class ScanExample(val id: String, val label: ToneCopy, val text: String)

/**
 * Three to four short **synthetic** Hinglish samples: one SBI KYC/OTP scam, one electricity-bill
 * threat, one legitimate bank credit alert and one delivery OTP. Written for this screen only:
 * no real phone number, account, OTP, UPI id or person, and no digit run long enough to look like
 * one (asserted in `ScanScreenTest.examplesAreSyntheticShortAndAnalysable`).
 *
 * The legitimate pair matters as much as the scam ones: BEHAVIOR_SPEC section "Negation and
 * legitimate pairs" requires the credit notice and the in-person delivery OTP to be checkable
 * without being called a scam.
 */
data class ScanExamples(val entries: List<ScanExample> = DEFAULT_ENTRIES) {

    companion object {
        val DEFAULT_ENTRIES: List<ScanExample> = listOf(
            ScanExample(
                id = "sbi_kyc_otp",
                label = ToneCopy("SBI KYC", "SBI KYC"),
                text = "SBI: Aapka KYC 24 ghante mein band ho jayega. Turant is link se KYC update " +
                    "karein aur jo OTP aaya hai woh batao."
            ),
            ScanExample(
                id = "electricity_bill",
                label = ToneCopy("Bijli bill", "Power bill"),
                text = "Bijli vibhag: Aaj raat 9 baje aapka connection kat jayega. Turant is " +
                    "personal number par call karke bill clear karein."
            ),
            ScanExample(
                id = "bank_credit",
                label = ToneCopy("Bank credit", "Bank credit"),
                text = "Rs 5,000 credited to A/c XX0123 on 12-Oct. Avl bal Rs 12,450. " +
                    "Yeh OTP kisi ko na batayein."
            ),
            ScanExample(
                id = "delivery_otp",
                label = ToneCopy("Delivery OTP", "Delivery OTP"),
                text = "Aapka parcel aaj shaam aa raha hai. OTP sirf darwaze par khade delivery " +
                    "agent ko dikhayein, phone par kisi ko na batayein."
            )
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Screen — thin renderer
// ---------------------------------------------------------------------------------------------

/**
 * The Home / Scan screen. Renders [state], raises [ScanInputEvent]s through [onEvent], and calls
 * [onCheck] with the sanitized text exactly when the pure model says a check may run. No decision
 * lives here beyond reading state fields.
 *
 * @param english English copy instead of the Hinglish default.
 * @param onCheck receives [ScanInputState.checkText]; the host runs the local pipeline and navigates.
 * @param onPickImage opens image selection (OCR path, owned by P3-05).
 * @param onOpenSettings opens Settings (there is no in-screen preference editing by design).
 * @param clipboardText reads the clipboard **on demand only**. The default returns `null`, so the
 *   screen never touches the clipboard during composition and a preview/test cannot leak it.
 *
 * Accessibility contract: every action is at least 48 dp with visible text plus a content
 * description, headings carry `heading()`, the error is a polite live region, colour is never the
 * only signal, and the whole column scrolls so 320 dp / 200 % font scale cannot clip.
 */
@Composable
fun ScanScreen(
    state: ScanInputState,
    english: Boolean = false,
    language: AppLanguage? = null,
    onEvent: (ScanInputEvent) -> Unit,
    onCheck: (String) -> Unit,
    onPickImage: () -> Unit,
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
    clipboardText: () -> String? = { null }
) {
    val selectedLanguage = language ?: if (english) AppLanguage.ENGLISH else AppLanguage.HINGLISH
    val copy: (ToneCopy) -> String = { it.text(selectedLanguage) }
    val examples = ScanExamples().entries

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = TsTokens.SpacingLg, vertical = TsTokens.SpacingLg),
            verticalArrangement = Arrangement.spacedBy(TsTokens.SpacingMd)
        ) {
            Image(
                painter = painterResource(R.drawable.trustshield_logo),
                contentDescription = copy(ToneCopy("TrustShield AI", "TrustShield AI")),
                modifier = Modifier.width(150.dp).heightIn(min = 36.dp)
            )
            Text(
                text = copy(ScanCopy.title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() }
            )
            Text(
                text = copy(ScanCopy.subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            NoticeLine(text = copy(ScanCopy.offlineNotice))

            // ---- Field ------------------------------------------------------------------
            OutlinedTextField(
                value = state.text,
                onValueChange = { onEvent(ScanInputEvent.TextChanged(it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(copy(ScanCopy.fieldLabel)) },
                placeholder = {
                    Text(
                        text = copy(ScanCopy.fieldPlaceholder),
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                minLines = 6,
                maxLines = 10,
                isError = state.error != null,
                supportingText = {
                    Column(verticalArrangement = Arrangement.spacedBy(TsTokens.SpacingXs)) {
                        Text(
                            text = state.counterLabel,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.semantics {
                                contentDescription =
                                    copy(ScanCopy.counterPrefix) + " " + state.counterLabel
                            }
                        )
                        if (state.truncationNoticeVisible) {
                            Text(
                                text = copy(ScanCopy.truncationNotice),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            )

            // ---- Paste / Clear ----------------------------------------------------------
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(TsTokens.SpacingSm)
            ) {
                OutlinedButton(
                    onClick = { onEvent(ScanInputEvent.PasteRequested(clipboardText())) },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = TsTokens.MinTouchTarget)
                        .semantics { contentDescription = copy(ScanCopy.pasteActionDescription) }
                ) {
                    Text(copy(ScanCopy.pasteAction), style = MaterialTheme.typography.labelLarge)
                }
                OutlinedButton(
                    onClick = { onEvent(ScanInputEvent.ClearRequested) },
                    enabled = state.canClear,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = TsTokens.MinTouchTarget)
                        .semantics { contentDescription = copy(ScanCopy.clearActionDescription) }
                ) {
                    Text(copy(ScanCopy.clearAction), style = MaterialTheme.typography.labelLarge)
                }
            }

            // ---- Empty / error states ---------------------------------------------------
            if (state.charCount == 0 && state.error == null) {
                Text(
                    text = copy(ScanCopy.emptyHint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            state.error?.let { error ->
                ErrorBanner(
                    label = copy(ScanCopy.errorLabel),
                    message = copy(error.copy),
                    dismissHint = copy(ScanCopy.errorDismissHint)
                )
            }

            // ---- Examples ---------------------------------------------------------------
            Text(
                text = copy(ScanCopy.examplesHeading),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier
                    .padding(top = TsTokens.SpacingSm)
                    .semantics { heading() }
            )
            Text(
                text = copy(ScanCopy.examplesNote),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(TsTokens.SpacingSm)
            ) {
                examples.forEach { example ->
                    val label = example.label.text(selectedLanguage)
                    AssistChip(
                        onClick = { onEvent(ScanInputEvent.ExampleSelected(example.text)) },
                        label = {
                            Text(text = label, style = MaterialTheme.typography.labelMedium)
                        },
                        modifier = Modifier
                            .heightIn(min = TsTokens.MinTouchTarget)
                            .semantics {
                                contentDescription =
                                    copy(ScanCopy.exampleChipDescription) + " " + label
                            }
                    )
                }
            }

            // ---- Primary action ---------------------------------------------------------
            Column(verticalArrangement = Arrangement.spacedBy(TsTokens.SpacingXs)) {
                Button(
                    onClick = {
                        // Both are pure reads of the same model: the event keeps the host's state in
                        // sync (error banner) while onCheck gets the sanitized text to analyse.
                        val target = state.checkText()
                        onEvent(ScanInputEvent.CheckRequested)
                        if (target != null) onCheck(target)
                    },
                    enabled = state.canCheck,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = TsTokens.MinTouchTarget)
                        .semantics { contentDescription = copy(ScanCopy.checkActionDescription) }
                ) {
                    Text(copy(ScanCopy.checkAction), style = MaterialTheme.typography.labelLarge)
                }
                Text(
                    text = copy(ScanCopy.checkOfflineNote),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                state.checkBlockedReason?.let { blocked ->
                    Text(
                        text = copy(blocked.copy),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ---- Secondary: image (OCR) -------------------------------------------------
            Column(verticalArrangement = Arrangement.spacedBy(TsTokens.SpacingXs)) {
                OutlinedButton(
                    onClick = onPickImage,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = TsTokens.MinTouchTarget)
                        .semantics { contentDescription = copy(ScanCopy.imageActionDescription) }
                ) {
                    Text(copy(ScanCopy.imageAction), style = MaterialTheme.typography.labelLarge)
                }
                Text(
                    text = copy(ScanCopy.imageNote),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ---- Share entry: information only, no fake button --------------------------
            Text(
                text = copy(ScanCopy.shareEntryNote),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = copy(ScanCopy.privacyNote),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            TextButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .heightIn(min = TsTokens.MinTouchTarget)
                    .semantics { contentDescription = copy(ScanCopy.settingsActionDescription) }
            ) {
                Text(copy(ScanCopy.settingsAction), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * Neutral, non-risk informational line. Deliberately not tinted with a risk tone: "online checks
 * are off" is a capability limit, not a risk signal about the content, and RiskTone is reserved for
 * content risk.
 */
@Composable
private fun NoticeLine(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(TsTokens.CornerSmall),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(
                horizontal = TsTokens.SpacingMd,
                vertical = TsTokens.SpacingSm
            )
        )
    }
}

/**
 * Inline error state. Text label + message + the way out ("typing clears it"), so the state is
 * never colour-only, and a polite live region so TalkBack announces it when it appears.
 */
@Composable
private fun ErrorBanner(label: String, message: String, dismissHint: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(TsTokens.CornerSmall),
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                contentDescription = "$label. $message $dismissHint"
            }
    ) {
        Column(
            modifier = Modifier.padding(TsTokens.SpacingMd),
            verticalArrangement = Arrangement.spacedBy(TsTokens.SpacingXs)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(text = message, style = MaterialTheme.typography.bodyLarge)
            Text(text = dismissHint, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Previews (compile-time only; this screen is not reachable in the app until P3-06 wires it).
// ---------------------------------------------------------------------------------------------

@Preview(name = "Scan — empty, Hinglish", showBackground = true, widthDp = 360, heightDp = 1200)
@Composable
private fun ScanScreenEmptyPreview() {
    TrustShieldTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            ScanScreen(
                state = ScanInputState(),
                onEvent = {},
                onCheck = {},
                onPickImage = {}
            )
        }
    }
}

@Preview(name = "Scan — example, English, dark", showBackground = true, widthDp = 360, heightDp = 1400)
@Composable
private fun ScanScreenFilledPreview() {
    val state = reduce(
        ScanInputState(),
        ScanInputEvent.ExampleSelected(ScanExamples().entries.first().text)
    )
    TrustShieldTheme(darkTheme = true) {
        Surface(color = MaterialTheme.colorScheme.background) {
            ScanScreen(
                state = state,
                english = true,
                onEvent = {},
                onCheck = {},
                onPickImage = {}
            )
        }
    }
}

@Preview(name = "Scan — empty-clipboard error", showBackground = true, widthDp = 360, heightDp = 1200)
@Composable
private fun ScanScreenErrorPreview() {
    val state = reduce(ScanInputState(), ScanInputEvent.PasteRequested(null))
    TrustShieldTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            ScanScreen(
                state = state,
                onEvent = {},
                onCheck = {},
                onPickImage = {}
            )
        }
    }
}
