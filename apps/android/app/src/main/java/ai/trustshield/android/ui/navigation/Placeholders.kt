package ai.trustshield.android.ui.navigation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ai.trustshield.android.ui.theme.TsTokens
import ai.trustshield.android.ui.theme.TrustShieldTheme

/**
 * Hinglish/English copy pair. Product rule: **Hinglish is the default**, English is the option, so
 * every string exists twice and neither variant is a translation of a placeholder tone.
 *
 * `res/values/strings.xml` is off-limits (eight parallel packages would collide on it), so copy lives
 * in Kotlin objects like this one — the same pattern C04's Settings screen will use.
 */
data class NavCopy(val hinglish: String, val english: String) {
    fun text(english: Boolean): String = if (english) this.english else hinglish
}

/**
 * Copy for the shell. Deliberately plain-language Hinglish, no marketing tone, and no claim about a
 * feature that is not built — these screens say "not built yet" instead of pretending.
 */
internal object NavStrings {
    val placeholderStatus = NavCopy(
        hinglish = "Yeh screen abhi ban rahi hai. Neeche sirf plan likha hai — koi asli scan yahan nahi chalta.",
        english = "This screen is not built yet. What you see is the plan only — no real scan runs here."
    )
    val destinationsHeading = NavCopy(
        hinglish = "Baaki screens",
        english = "Other screens"
    )
}

/**
 * One screen of the shell. Renders a status strip, a heading and body copy at a fixed max width so a
 * tablet keeps a readable column (UI_SPEC: centred max-width content column), inside a vertical
 * scroll so nothing is clipped at 320 dp or 200 % font scale.
 */
@Composable
internal fun PlaceholderScreen(
    title: NavCopy,
    body: NavCopy,
    status: NavCopy,
    modifier: Modifier = Modifier,
    english: Boolean = false,
    actions: @Composable () -> Unit = {}
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .widthIn(max = 560.dp)
            .padding(horizontal = TsTokens.SpacingLg, vertical = TsTokens.SpacingLg),
        verticalArrangement = Arrangement.spacedBy(TsTokens.SpacingMd)
    ) {
        // Neutral container, never a risk tone: RiskTone.AMBER means "caution about the content under
        // analysis", and a missing screen is not that. Risk hues must stay reserved for risk.
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = RoundedCornerShape(TsTokens.CornerSmall)
        ) {
            Text(
                text = status.text(english),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(
                    horizontal = TsTokens.SpacingMd,
                    vertical = TsTokens.SpacingSm
                )
            )
        }

        Text(
            text = title.text(english),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() }
        )

        Text(
            text = body.text(english),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        actions()
    }
}

/**
 * Full-width row used as a navigation affordance on the Home placeholder. 48 dp minimum target, a
 * merged semantics node with `Role.Button`, and a text arrow so the affordance is never colour-only.
 */
@Composable
private fun DestinationRow(
    label: NavCopy,
    english: Boolean,
    onClick: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(TsTokens.CornerSmall),
        tonalElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TsTokens.MinTouchTarget)
            .clickable(role = Role.Button, onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = TsTokens.SpacingMd, vertical = TsTokens.SpacingMd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label.text(english),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f, fill = false)
            )
            Text(
                text = "→",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End
            )
        }
    }
}

/**
 * SEAM: replace with the real `HomeScreen` once it exists. Home/Scan is not owned by any C package
 * yet, so this placeholder is currently the only entry point to the other four destinations — the
 * rows below exist for the shell demo and must disappear when the real screen lands.
 */
@Composable
fun HomePlaceholder(english: Boolean = false, modifier: Modifier = Modifier) {
    val nav = LocalNavActions.current
    PlaceholderScreen(
        title = NavCopy("Scan karein", "Scan"),
        body = NavCopy(
            hinglish = "Koi bhi suspicious message, link ya screenshot yahan paste karke check karein. " +
                "Verdict local rules se banta hai — internet ke bina bhi.",
            english = "Paste any suspicious message, link or screenshot here to check it. " +
                "The verdict comes from local rules — it works without internet."
        ),
        status = NavStrings.placeholderStatus,
        modifier = modifier,
        english = english
    ) {
        Text(
            text = NavStrings.destinationsHeading.text(english),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .padding(top = TsTokens.SpacingSm)
                .semantics { heading() }
        )
        DestinationRow(NavCopy("Result", "Result"), english) { nav.navigate(AppRoute.RESULT) }
        DestinationRow(NavCopy("Call Shield", "Call Shield"), english) {
            nav.navigate(AppRoute.CALL_SHIELD)
        }
        DestinationRow(NavCopy("QR Guard", "QR Guard"), english) { nav.navigate(AppRoute.QR_GUARD) }
        DestinationRow(NavCopy("Settings", "Settings"), english) { nav.navigate(AppRoute.SETTINGS) }
    }
}

