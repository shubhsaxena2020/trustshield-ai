package ai.trustshield.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import ai.trustshield.android.data.preferences.AppLanguage
import ai.trustshield.android.data.preferences.Sensitivity
import ai.trustshield.android.data.preferences.ThemeMode
import ai.trustshield.android.ui.theme.TsTokens
import ai.trustshield.android.ui.theme.ToneCopy
import ai.trustshield.android.ui.theme.TrustShieldTheme

/**
 * Strings that exist only for announcements: the state of a control, and the labels of the read-only
 * device check. They are [SettingText], the same hinglish/english pair (Devanagari through
 * `ToneCopy`) that [SettingsCopy] uses, because `SettingText.forLanguage` is the only resolution
 * path on this screen that also handles [AppLanguage.SYSTEM] the way the rest of the copy does.
 *
 * None of these is a new control: there is still no "turn off all warnings" option.
 */
private object SettingsA11yCopy {

    val selected = SettingText("Chuna hua", "Selected")
    val notSelected = SettingText("Nahi chuna", "Not selected")
    val switchOn = SettingText("Chalu", "On")
    val switchOff = SettingText("Band", "Off")

    val deviceCheckSection = SettingText("Device check", "Device check")
    val showDeviceDetails = SettingText("Device ki jaankari dekhein", "Show device details")
    val hideDeviceDetails = SettingText("Device ki jaankari chhupayein", "Hide device details")
    val deviceAndroid = SettingText("Android", "Android")
    val deviceAbi = SettingText("ABI", "ABI")
    val deviceSpeechRecognizer = SettingText("Speech recognizer", "Speech recognizer")
    val deviceHindiLocale = SettingText("Hindi hi-IN", "Hindi hi-IN")
    val deviceCamera = SettingText("Camera permission", "Camera permission")
    val deviceMicrophone = SettingText("Microphone permission", "Microphone permission")
}

/**
 * Settings screen (work package C04, issue #45).
 *
 * Deliberately dumb: it renders [SettingsState] and forwards [SettingsEvent]s. All decisions live
 * in the pure reducer in `SettingsState.kt` so they are unit-testable without a device
 * (COMPOSE UI tests are not on the classpath — Codex owns build files).
 *
 * Accessibility contract (UI_SPEC, QUALITY_GATES gate 6):
 *  - every row is at least [TsTokens.MinTouchTarget] (48 dp) tall and full width;
 *  - radios use `Role.RadioButton` in a `selectableGroup`, switches use `Role.Switch`, so TalkBack
 *    announces the control type and its checked state;
 *  - each row exposes a merged `contentDescription` (label + detail) **and** a translated
 *    `stateDescription` for the current selection, so the control is never read as a bare
 *    "Switch, on" or as an unlabelled radio;
 *  - the screen title is the single top-level heading and every section title carries `heading()`,
 *    so heading navigation walks the sections in order;
 *  - the column scrolls and every label wraps (no `maxLines`), so 200% font scale at 320 dp width
 *    cannot clip content;
 *  - state is never conveyed by colour alone.
 *
 * There is no "turn off all warnings" control, by design.
 *
 * // SEAM: C01 (`ui/navigation/AppNav.kt`) supplies [state] and persists
 * // `state.preferences` through `SharedPreferencesUserPreferencesStore` / A05's threshold
 * // consumer. Until A05 exists, `SensitivityPolicy.thresholds(...)` is the contract it reads.
 */
