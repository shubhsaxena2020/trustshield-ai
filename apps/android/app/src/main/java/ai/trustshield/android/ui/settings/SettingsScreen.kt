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
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
 *  - each row exposes a merged `contentDescription` (label + detail) so the control is not read as
 *    a bare "Switch, on";
 *  - the column scrolls, so 200% font scale at 320 dp width cannot clip content;
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
    onPlayVoiceSample: () -> Unit = {},
    onVoiceSelfTest: () -> Unit = {},
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
        Text(
            text = copy(SettingsCopy.screenTitle),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = copy(SettingsCopy.screenSubtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(TsTokens.SpacingMd))

        // ---- Theme ---------------------------------------------------------------------------
        SettingsSection(title = ToneCopy("Dikhawat", "Appearance", "रूप").forLanguage(state.preferences.language)) {
            OptionRow(
                label = copy(SettingsCopy.themeSystem),
                detail = copy(SettingsCopy.themeSystemDetail),
                selected = state.themeMode == ThemeMode.SYSTEM,
                onClick = { onEvent(SettingsEvent.SelectTheme(ThemeMode.SYSTEM)) }
            )
            OptionRow(
                label = copy(SettingsCopy.themeLight),
                detail = null,
                selected = state.themeMode == ThemeMode.LIGHT,
                onClick = { onEvent(SettingsEvent.SelectTheme(ThemeMode.LIGHT)) }
            )
            OptionRow(
                label = copy(SettingsCopy.themeDark),
                detail = null,
                selected = state.themeMode == ThemeMode.DARK,
                onClick = { onEvent(SettingsEvent.SelectTheme(ThemeMode.DARK)) }
            )
        }

        // ---- Language ------------------------------------------------------------------------
        SettingsSection(title = copy(SettingsCopy.languageSection)) {
            OptionRow(
                label = copy(SettingsCopy.languageSystem),
                detail = null,
                selected = state.language == AppLanguage.SYSTEM,
                onClick = { onEvent(SettingsEvent.SelectLanguage(AppLanguage.SYSTEM)) }
            )
            OptionRow(
                label = copy(SettingsCopy.languageHinglish),
                detail = null,
                selected = state.language == AppLanguage.HINGLISH,
                onClick = { onEvent(SettingsEvent.SelectLanguage(AppLanguage.HINGLISH)) }
            )
            OptionRow(
                label = copy(SettingsCopy.languageEnglish),
                detail = null,
                selected = state.language == AppLanguage.ENGLISH,
                onClick = { onEvent(SettingsEvent.SelectLanguage(AppLanguage.ENGLISH)) }
            )
            OptionRow(
                label = copy(SettingsCopy.languageHindi),
                detail = null,
                selected = state.language == AppLanguage.HINDI,
                onClick = { onEvent(SettingsEvent.SelectLanguage(AppLanguage.HINDI)) }
            )
            SupportingNote(copy(SettingsCopy.languageHint))
        }

        // ---- Voice ---------------------------------------------------------------------------
        SettingsSection(title = ToneCopy("Voice", "Voice", "आवाज़").forLanguage(state.language)) {
            OptionRow(
                label = ToneCopy("Automatic (default)", "Automatic (default)", "Automatic (default)").forLanguage(state.language),
                detail = ToneCopy("Online par Google; offline par phone", "Google online; phone offline", "Online par Google; offline par phone").forLanguage(state.language),
                selected = state.preferences.voiceRecognition == ai.trustshield.android.data.preferences.VoiceRecognitionChoice.AUTOMATIC,
                onClick = { onEvent(SettingsEvent.SelectVoiceRecognition(ai.trustshield.android.data.preferences.VoiceRecognitionChoice.AUTOMATIC)) }
            )
            OptionRow(
                label = ToneCopy("Offline only (Vosk)", "Offline only (Vosk)", "Sirf offline (Vosk)").forLanguage(state.language),
                detail = ToneCopy("Network kabhi use nahi hoga", "Never uses the network", "Network kabhi use nahi hoga").forLanguage(state.language),
                selected = state.preferences.voiceRecognition == ai.trustshield.android.data.preferences.VoiceRecognitionChoice.OFFLINE_ONLY,
                onClick = { onEvent(SettingsEvent.SelectVoiceRecognition(ai.trustshield.android.data.preferences.VoiceRecognitionChoice.OFFLINE_ONLY)) }
            )
            OptionRow(
                label = ToneCopy("Online preferred", "Online preferred", "Online preferred").forLanguage(state.language),
                detail = ToneCopy("Google recogniser ko preference dein", "Prefer Google's recogniser", "Google recogniser ko preference dein").forLanguage(state.language),
                selected = state.preferences.voiceRecognition == ai.trustshield.android.data.preferences.VoiceRecognitionChoice.ONLINE_PREFERRED,
                onClick = { onEvent(SettingsEvent.SelectVoiceRecognition(ai.trustshield.android.data.preferences.VoiceRecognitionChoice.ONLINE_PREFERRED)) }
            )
            SupportingNote(ToneCopy(
                "Online: your voice is sent to Google's speech service. Offline: processed on this phone.",
                "Online: your voice is sent to Google's speech service. Offline: processed on this phone.",
                "Online par: aapki voice Google speech service ko bheji jaati hai. Offline par: isi phone par process hoti hai."
            ).forLanguage(state.language))
            ToggleRow(
                label = ToneCopy("Spoken verdict", "Spoken verdict", "Spoken verdict").forLanguage(state.language),
                detail = ToneCopy("Verdict ko awaaz mein sunayein", "Read the verdict aloud", "Verdict ko awaaz mein sunayein").forLanguage(state.language),
                checked = !state.preferences.spokenVerdictMuted,
                testTag = "settings_spoken_verdict_toggle",
                onCheckedChange = { onEvent(SettingsEvent.SetSpokenVerdictMuted(!it)) }
            )
            Text(
                ToneCopy("Speech rate: %.2fx".format(state.preferences.spokenVerdictRate), "Speech rate: %.2fx".format(state.preferences.spokenVerdictRate), "Speech rate: %.2fx".format(state.preferences.spokenVerdictRate)).forLanguage(state.language),
                modifier = Modifier.padding(top = TsTokens.SpacingXs)
            )
            Slider(
                value = state.preferences.spokenVerdictRate,
                onValueChange = { onEvent(SettingsEvent.SetSpokenVerdictRate(it)) },
                valueRange = 0.75f..1.25f,
                steps = 9,
                modifier = Modifier.fillMaxWidth().heightIn(min = TsTokens.MinTouchTarget)
                    .semantics { contentDescription = ToneCopy("Speech rate", "Speech rate", "Speech rate").forLanguage(state.language) }
            )
            OutlinedButton(
                onClick = onPlayVoiceSample,
                modifier = Modifier.fillMaxWidth().heightIn(min = TsTokens.MinTouchTarget)
            ) { Text(ToneCopy("Play sample", "Play sample", "Sample sunayein").forLanguage(state.language)) }
            OutlinedButton(
                onClick = onVoiceSelfTest,
                modifier = Modifier.fillMaxWidth().heightIn(min = TsTokens.MinTouchTarget)
            ) { Text(ToneCopy("Voice self-test", "Voice self-test", "Voice self-test").forLanguage(state.language)) }
        }

        // ---- Sensitivity ---------------------------------------------------------------------
        SettingsSection(title = ToneCopy("Privacy", "Privacy", "गोपनीयता").forLanguage(state.preferences.language)) {
            OptionRow(
                label = copy(SettingsCopy.sensitivityLow),
                detail = copy(SettingsCopy.sensitivityLowDetail),
                selected = state.sensitivity == Sensitivity.LOW,
                onClick = { onEvent(SettingsEvent.SelectSensitivity(Sensitivity.LOW)) }
            )
            OptionRow(
                label = copy(SettingsCopy.sensitivityBalanced),
                detail = copy(SettingsCopy.sensitivityBalancedDetail),
                selected = state.sensitivity == Sensitivity.BALANCED,
                onClick = { onEvent(SettingsEvent.SelectSensitivity(Sensitivity.BALANCED)) }
            )
            OptionRow(
                label = copy(SettingsCopy.sensitivityHigh),
                detail = copy(SettingsCopy.sensitivityHighDetail),
                selected = state.sensitivity == Sensitivity.HIGH,
                onClick = { onEvent(SettingsEvent.SelectSensitivity(Sensitivity.HIGH)) }
            )
            SupportingNote(copy(SettingsCopy.sensitivitySafetyNote))
        }

        // ---- Haptics -------------------------------------------------------------------------
        SettingsSection(title = ToneCopy("Call Shield", "Call Shield", "कॉल शील्ड").forLanguage(state.preferences.language)) {
            ToggleRow(
                label = copy(SettingsCopy.hapticsLabel),
                detail = copy(SettingsCopy.hapticsDetail),
                checked = state.haptics,
                testTag = "settings_haptics_toggle",
                onCheckedChange = { onEvent(SettingsEvent.SetHaptics(it)) }
            )
        }

        // ---- Reduced motion ------------------------------------------------------------------
        SettingsSection(title = ToneCopy("Dikhawat aur motion", "Appearance and motion", "रूप और गति").forLanguage(state.preferences.language)) {
            ToggleRow(
                label = copy(SettingsCopy.reducedMotionLabel),
                detail = copy(SettingsCopy.reducedMotionDetail),
                checked = state.reducedMotion,
                testTag = "settings_reduced_motion_toggle",
                onCheckedChange = { onEvent(SettingsEvent.SetReducedMotion(it)) }
            )
        }

        DeviceCheckPanel(language = state.preferences.language)

        Spacer(Modifier.height(TsTokens.SpacingLg))

        OutlinedButton(colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
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
        SettingsSection(title = ToneCopy("About aur licences", "About and licences", "परिचय और लाइसेंस").forLanguage(state.preferences.language)) {
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
                TextButton(colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                    onClick = { onEvent(SettingsEvent.ConfirmReset) },
                    modifier = Modifier.heightIn(min = TsTokens.MinTouchTarget)
                ) {
                    Text(copy(SettingsCopy.resetConfirm))
                }
            },
            dismissButton = {
                TextButton(colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
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

    SettingsSection(title = ToneCopy("Device check", "Device check").forLanguage(language)) {
        TextButton(colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
            onClick = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth().heightIn(min = TsTokens.MinTouchTarget),
        ) {
            Text(
                ToneCopy(
                    "Device ki jaankari ${if (expanded) "chhupayein" else "dekhein"}",
                    "${if (expanded) "Hide" else "Show"} device details",
                ).forLanguage(language),
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
                DeviceCheckLine("Android", "${current.androidVersion} (API ${current.apiLevel})")
                DeviceCheckLine("ABI", current.abi)
                DeviceCheckLine(ToneCopy("Speech recognizer", "Speech recognizer").forLanguage(language), DeviceCheckCopy.availability(current.recognizerAvailable, language))
                DeviceCheckLine(ToneCopy("Hindi hi-IN", "Hindi hi-IN").forLanguage(language), locale)
                DeviceCheckLine(ToneCopy("Camera permission", "Camera permission").forLanguage(language), DeviceCheckCopy.permission(current.cameraPermission, language))
                DeviceCheckLine(ToneCopy("Microphone permission", "Microphone permission").forLanguage(language), DeviceCheckCopy.permission(current.microphonePermission, language))
            }
        }
    }
}

@Composable
private fun DeviceCheckLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = TsTokens.MinTouchTarget),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
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
            color = MaterialTheme.colorScheme.onSurface
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
 */
@Composable
private fun OptionRow(
    label: String,
    detail: String?,
    selected: Boolean,
    onClick: () -> Unit
) {
    val description = if (detail.isNullOrBlank()) label else "$label. $detail"
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
            }
            .padding(vertical = TsTokens.SpacingXs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(
                selectedColor = MaterialTheme.colorScheme.onSurface,
                unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
        Spacer(Modifier.width(TsTokens.SpacingMd))
        Column(modifier = Modifier.fillMaxWidth()) {
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
 * target and owns the semantics, so TalkBack reports "Switch, off/on" with our label.
 */
@Composable
private fun ToggleRow(
    label: String,
    detail: String,
    checked: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit
) {
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
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onSurface,
                checkedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )
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
            TextButton(colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
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
