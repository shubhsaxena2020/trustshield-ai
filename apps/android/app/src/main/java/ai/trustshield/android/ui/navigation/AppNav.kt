package ai.trustshield.android.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ai.trustshield.android.R
import ai.trustshield.android.data.preferences.AppLanguage
import ai.trustshield.android.data.preferences.resolveLanguage
import ai.trustshield.android.ui.theme.ToneCopy
import java.util.Locale

@Immutable
data class NavActions(
    val navigate: (AppRoute) -> Unit,
    val replace: (AppRoute) -> Unit,
    val back: () -> Boolean
)

val LocalNavActions: ProvidableCompositionLocal<NavActions> = staticCompositionLocalOf {
    NavActions(navigate = {}, replace = {}, back = { false })
}

val NavBackStackSaver: Saver<NavBackStack, Any> = listSaver(
    save = { stack -> stack.serialise() },
    restore = { wire -> NavBackStack.restore(wire) }
)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AppNavHost(
    modifier: Modifier = Modifier,
    initial: NavBackStack = NavBackStack.Home,
    entry: AppRoute? = null,
    entrySequence: Int = 0,
    content: @Composable (AppRoute) -> Unit
) {
    val state: MutableState<NavBackStack> = rememberSaveable(stateSaver = NavBackStackSaver) {
        mutableStateOf(initial)
    }
    val lastAppliedEntrySequence = rememberSaveable { mutableStateOf(Int.MIN_VALUE) }

    LaunchedEffect(entry, entrySequence) {
        if (shouldApplyIntentEntry(entry, entrySequence, lastAppliedEntrySequence.value)) {
            if (state.value.current != entry) state.value = NavBackStack.fromIntentEntry(entry?.wireName)
            lastAppliedEntrySequence.value = entrySequence
        }
    }

    val actions = remember {
        NavActions(
            navigate = { route -> state.value = state.value.push(route) },
            replace = { route -> state.value = state.value.replace(route) },
            back = {
                state.value.pop()?.let { state.value = it; true } ?: false
            }
        )
    }
    BackHandler(enabled = state.value.canPop) {
        state.value.pop()?.let { state.value = it }
    }

    CompositionLocalProvider(LocalNavActions provides actions) {
        val preferences = LocalUserPreferences.current
        val preferencesSink = LocalPreferencesSink.current
        val selectedLanguage = resolveLanguage(preferences.language, Locale.getDefault().toLanguageTag())
        var languageMenuOpen by remember { mutableStateOf(false) }
        val languageOptions = listOf(
            AppLanguage.SYSTEM to ToneCopy("Phone ki bhasha", "System", "सिस्टम"),
            AppLanguage.HINGLISH to ToneCopy("Hinglish", "Hinglish", "हिंग्लिश"),
            AppLanguage.ENGLISH to ToneCopy("English", "English", "अंग्रेज़ी"),
            AppLanguage.HINDI to ToneCopy("Hindi", "Hindi", "हिन्दी")
        )
        val currentLanguageLabel = languageOptions.first { it.first == selectedLanguage }
            .second.forLanguage(selectedLanguage)
        val tabLabels = mapOf(
            AppRoute.HOME to ToneCopy("Check", "Check", "जाँच"),
            AppRoute.CALL_SHIELD to ToneCopy("Call", "Call", "कॉल"),
            AppRoute.QR_GUARD to ToneCopy("QR", "QR", "QR"),
            AppRoute.HISTORY to ToneCopy("Recent", "Recent", "हाल"),
            AppRoute.SETTINGS to ToneCopy("Setup", "Setup", "विकल्प")
        )

        Scaffold(
            modifier = modifier.fillMaxSize().testTag("app_shell"),
            topBar = {
                TopAppBar(
                    title = {
                        Image(
                            painter = painterResource(R.drawable.trustshield_logo_mask),
                            contentDescription = ToneCopy("TrustShield AI", "TrustShield AI", "ट्रस्टशील्ड एआई").forLanguage(selectedLanguage),
                            contentScale = ContentScale.Fit,
                            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
                            modifier = Modifier.size(width = 148.dp, height = 38.dp)
                        )
                    },
                    actions = {
                        Box {
                            IconButton(
                                onClick = { languageMenuOpen = true },
                                modifier = Modifier.widthIn(min = 120.dp).testTag("language_picker")
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.semantics {
                                        contentDescription = ToneCopy(
                                            "Bhasha chunein: $currentLanguageLabel",
                                            "Choose language: $currentLanguageLabel",
                                            "भाषा चुनें: $currentLanguageLabel"
                                        ).forLanguage(selectedLanguage)
                                    }
                                ) {
                                    AppGlyph(AppGlyphKind.GLOBE, MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        currentLanguageLabel,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
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
                                        modifier = Modifier.testTag("language_${language.name.lowercase()}")
                                    )
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            },
            bottomBar = {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.testTag("bottom_navigation")
                ) {
                    AppRoute.primaryTabs.forEach { route ->
                        val label = tabLabels.getValue(route)
                        val selected = if (state.value.current == AppRoute.RESULT) {
                            route == AppRoute.HOME
                        } else state.value.current == route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (route != state.value.current) {
                                    state.value = if (route == AppRoute.HOME) NavBackStack.Home
                                    else NavBackStack.fromIntentEntry(route.wireName)
                                }
                            },
                            icon = { AppGlyph(route.glyphKind(), MaterialTheme.colorScheme.onSurfaceVariant) },
                            label = { Text(label.forLanguage(selectedLanguage), maxLines = 2) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onSurface,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            modifier = Modifier.testTag("nav_${route.wireName}")
                        )
                    }
                }
            }
        ) { innerPadding ->
            Box(Modifier.padding(innerPadding).fillMaxSize()) {
                content(state.value.current)
            }
        }
    }
}

private enum class AppGlyphKind { CHECK, CALL, QR, HISTORY, SETTINGS, GLOBE }

private fun AppRoute.glyphKind(): AppGlyphKind = when (this) {
    AppRoute.HOME, AppRoute.RESULT, AppRoute.BOMB_SHIELD -> AppGlyphKind.CHECK
    AppRoute.CALL_SHIELD -> AppGlyphKind.CALL
    AppRoute.QR_GUARD -> AppGlyphKind.QR
    AppRoute.HISTORY -> AppGlyphKind.HISTORY
    AppRoute.SETTINGS -> AppGlyphKind.SETTINGS
}

@Composable
private fun AppGlyph(kind: AppGlyphKind, color: Color) {
    Canvas(Modifier.size(24.dp)) {
        val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
        val center = size.width / 2
        when (kind) {
            AppGlyphKind.CHECK -> {
                drawCircle(color, size.width * .28f, Offset(center * .82f, center * .82f), style = stroke)
                drawLine(color, Offset(center * 1.35f, center * 1.35f), Offset(center * 1.82f, center * 1.82f), stroke.width, StrokeCap.Round)
            }
            AppGlyphKind.CALL -> {
                val path = Path().apply {
                    moveTo(size.width * .24f, size.height * .18f)
                    cubicTo(size.width * .10f, size.height * .30f, size.width * .35f, size.height * .72f, size.width * .53f, size.height * .80f)
                    cubicTo(size.width * .72f, size.height * .88f, size.width * .83f, size.height * .81f, size.width * .84f, size.height * .70f)
                    lineTo(size.width * .67f, size.height * .57f)
                    lineTo(size.width * .54f, size.height * .66f)
                    lineTo(size.width * .35f, size.height * .46f)
                    lineTo(size.width * .44f, size.height * .33f)
                    close()
                }
                drawPath(path, color, style = stroke)
            }
            AppGlyphKind.QR -> {
                val a = size.width * .20f; val b = size.width * .43f; val c = size.width * .80f
                listOf(
                    Offset(a, a) to Offset(b, a), Offset(a, a) to Offset(a, b),
                    Offset(c, a) to Offset(c, b), Offset(b, a) to Offset(c, a),
                    Offset(a, c) to Offset(a, b), Offset(a, c) to Offset(b, c),
                    Offset(c, b) to Offset(c, c), Offset(b, c) to Offset(c, c)
                ).forEach { (from, to) -> drawLine(color, from, to, stroke.width, StrokeCap.Round) }
                drawRect(color, Offset(size.width * .43f, size.height * .43f), Size(size.width * .14f, size.height * .14f))
            }
            AppGlyphKind.HISTORY -> {
                drawCircle(color, size.width * .36f, style = stroke)
                drawLine(color, Offset(center, center), Offset(center, size.height * .30f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(center, center), Offset(size.width * .68f, size.height * .58f), stroke.width, StrokeCap.Round)
            }
            AppGlyphKind.SETTINGS -> {
                drawCircle(color, size.width * .30f, style = stroke)
                drawCircle(color, size.width * .09f, style = stroke)
                for (i in 0 until 8) {
                    val angle = i * (Math.PI / 4.0)
                    val start = Offset(center + kotlin.math.cos(angle).toFloat() * size.width * .36f, center + kotlin.math.sin(angle).toFloat() * size.height * .36f)
                    val end = Offset(center + kotlin.math.cos(angle).toFloat() * size.width * .47f, center + kotlin.math.sin(angle).toFloat() * size.height * .47f)
                    drawLine(color, start, end, stroke.width, StrokeCap.Round)
                }
            }
            AppGlyphKind.GLOBE -> {
                drawCircle(color, size.width * .37f, style = stroke)
                drawOval(color, Offset(size.width * .36f, size.height * .13f), Size(size.width * .28f, size.height * .74f), style = stroke)
                drawLine(color, Offset(size.width * .17f, center), Offset(size.width * .83f, center), stroke.width, StrokeCap.Round)
            }
        }
    }
}