@Composable
fun SettingsScreen(
    state: SettingsState,
    onEvent: (SettingsEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    val copy: (SettingText) -> String = { state.text(it) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                horizontal = TsTokens.SpacingLg,
                vertical = TsTokens.SpacingXl
            ),
        verticalArrangement = Arrangement.spacedBy(TsTokens.SpacingSm)
    ) {
        // The one top-level heading on this screen; the section titles below are its subsections.
        Text(
            text = copy(SettingsCopy.screenTitle),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() }
        )
        Text(
            text = copy(SettingsCopy.screenSubtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(TsTokens.SpacingMd))

        // ---- Theme ---------------------------------------------------------------------------
        SettingsSection(title = copy(SettingsCopy.themeSection)) {
            OptionRow(
                label = copy(SettingsCopy.themeSystem),
                detail = copy(SettingsCopy.themeSystemDetail),
                selected = state.themeMode == ThemeMode.SYSTEM,
                language = state.language,
                onClick = { onEvent(SettingsEvent.SelectTheme(ThemeMode.SYSTEM)) }
            )
            OptionRow(
                label = copy(SettingsCopy.themeLight),
                detail = null,
                selected = state.themeMode == ThemeMode.LIGHT,
                language = state.language,
                onClick = { onEvent(SettingsEvent.SelectTheme(ThemeMode.LIGHT)) }
            )
            OptionRow(
                label = copy(SettingsCopy.themeDark),
                detail = null,
                selected = state.themeMode == ThemeMode.DARK,
                language = state.language,
                onClick = { onEvent(SettingsEvent.SelectTheme(ThemeMode.DARK)) }
            )
        }

        // ---- Language ------------------------------------------------------------------------
        SettingsSection(title = copy(SettingsCopy.languageSection)) {
            OptionRow(
                label = copy(SettingsCopy.languageSystem),
                detail = null,
                selected = state.language == AppLanguage.SYSTEM,
                language = state.language,
                onClick = { onEvent(SettingsEvent.SelectLanguage(AppLanguage.SYSTEM)) }
            )
            OptionRow(
                label = copy(SettingsCopy.languageHinglish),
                detail = null,
                selected = state.language == AppLanguage.HINGLISH,
                language = state.language,
                onClick = { onEvent(SettingsEvent.SelectLanguage(AppLanguage.HINGLISH)) }
            )
            OptionRow(
                label = copy(SettingsCopy.languageEnglish),
                detail = null,
                selected = state.language == AppLanguage.ENGLISH,
                language = state.language,
                onClick = { onEvent(SettingsEvent.SelectLanguage(AppLanguage.ENGLISH)) }
            )
            OptionRow(
                label = copy(SettingsCopy.languageHindi),
                detail = null,
                selected = state.language == AppLanguage.HINDI,
                language = state.language,
                onClick = { onEvent(SettingsEvent.SelectLanguage(AppLanguage.HINDI)) }
            )
            SupportingNote(copy(SettingsCopy.languageHint))
        }

        // ---- Sensitivity ---------------------------------------------------------------------
        SettingsSection(title = copy(SettingsCopy.sensitivitySection)) {
            OptionRow(
                label = copy(SettingsCopy.sensitivityLow),
                detail = copy(SettingsCopy.sensitivityLowDetail),
                selected = state.sensitivity == Sensitivity.LOW,
                language = state.language,
                onClick = { onEvent(SettingsEvent.SelectSensitivity(Sensitivity.LOW)) }
            )
            OptionRow(
                label = copy(SettingsCopy.sensitivityBalanced),
                detail = copy(SettingsCopy.sensitivityBalancedDetail),
                selected = state.sensitivity == Sensitivity.BALANCED,
                language = state.language,
                onClick = { onEvent(SettingsEvent.SelectSensitivity(Sensitivity.BALANCED)) }
            )
            OptionRow(
                label = copy(SettingsCopy.sensitivityHigh),
                detail = copy(SettingsCopy.sensitivityHighDetail),
                selected = state.sensitivity == Sensitivity.HIGH,
                language = state.language,
                onClick = { onEvent(SettingsEvent.SelectSensitivity(Sensitivity.HIGH)) }
            )
            SupportingNote(copy(SettingsCopy.sensitivitySafetyNote))
        }

        // ---- Haptics -------------------------------------------------------------------------
        SettingsSection(title = copy(SettingsCopy.hapticsSection)) {
            ToggleRow(
                label = copy(SettingsCopy.hapticsLabel),
                detail = copy(SettingsCopy.hapticsDetail),
                checked = state.haptics,
                language = state.language,
                testTag = "settings_haptics_toggle",
                onCheckedChange = { onEvent(SettingsEvent.SetHaptics(it)) }
            )
        }

        // ---- Reduced motion ------------------------------------------------------------------
        SettingsSection(title = copy(SettingsCopy.reducedMotionSection)) {
            ToggleRow(
                label = copy(SettingsCopy.reducedMotionLabel),
                detail = copy(SettingsCopy.reducedMotionDetail),
                checked = state.reducedMotion,
                language = state.language,
                testTag = "settings_reduced_motion_toggle",
                onCheckedChange = { onEvent(SettingsEvent.SetReducedMotion(it)) }
            )
        }

        DeviceCheckPanel(language = state.preferences.language)

        Spacer(Modifier.height(TsTokens.SpacingLg))

        OutlinedButton(
            onClick = { onEvent(SettingsEvent.RequestReset) },
            enabled = !state.isAtDefaults,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = TsTokens.MinTouchTarget)
        ) {
            Text(
                text = copy(SettingsCopy.resetAction),
                style = MaterialTheme.typography.labelLarge
            )
        }

        Spacer(Modifier.height(TsTokens.SpacingSm))

        Text(
            text = copy(SettingsCopy.privacyNote),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SettingsSection(title = copy(SettingsCopy.aboutSection)) {
            SupportingNote(copy(SettingsCopy.aboutBody))
        }

        if (state.resetApplied) {
            Spacer(Modifier.height(TsTokens.SpacingMd))
            ResetAppliedBanner(
                message = copy(SettingsCopy.resetApplied),
                dismissLabel = copy(SettingsCopy.dismissOk),
                onDismiss = { onEvent(SettingsEvent.DismissNotice) }
            )
        }
    }

    if (state.resetConfirmationVisible) {
        AlertDialog(
            onDismissRequest = { onEvent(SettingsEvent.CancelReset) },
            title = { Text(copy(SettingsCopy.resetDialogTitle)) },
            text = { Text(copy(SettingsCopy.resetDialogBody)) },
            confirmButton = {
                TextButton(
                    onClick = { onEvent(SettingsEvent.ConfirmReset) },
                    modifier = Modifier.heightIn(min = TsTokens.MinTouchTarget)
                ) {
                    Text(copy(SettingsCopy.resetConfirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { onEvent(SettingsEvent.CancelReset) },
                    modifier = Modifier.heightIn(min = TsTokens.MinTouchTarget)
                ) {
                    Text(copy(SettingsCopy.resetCancel))
                }
            }
        )
    }
}

@Composable
private fun DeviceCheckPanel(language: AppLanguage) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var hindiLocale by remember { mutableStateOf(DeviceCheckLocale.NOT_CHECKED) }
    val current = DeviceCheckProbe.snapshot(context, hindiLocale)
    // The device check is read-only diagnostics; every visible string still comes from the copy
    // layer. It keeps the screen's existing boolean split because `DeviceCheckCopy` in
    // `DeviceCheck.kt` (not this file) only knows Hinglish and English.
    val copy: (SettingText) -> String = {
        it.forLanguage(language)
    }

    SettingsSection(title = copy(SettingsA11yCopy.deviceCheckSection)) {
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth().heightIn(min = TsTokens.MinTouchTarget),
        ) {
            Text(
                text = copy(
                    if (expanded) SettingsA11yCopy.hideDeviceDetails
                    else SettingsA11yCopy.showDeviceDetails
                )
            )
        }
        if (expanded) {
            LaunchedEffect(Unit) {
                DeviceCheckProbe.checkHindiLocale(
                    context = context,
                    executor = ContextCompat.getMainExecutor(context),
                    onResult = { hindiLocale = it },
                )
            }
            val locale = DeviceCheckCopy.localeStatus(current.hindiLocale, language)
            Column(verticalArrangement = Arrangement.spacedBy(TsTokens.SpacingXs)) {
                DeviceCheckLine(
                    copy(SettingsA11yCopy.deviceAndroid),
                    "${current.androidVersion} (API ${current.apiLevel})"
                )
                DeviceCheckLine(copy(SettingsA11yCopy.deviceAbi), current.abi)
                DeviceCheckLine(
                    copy(SettingsA11yCopy.deviceSpeechRecognizer),
                    DeviceCheckCopy.availability(current.recognizerAvailable, language)
                )
                DeviceCheckLine(
                    copy(SettingsA11yCopy.deviceHindiLocale),
                    locale,
                    announceChanges = true
                )
                DeviceCheckLine(
                    copy(SettingsA11yCopy.deviceCamera),
                    DeviceCheckCopy.permission(current.cameraPermission, language)
                )
                DeviceCheckLine(
                    copy(SettingsA11yCopy.deviceMicrophone),
                    DeviceCheckCopy.permission(current.microphonePermission, language)
                )
            }
        }
    }
}

