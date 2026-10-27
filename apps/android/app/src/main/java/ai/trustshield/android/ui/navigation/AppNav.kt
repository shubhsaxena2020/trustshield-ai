package ai.trustshield.android.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import ai.trustshield.android.data.preferences.AppLanguage
import ai.trustshield.android.data.preferences.resolveLanguage
import ai.trustshield.android.ui.theme.ToneCopy
import ai.trustshield.android.ui.theme.TsTokens
import java.util.Locale

/**
 * Navigation verbs handed to screens as plain callbacks.
 *
 * The alternative — `androidx.navigation`'s `NavController` — is **not on the classpath** (Codex owns
 * `app/build.gradle.kts`) and a 2-level stack over five destinations does not need it. Screens call
 * these lambdas; they never learn about [NavBackStack], so a later swap to Navigation-Compose would
 * only touch this file.
 */
@Immutable
data class NavActions(
    /** Push a destination (single-top: pushing the current route is a no-op). */
    val navigate: (AppRoute) -> Unit,
    /** Swap the current destination without growing the stack. */
    val replace: (AppRoute) -> Unit,
    /** Pop one entry. Returns `false` at the root — the caller should let the app exit. */
    val back: () -> Boolean
)

/**
 * Screen-side handle on [NavActions]: `LocalNavActions.current.navigate(AppRoute.RESULT)`.
 *
 * The default is inert rather than throwing, so a `@Preview` (or a screen rendered on its own) shows
 * a static frame instead of crashing; [AppNavHost] provides the real actions below itself.
 */
val LocalNavActions: ProvidableCompositionLocal<NavActions> = staticCompositionLocalOf {
    NavActions(
        navigate = {},
        replace = {},
        back = { false }
    )
}

/**
 * `rememberSaveable` codec for [NavBackStack]: the stack travels as `ArrayList<String>` of
 * `wireName`s (Bundle-saveable), and a stack restored from an older build degrades to Home instead
 * of crashing.
 */
val NavBackStackSaver: Saver<NavBackStack, Any> = listSaver(
    save = { stack -> stack.serialise() },
    restore = { wire -> NavBackStack.restore(wire) }
)

/**
 * App-chrome copy: the strings the bar owns that no screen owns.
 *
 * Kept as [ToneCopy] values so the bar switches language with the rest of the UI in the same frame
 * (HEAD_DIRECTIVE_PHASE6 §U1). Devanagari falls back to the reviewed table in `HindiCopy`; nothing
 * here may be a bare English literal.
 */
internal object AppChromeCopy {

    /**
     * The bar's title.
     *
     * Deliberately the *app* name and not the destination name. Each destination body already draws
     * its own title (ScanScreen, ResultScreen's result top bar, CallShieldScreen, QrGuardScreen,
     * SettingsScreen), so repeating the destination here would put two headings for the same place
     * on every screen. UI_SPEC / the C-lane contract: one heading per screen.
     */
    val barTitle: ToneCopy = ToneCopy("TrustShield", "TrustShield")

    /** Used only if the option list ever stops covering the resolved language. */
    val fallbackLanguageName: ToneCopy = ToneCopy("Hinglish", "Hinglish", "हिंग्लिश")

    /** Visible label of the chrome's up affordance; the app's existing "Peeche" vocabulary. */
    val back: ToneCopy = ToneCopy("Peeche", "Back", "पीछे")

    /**
     * Announced when Back goes one screen up.
     *
     * The label says what Back *does* — which screen it ends — because "Peeche" on its own does not
     * tell a screen-reader user whether they are closing a session or moving up a screen
     * (UI_SPEC "Back and Stop always end a session").
     */
    val backToPreviousScreen: ToneCopy = ToneCopy(
        hinglish = "Peeche. Pichli screen par le jaata hai.",
        english = "Back. Returns to the previous screen."
    )

    /** Announced on Call Shield, where leaving the screen ends the live session. */
    val backEndsSession: ToneCopy = ToneCopy(
        hinglish = "Peeche. Call Shield session band ho jayega.",
        english = "Back. Ends the Call Shield session."
    )

    /**
     * Announced on the language button: the label the user can see, which language is showing, and
     * that tapping changes it.
     *
     * The visible text comes first so voice control can still reach the button by the words printed
     * on it ("Phone ki bhasha" / "Hinglish") while TalkBack gets the extra context. The visible label
     * stays short so the bar still fits 320 dp at 200 % text.
     */
    fun languageButton(visible: String, current: String): ToneCopy = ToneCopy(
        hinglish = "$visible. Bhasha: $current. Badalne ke liye tap karein.",
        english = "$visible. Language: $current. Tap to change."
    )
}

