package ai.trustshield.android.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.dp
import ai.trustshield.android.data.preferences.AppLanguage
import ai.trustshield.android.data.preferences.resolveLanguage
import ai.trustshield.android.ui.theme.ToneCopy
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
 * Root navigation shell: owns the back stack, the system Back button and the saveable state. It
 * renders exactly one thing — `content(currentRoute)` — and imposes no chrome of its own, so the
 * real Result / Call Shield / QR Guard / Settings screens keep full control of their layout, app bar
 * and insets.
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
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box {
                        TextButton(onClick = { languageMenuOpen = true }) {
                            Text(languageOptions.first { it.first == preferences.language }
                                .second.forLanguage(selectedLanguage))
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