/**
 * One read-only diagnostic line.
 *
 * The label and its value are merged into a single node and the value is part of the announced
 * content, so the status is spoken as text and never depends on colour. A "not checked" value is
 * announced exactly as [DeviceCheckCopy] writes it, which is a different sentence from
 * "not supported".
 *
 * [announceChanges] makes the line a polite live region. Only the Hindi-locale line sets it: its
 * value arrives from an asynchronous probe, so the first value on screen is "not checked" and the
 * resolved answer would otherwise replace it silently for a screen-reader user.
 *
 * Stacked instead of side by side on purpose: in a `Row` the label was the weighted child, so at
 * 320 dp with a 200% font scale it was squeezed to zero width and the pair clipped. A column always
 * wraps, and the whole line is still one 48 dp touch target and one focus stop.
 */
@Composable
private fun DeviceCheckLine(label: String, value: String, announceChanges: Boolean = false) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TsTokens.MinTouchTarget)
            .semantics(mergeDescendants = true) {
                contentDescription = "$label. $value"
                if (announceChanges) {
                    liveRegion = LiveRegionMode.Polite
                }
            }
            .padding(vertical = TsTokens.SpacingXs),
        verticalArrangement = Arrangement.spacedBy(TsTokens.SpacingXs)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = TsTokens.SpacingMd),
        verticalArrangement = Arrangement.spacedBy(TsTokens.SpacingXs)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() }
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup(),
            content = content
        )
    }
}