/**
 * Root navigation shell: owns the back stack, the system Back button and the saveable state.
 *
 * It draws exactly two things: a one-row app bar (the app title, and the language button that
 * `HEAD_DIRECTIVE_PHASE6 §U1` requires on every main screen), then `content(currentRoute)`. It draws
 * no destination title, so the real Result / Call Shield / QR Guard / Settings screens keep full
 * control of their layout, their own headings and their insets.
 *
 * Accessibility contract of the bar (UI_SPEC "Polish and accessibility acceptance", QUALITY_GATES
 * gate 6):
 *  * the title is a `heading()` and lives in its own node, so it is one heading and never swallows
 *    the language button's label into the same focus stop;
 *  * the language button is at least [TsTokens.MinTouchTarget] both before and after the labels
 *    wrap, and announces the current language (`contentDescription`) plus the current language as
 *    its state (`stateDescription`) — not just the two visible words;
 *  * the bar has a *minimum* height and no fixed width anywhere: at 320 dp and 200 % font scale the
 *    labels wrap and the bar grows, so no chrome text is clipped (landscape included).
 *
 * @param modifier applied to the host container (test tags, window insets on the host side).
 * @param initial stack to use when there is no saved state (see [NavBackStack.fromIntentEntry]).
 * @param entry destination requested by the launching/re-entry intent, if any. Applied once per
 *   distinct value and only when it is not already the visible route, so process recreation cannot
 *   discard a restored back stack.
 * @param content the screen for a route. Kept as a plain lambda so no screen package is imported
 *   here and `MainActivity` stays the only place that maps routes to screens.
 */