/** SEAM: replace with `ResultScreen` (C03, issue #44). */
@Composable
fun ResultPlaceholder(english: Boolean = false, modifier: Modifier = Modifier) {
    PlaceholderScreen(
        title = NavCopy("Result", "Result"),
        body = NavCopy(
            hinglish = "Har result mein risk score aur confidence alag-alag dikhenge, aur har evidence " +
                "ke saath input se asli quote hoga. Quote verify na ho to \"Unable to Verify\" likha jayega.",
            english = "Every result shows the risk score and the confidence separately, and every " +
                "evidence item carries an exact quote from the input. An unverifiable quote reads " +
                "\"Unable to Verify\"."
        ),
        status = NavCopy(
            hinglish = "Result screen abhi ban rahi hai (C03).",
            english = "The Result screen is not built yet (C03)."
        ),
        modifier = modifier,
        english = english
    )
}

/** SEAM: replace with `CallShieldScreen` (C05, issue #46). */
@Composable
fun CallShieldPlaceholder(english: Boolean = false, modifier: Modifier = Modifier) {
    PlaceholderScreen(
        title = NavCopy("Call Shield", "Call Shield"),
        body = NavCopy(
            hinglish = "Call Shield app khula hone par sirf mic se suni jaane wali awaaz check karega — " +
                "aap khud shuru karenge, permission ke saath. Doosre phone ki awaaz record nahi hoti " +
                "aur kuch bhi save nahi hota.",
            english = "Call Shield will check only the audible microphone speech while the app is " +
                "visible — user-started, with permission. It does not record the other side of the " +
                "call and stores nothing."
        ),
        status = NavCopy(
            hinglish = "Call Shield screen abhi ban rahi hai (C05).",
            english = "The Call Shield screen is not built yet (C05)."
        ),
        modifier = modifier,
        english = english
    )
}

/** SEAM: replace with `QrGuardScreen` (C06, issue #47). */
@Composable
fun QrGuardPlaceholder(english: Boolean = false, modifier: Modifier = Modifier) {
    PlaceholderScreen(
        title = NavCopy("QR Guard", "QR Guard"),
        body = NavCopy(
            hinglish = "QR Guard decoded QR ko data ki tarah padhega aur locally saved payee se compare " +
                "karega. Koi QR ya link kabhi kholega nahi.",
            english = "QR Guard reads a decoded QR as data and compares it against the locally saved " +
                "payee. It never opens a QR or a link."
        ),
        status = NavCopy(
            hinglish = "QR Guard screen abhi ban rahi hai (C06).",
            english = "The QR Guard screen is not built yet (C06)."
        ),
        modifier = modifier,
        english = english
    )
}

/** SEAM: replace with `SettingsScreen` (C04, issue #45). */
@Composable
fun SettingsPlaceholder(english: Boolean = false, modifier: Modifier = Modifier) {
    PlaceholderScreen(
        title = NavCopy("Settings", "Settings"),
        body = NavCopy(
            hinglish = "Theme, bhasha, sensitivity aur haptics yahan set honge. \u2018Saare warnings band\u2019 " +
                "jaisa koi option nahi hoga.",
            english = "Theme, language, sensitivity and haptics will live here. There is no " +
                "\u2018turn off all warnings\u2019 preset."
        ),
        status = NavCopy(
            hinglish = "Settings screen abhi ban rahi hai (C04).",
            english = "The Settings screen is not built yet (C04)."
        ),
        modifier = modifier,
        english = english
    )
}

/**
 * Route -> screen map for the shell. `MainActivity` passes this to [AppNavHost], which keeps the
 * activity free of navigation logic and keeps each real screen a one-line swap:
 *
 * ```
 * AppRoute.RESULT -> ResultScreen(state = ..., onAction = ...)
 * ```
 */
@Composable
fun TrustShieldRouteContent(route: AppRoute, english: Boolean = false) {
    when (route) {
        AppRoute.HOME -> HomePlaceholder(english)
        AppRoute.RESULT -> ResultPlaceholder(english)
        AppRoute.CALL_SHIELD -> CallShieldPlaceholder(english)
        AppRoute.QR_GUARD -> QrGuardPlaceholder(english)
        AppRoute.SETTINGS -> SettingsPlaceholder(english)
    }
}

@Preview(name = "Shell — light", showBackground = true)
@Composable
private fun HomePlaceholderPreviewLight() {
    TrustShieldTheme(darkTheme = false) { TrustShieldRouteContent(AppRoute.HOME) }
}

@Preview(name = "Shell — dark, English", showBackground = true)
@Composable
private fun HomePlaceholderPreviewDark() {
    TrustShieldTheme(darkTheme = true) { TrustShieldRouteContent(AppRoute.HOME, english = true) }
}