/**
 * Radio row. [detail] is optional supporting text; label and detail are merged into one TalkBack
 * announcement so the control is never read as an unlabelled radio.
 *
 * [language] resolves the *state* announcement. `selectable` only sets a `Selected` boolean; the
 * wording TalkBack derives from it is the platform's own and not localised, so an explicit
 * translated `stateDescription` reports the current selection of this row. The row merges its
 * descendants, so the [RadioButton] inside is never a second focus stop (`onClick = null` also
 * keeps it non-interactive).
 */
@Composable
private fun OptionRow(
    label: String,
    detail: String?,
    selected: Boolean,
    language: AppLanguage,
    onClick: () -> Unit
) {
    val description = if (detail.isNullOrBlank()) label else "$label. $detail"
    val stateLabel = (if (selected) SettingsA11yCopy.selected else SettingsA11yCopy.notSelected)
        .forLanguage(language)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TsTokens.MinTouchTarget)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick
            )
            .semantics(mergeDescendants = true) {
                contentDescription = description
                stateDescription = stateLabel
            }
            .padding(vertical = TsTokens.SpacingXs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(TsTokens.SpacingMd))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!detail.isNullOrBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Switch row. The switch itself has `onCheckedChange = null`; the whole 48 dp row is the toggle
 * target and owns the semantics, so TalkBack reports "Switch, on/off" with our label and a
 * translated [stateDescription] instead of the platform's English "On"/"Off".
 */
@Composable
private fun ToggleRow(
    label: String,
    detail: String,
    checked: Boolean,
    language: AppLanguage,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    val stateLabel = (if (checked) SettingsA11yCopy.switchOn else SettingsA11yCopy.switchOff)
        .forLanguage(language)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TsTokens.MinTouchTarget)
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange
            )
            .semantics(mergeDescendants = true) {
                contentDescription = "$label. $detail"
                stateDescription = stateLabel
                this.testTag = testTag
            }
            .padding(vertical = TsTokens.SpacingXs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(TsTokens.SpacingMd))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun SupportingNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = TsTokens.SpacingXs)
    )
}

@Composable
private fun ResetAppliedBanner(
    message: String,
    dismissLabel: String,
    onDismiss: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(TsTokens.SpacingMd),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = TsTokens.MinTouchTarget)
            ) {
                Text(dismissLabel)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Previews (compile-time only; no device or emulator was used for this package).
// ---------------------------------------------------------------------------------------------

@Preview(name = "Settings — Hinglish, light", showBackground = true, widthDp = 360)
@Composable
private fun SettingsScreenHinglishPreview() {
    TrustShieldTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            SettingsScreen(state = SettingsState(), onEvent = {})
        }
    }
}

@Preview(name = "Settings — English, dark", showBackground = true, widthDp = 360)
@Composable
private fun SettingsScreenEnglishDarkPreview() {
    TrustShieldTheme(darkTheme = true) {
        Surface(color = MaterialTheme.colorScheme.background) {
            SettingsScreen(
                state = SettingsState(
                    preferences = ai.trustshield.android.data.preferences.UserPreferences(
                        language = AppLanguage.ENGLISH,
                        sensitivity = Sensitivity.HIGH
                    )
                ),
                onEvent = {}
            )
        }
    }
}

@Preview(name = "Settings — reset confirmation", showBackground = true, widthDp = 360)
@Composable
private fun SettingsScreenResetPreview() {
    TrustShieldTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            SettingsScreen(
                state = SettingsState(notice = SettingsNotice.RESET_REQUESTED),
                onEvent = {}
            )
        }
    }
}