@Composable
fun AppNavHost(
    modifier: Modifier = Modifier,
    initial: NavBackStack = NavBackStack.Home,
    entry: AppRoute? = null,
    entrySequence: Int = 0,
    content: @Composable (AppRoute) -> Unit
) {
    val state: MutableState<NavBackStack> =
        rememberSaveable(stateSaver = NavBackStackSaver) { mutableStateOf(initial) }
    val lastAppliedEntrySequence = rememberSaveable { mutableStateOf(Int.MIN_VALUE) }

    // A saved marker prevents the activity's original launch intent from replacing a restored stack.
    // A monotonically increasing request sequence still lets onNewIntent handle repeated same-route intents.
    LaunchedEffect(entry, entrySequence) {
        if (shouldApplyIntentEntry(entry, entrySequence, lastAppliedEntrySequence.value)) {
            if (state.value.current != entry) {
                state.value = NavBackStack.fromIntentEntry(entry?.wireName)
            }
            lastAppliedEntrySequence.value = entrySequence
        }
    }

    val actions = remember {
        NavActions(
            navigate = { route -> state.value = state.value.push(route) },
            replace = { route -> state.value = state.value.replace(route) },
            back = {
                val popped = state.value.pop()
                if (popped == null) {
                    false
                } else {
                    state.value = popped
                    true
                }
            }
        )
    }

    // Enabled only when there is somewhere to go back to: at the root the event falls through to
    // the system and the activity finishes, which is the documented "back from root exits" rule.
    BackHandler(enabled = state.value.canPop) {
        state.value.pop()?.let { state.value = it }
    }

    CompositionLocalProvider(LocalNavActions provides actions) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .safeDrawingPadding()
        ) {
            Column(Modifier.fillMaxSize()) {
                var languageMenuOpen by remember { mutableStateOf(false) }
                val preferences = LocalUserPreferences.current
                val preferencesSink = LocalPreferencesSink.current
                val selectedLanguage = resolveLanguage(
                    preferences.language,
                    Locale.getDefault().toLanguageTag()
                )
                val languageOptions = listOf(
                    AppLanguage.SYSTEM to ToneCopy("Phone ki bhasha", "Use system language"),
                    AppLanguage.HINGLISH to ToneCopy("Hinglish", "Hinglish", "हिंग्लिश"),
                    AppLanguage.ENGLISH to ToneCopy("English", "English", "अंग्रेज़ी"),
                    AppLanguage.HINDI to ToneCopy("Hindi", "Hindi", "हिन्दी")
                )
                // What TalkBack says is showing right now: the resolved language, not the stored
                // choice, because "Phone ki bhasha" alone does not tell a screen-reader user which
                // language the copy in front of them is in. The *visible* label stays the stored
                // choice (unchanged behaviour, and it keeps the bar narrow).
                val currentLanguageOption =
                    languageOptions.firstOrNull { (language, _) -> language == selectedLanguage }?.second
                        ?: AppChromeCopy.fallbackLanguageName
                val spokenLanguage = currentLanguageOption.forLanguage(selectedLanguage)
                // The button still shows the *stored* choice ("Phone ki bhasha" for SYSTEM); the
                // announcement carries both that label and the language it currently resolves to.
                val visibleLanguageLabel =
                    languageOptions.first { it.first == preferences.language }
                        .second.forLanguage(selectedLanguage)
                val currentRoute = state.value.current
                // Announced label for the up affordance: it names what Back ends (a session on Call
                // Shield, a screen elsewhere) instead of only the word "Peeche".
                val backAnnouncement =
                    if (currentRoute == AppRoute.CALL_SHIELD) {
                        AppChromeCopy.backEndsSession
                    } else {
                        AppChromeCopy.backToPreviousScreen
                    }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // A minimum, never a fixed height: at 320 dp / 200 % the two labels wrap and
                        // the bar grows instead of clipping them (UI_SPEC "Polish and accessibility
                        // acceptance"; QUALITY_GATES gate 6).
                        .heightIn(min = TsTokens.MinTouchTarget)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (state.value.canPop && chromeOwnsUpAffordance(currentRoute)) {
                        TextButton(
                            onClick = { actions.back() },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier
                                .heightIn(min = TsTokens.MinTouchTarget)
                                .semantics {
                                    contentDescription = backAnnouncement.forLanguage(selectedLanguage)
                                }
                        ) {
                            Text(AppChromeCopy.back.forLanguage(selectedLanguage))
                        }
                    }
                    Text(
                        text = AppChromeCopy.barTitle.forLanguage(selectedLanguage),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        // Its own layout node with its own heading(), separated from the language
                        // button below, so the two are never merged into one focus stop and the
                        // language label is never read out a second time inside the title's node.
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp)
                            .semantics { heading() }
                    )
                    // Weighted with `fill = false`: the button is capped at its share of the row and
                    // wraps its label there, so at 320 dp it can never push the title to zero width.
                    Box(Modifier.weight(0.8f, fill = false)) {
                        TextButton(
                            onClick = { languageMenuOpen = true },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier
                                // Explicit 48 dp: the M3 minimum-interactive-size rule enlarges the
                                // touch target only, not the laid-out button, and in landscape the
                                // row is short enough that the drawn control must carry it.
                                .heightIn(min = TsTokens.MinTouchTarget)
                                .semantics {
                                    contentDescription = AppChromeCopy
                                        .languageButton(visibleLanguageLabel, spokenLanguage)
                                        .forLanguage(selectedLanguage)
                                    stateDescription = spokenLanguage
                                }
                        ) {
                            Text(visibleLanguageLabel)
                        }
                        DropdownMenu(
                            expanded = languageMenuOpen,
                            onDismissRequest = { languageMenuOpen = false }
                        ) {
                            languageOptions.forEach { (language, label) ->
                                DropdownMenuItem(
                                    text = { Text(label.forLanguage(selectedLanguage)) },
                                    onClick = {
                                        preferencesSink(preferences.copy(language = language))
                                        languageMenuOpen = false
                                    },
                                    // Tells TalkBack which option is in force; four unmarked rows
                                    // otherwise read identically.
                                    modifier = Modifier.semantics {
                                        selected = language == preferences.language
                                    }
                                )
                            }
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    content(state.value.current)
                }
            }
        }
    }
}

/**
 * Whether the app bar itself draws the up affordance for [route].
 *
 * Result is the one destination that already draws its own back control inside its content
 * (`ResultScreen.kt`, `ResultTopBar`, wired by [ResultRouteHost]), so a second one in the bar would
 * be the same action twice for a TalkBack user. Home is the root: back there leaves the app and
 * [NavActions.back] has nothing to pop, so the bar must not offer it.
 */
internal fun chromeOwnsUpAffordance(route: AppRoute): Boolean = when (route) {
    AppRoute.HOME, AppRoute.RESULT -> false
    AppRoute.CALL_SHIELD, AppRoute.QR_GUARD, AppRoute.SETTINGS -> true
}
