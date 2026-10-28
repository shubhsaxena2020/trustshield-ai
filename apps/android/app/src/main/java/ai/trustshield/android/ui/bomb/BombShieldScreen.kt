package ai.trustshield.android.ui.bomb

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import ai.trustshield.android.core.flood.CallOutcome
import ai.trustshield.android.core.flood.FloodDetector
import ai.trustshield.android.core.flood.FloodEvent
import ai.trustshield.android.core.flood.FloodKind
import ai.trustshield.android.core.flood.FloodProfile
import ai.trustshield.android.core.flood.FloodResult
import ai.trustshield.android.core.flood.FloodSeverity
import ai.trustshield.android.core.flood.BombCallMonitor
import ai.trustshield.android.core.flood.BombShieldRuntime
import ai.trustshield.android.data.preferences.AppLanguage
import ai.trustshield.android.ui.theme.ToneCopy

@Composable
fun BombShieldScreen(language: AppLanguage, onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val callMonitor = remember(context) { BombCallMonitor(context) }
    var profile by remember { mutableStateOf(FloodProfile.NORMAL) }
    val result = BombShieldRuntime.snapshot()
    var disclosure by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(BombShieldRuntime.isEnabled(context)) }
    var permissionMissing by remember { mutableStateOf(false) }
    val smsPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
    val callPermissions = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
    val samples by BombShieldRuntime.events.collectAsState()
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val allGranted = grants.values.all { it } &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
        enabled = allGranted
        permissionMissing = !allGranted
        BombShieldRuntime.setEnabled(context, allGranted)
    }
    DisposableEffect(enabled) {
        if (enabled) callMonitor.start()
        onDispose { callMonitor.stop() }
    }
    androidx.compose.runtime.LaunchedEffect(profile) { BombShieldRuntime.useProfile(profile) }
    androidx.compose.runtime.LaunchedEffect(smsPermission, callPermissions) {
        if (enabled && (!smsPermission || !callPermissions)) {
            permissionMissing = true
            enabled = false
            BombShieldRuntime.setEnabled(context, false)
        }
    }
    val UiCopy: (String, String, String) -> String = { h, e, hi -> ToneCopy(h, e, hi).forLanguage(language) }
    val outlinedColors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
    val textColors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(UiCopy("Bomb Shield", "Bomb Shield", "बम शील्ड"), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        Text(
            UiCopy("SMS/OTP aur baar-baar aane wali calls ke achanak flood ko pehchanein.", "Detect sudden floods of SMS/OTPs and repeated calls.", "SMS/OTP और बार-बार आने वाली कॉल की अचानक बाढ़ पहचानें।"),
            style = MaterialTheme.typography.bodyMedium
        )
        Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val status = when {
                    permissionMissing -> UiCopy("Permission nahi mili", "Permission missing", "अनुमति नहीं मिली")
                    enabled -> UiCopy("Suraksha chalu", "Protected", "सुरक्षित")
                    else -> UiCopy("Band", "Off", "बंद")
                }
                Text(status, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics {
                    contentDescription = status
                    liveRegion = LiveRegionMode.Polite
                })
                Text(UiCopy("Nigrani phone par hoti hai. Sirf counts aur samay memory mein rehte hain.", "Monitoring stays on this phone. Only counts and timestamps stay in memory.", "निगरानी इसी फ़ोन पर होती है। केवल गिनती और समय मेमोरी में रहते हैं।"))
                Button(onClick = {
                    if (enabled) {
                        enabled = false
                        permissionMissing = false
                        BombShieldRuntime.setEnabled(context, false)
                    } else disclosure = true
                }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (enabled) UiCopy("Band karein", "Turn off", "बंद करें") else UiCopy("Chalu karein", "Turn on", "चालू करें"))
                }
                if (permissionMissing) {
                    Text(UiCopy("SMS ya call permission nahi mili. System settings mein Bomb Shield permissions dekhein.", "Required SMS or call permissions are missing. Review Bomb Shield permissions in system settings.", "\u091c\u093c\u0930\u0942\u0930\u0940 SMS \u092f\u093e \u0915\u0949\u0932 \u0905\u0928\u0941\u092e\u0924\u093f \u0928\u0939\u0940\u0902 \u092e\u093f\u0932\u0940\u0964 System settings \u092e\u0947\u0902 Bomb Shield permissions \u0926\u0947\u0916\u0947\u0902."))
                }
            }
        }

        Text(UiCopy("Pichhle 60 minute", "Last 60 minutes", "पिछले 60 मिनट"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        FloodTimeline(samples.toList(), UiCopy("Pichhle ek ghante ke events", "Events in the last hour", "पिछले घंटे के इवेंट"), language)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(UiCopy("SMS", "SMS", "SMS"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(UiCopy("Calls", "Calls", "कॉल"), style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = { BombShieldRuntime.reset() }, modifier = Modifier.heightIn(min = 48.dp), colors = textColors) {
                Text(UiCopy("Saaf karein", "Clear", "साफ़ करें"))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(FloodProfile.NORMAL, FloodProfile.STRICT).forEach { option ->
                if (profile == option) Button(onClick = { profile = option }) {
                    Text(if (option == FloodProfile.NORMAL) UiCopy("Normal", "Normal", "सामान्य") else UiCopy("Strict", "Strict", "सख़्त"))
                } else OutlinedButton(onClick = { profile = option }, colors = outlinedColors) {
                    Text(if (option == FloodProfile.NORMAL) UiCopy("Normal", "Normal", "सामान्य") else UiCopy("Strict", "Strict", "सख़्त"))
                }
            }
        }
        Text(UiCopy("DEMO MODE · sample events only", "DEMO MODE · sample events only", "डेमो मोड · केवल नमूना इवेंट"), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                    BombShieldRuntime.reset()
                    repeat(8) { index ->
                        val event = FloodEvent(System.currentTimeMillis() + index, "demo-service-$index", FloodKind.SMS, isOtpLike = index % 2 == 0)
                        BombShieldRuntime.accept(context, event, event.sourceKey)
                    }
            }, modifier = Modifier.weight(1f).heightIn(min = 48.dp), colors = outlinedColors) { Text(UiCopy("SMS bomb simulate", "Simulate SMS bomb", "SMS बम दिखाएँ")) }
            OutlinedButton(onClick = {
                    BombShieldRuntime.reset()
                    repeat(4) { index ->
                        val event = FloodEvent(System.currentTimeMillis() + index, "demo-number-$index", FloodKind.CALL, CallOutcome.MISSED)
                        BombShieldRuntime.accept(context, event, event.sourceKey)
                }
            }, modifier = Modifier.weight(1f).heightIn(min = 48.dp), colors = outlinedColors) { Text(UiCopy("Call bomb simulate", "Simulate call bomb", "कॉल बम दिखाएँ")) }
        }
        if (result.severity == FloodSeverity.FLOOD) FloodAlert(result, language)
        else Text(UiCopy("Abhi koi flood alert nahi.", "No flood alert right now.", "अभी कोई फ्लड अलर्ट नहीं।"))
        Text(UiCopy("Abhi kya karein", "What to do right now", "अभी क्या करें"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Text(UiCopy("OTP kisi ko na batayein. Pareshan karne wale messages/calls ka jawab na dein. Apne mobile operator se sampark karein; cybercrime.gov.in ya 1930 par report karein. TRAI DND ke liye 1909 ya Sanchar Saathi use karein.", "Do not share any OTP. Avoid replying to the flood. Contact your carrier; report at cybercrime.gov.in or call 1930. For TRAI DND, use 1909 or Sanchar Saathi.", "OTP साझा न करें। ऐसे संदेशों या कॉल का जवाब न दें। अपने ऑपरेटर से संपर्क करें; cybercrime.gov.in या 1930 पर रिपोर्ट करें। TRAI DND के लिए 1909 या Sanchar Saathi देखें।"))
        Text(UiCopy("Android app doosre apps ke SMS/calls block ya mute nahi kar sakta. Mute controls system ke paas hain.", "Android does not let this app block SMS/calls or silence other apps. Mute controls remain with the system.", "Android यह ऐप दूसरे ऐप के SMS/कॉल ब्लॉक या साइलेंट नहीं कर सकता। म्यूट नियंत्रण सिस्टम के पास हैं।"), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp), colors = textColors) { Text(UiCopy("Wapas", "Back", "वापस")) }
    }

    if (disclosure) AlertDialog(
        onDismissRequest = { disclosure = false },
        title = { Text(UiCopy("Bomb Shield permission", "Bomb Shield permission", "Bomb Shield अनुमति")) },
        text = { Text(UiCopy("Aane wale SMS sender aur OTP jaisa pattern check hoga. Call state aur recent outcome sirf is screen ke khule hone par check honge. SMS text save nahi hota; grouping ke liye number ka short hash, count aur samay sirf memory mein rehte hain. Kuch phone se bahar nahi jaata. Ab permissions maangi jayengi.", "Incoming SMS sender and an OTP-pattern flag are checked; call state and recent outcomes are read only while this screen is open. Nothing leaves this phone. Message text is never saved; a short number hash, counts and timestamps stay in memory only. Permissions are requested now.", "\u0906\u0928\u0947 \u0935\u093e\u0932\u0947 SMS \u0915\u093e \u092a\u094d\u0930\u0947\u0937\u0915 \u0914\u0930 OTP-\u091c\u0948\u0938\u093e \u0938\u0902\u0915\u0947\u0924 \u091c\u093e\u0901\u091a\u093e \u091c\u093e\u090f\u0917\u093e; \u0915\u0949\u0932 \u0915\u0940 \u0938\u094d\u0925\u093f\u0924\u093f \u0914\u0930 \u0939\u093e\u0932 \u0915\u0947 \u092a\u0930\u093f\u0923\u093e\u092e \u0938\u093f\u0930\u094d\u092b \u092f\u0939 \u0938\u094d\u0915\u094d\u0930\u0940\u0928 \u0916\u0941\u0932\u0940 \u0939\u094b\u0928\u0947 \u092a\u0930 \u092a\u0922\u093c\u0947 \u091c\u093e\u090f\u0901\u0917\u0947\u0964 \u0915\u0941\u091b \u092d\u0940 \u092b\u093c\u094b\u0928 \u0938\u0947 \u092c\u093e\u0939\u0930 \u0928\u0939\u0940\u0902 \u091c\u093e\u0924\u093e\u0964 \u0938\u0902\u0926\u0947\u0936 \u0915\u093e \u092a\u093e\u0920 \u0938\u0939\u0947\u091c\u093e \u0928\u0939\u0940\u0902 \u091c\u093e\u0924\u093e; \u0928\u0902\u092c\u0930 \u0915\u093e \u091b\u094b\u091f\u093e \u0939\u0948\u0936, \u0917\u093f\u0928\u0924\u0940 \u0914\u0930 \u0938\u092e\u092f \u0915\u0947\u0935\u0932 \u092e\u0947\u092e\u094b\u0930\u0940 \u092e\u0947\u0902 \u0930\u0939\u0924\u0947 \u0939\u0948\u0902\u0964 \u0905\u0928\u0941\u092e\u0924\u093f\u092f\u093e\u0901 \u0905\u092c \u092e\u093e\u0901\u0917\u0940 \u091c\u093e\u090f\u0901\u0917\u0940.")) },
        confirmButton = { TextButton(onClick = {
            disclosure = false
            val requested = mutableListOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG)
            if (android.os.Build.VERSION.SDK_INT >= 33) requested += Manifest.permission.POST_NOTIFICATIONS
            permissionLauncher.launch(requested.toTypedArray())
        }) { Text(UiCopy("Samajh gaya", "Continue", "आगे बढ़ें")) } },
        dismissButton = { TextButton(onClick = { disclosure = false }) { Text(UiCopy("Abhi nahi", "Not now", "अभी नहीं")) } }
    )
}

@Composable
private fun FloodTimeline(events: List<FloodEvent>, description: String, language: AppLanguage) {
    if (events.isEmpty()) {
        Box(Modifier.fillMaxWidth().height(88.dp).semantics { contentDescription = description }) {
            Text(ToneCopy("Pichhle ghante mein koi SMS ya call event nahi. Demo dekhne ke liye neeche buttons hain.", "No SMS or call events in the last hour. Use a demo below to see how detection works.", "पिछले घंटे में कोई SMS या कॉल इवेंट नहीं। डेमो देखने के लिए नीचे दिए बटन चुनें।").forLanguage(language))
        }
        return
    }
    val smsColor = MaterialTheme.colorScheme.onSurfaceVariant
    val callColor = MaterialTheme.colorScheme.onSurface
    val now = System.currentTimeMillis()
    Canvas(Modifier.fillMaxWidth().height(88.dp).semantics { contentDescription = description }) {
        val sms = events.count { it.kind == FloodKind.SMS }.coerceAtLeast(1)
        val calls = events.count { it.kind == FloodKind.CALL }.coerceAtLeast(1)
        val bar = size.width / 12f
        repeat(12) { index ->
            val inBin = events.filter { (now - it.timestampMillis).coerceAtLeast(0) / (5 * 60_000L) == (11 - index).toLong() }
            val smsCount = inBin.count { it.kind == FloodKind.SMS }.toFloat() / sms
            val callCount = inBin.count { it.kind == FloodKind.CALL }.toFloat() / calls
            if (smsCount > 0f) drawLine(smsColor, androidx.compose.ui.geometry.Offset(index * bar + bar * .32f, size.height), androidx.compose.ui.geometry.Offset(index * bar + bar * .32f, size.height * (1f - smsCount)), bar * .28f, StrokeCap.Round)
            if (callCount > 0f) drawLine(callColor, androidx.compose.ui.geometry.Offset(index * bar + bar * .70f, size.height), androidx.compose.ui.geometry.Offset(index * bar + bar * .70f, size.height * (1f - callCount)), bar * .28f, StrokeCap.Round)
        }
    }
}

@Composable
private fun FloodAlert(result: FloodResult, language: AppLanguage) {
    val context = LocalContext.current
    val outlinedColors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
    val textColors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
    val message = when {
        result.reason.name.startsWith("SMS") -> ToneCopy("Message flood: ${result.count} messages in this window", "Message flood: ${result.count} messages in this window", "संदेशों की बाढ़: इस समय में ${result.count} संदेश").forLanguage(language)
        else -> ToneCopy("Call flood: ${result.count} calls in this window", "Call flood: ${result.count} calls in this window", "कॉल की बाढ़: इस समय में ${result.count} कॉल").forLanguage(language)
    }
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(message, style = MaterialTheme.typography.titleMedium)
            Text(when (result.reason) {
                ai.trustshield.android.core.flood.FloodReason.SMS_BROAD -> ToneCopy("60 second mein ${result.count} messages aaye.", "${result.count} messages arrived within 60 seconds.", "60 सेकंड में ${result.count} संदेश आए।").forLanguage(language)
                ai.trustshield.android.core.flood.FloodReason.SMS_SAME_SENDER -> ToneCopy("Ek sender se ${result.count} messages 30 second mein aaye.", "${result.count} messages came from one sender within 30 seconds.", "एक प्रेषक से 30 सेकंड में ${result.count} संदेश आए।").forLanguage(language)
                ai.trustshield.android.core.flood.FloodReason.SMS_OTP_SERVICES -> ToneCopy("Alag services se kai code jaise messages aaye.", "Several code-like messages came from different services.", "अलग सेवाओं से कई कोड जैसे संदेश आए।").forLanguage(language)
                ai.trustshield.android.core.flood.FloodReason.CALL_REPEAT -> ToneCopy("Ek number se baar-baar missed ya rejected calls aaye.", "Repeated missed or rejected calls came from one number.", "एक नंबर से बार-बार मिस्ड या अस्वीकृत कॉल आईं।").forLanguage(language)
                ai.trustshield.android.core.flood.FloodReason.CALL_DISTRIBUTED -> ToneCopy("Kai alag numbers se ${result.count} calls aayi.", "${result.count} calls came from different numbers.", "कई अलग नंबरों से ${result.count} कॉल आईं।").forLanguage(language)
                ai.trustshield.android.core.flood.FloodReason.NONE -> ToneCopy("Koi alert nahi.", "No alert.", "कोई अलर्ट नहीं।").forLanguage(language)
            })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }
                }, modifier = Modifier.heightIn(min = 48.dp), colors = outlinedColors) { Text(ToneCopy("30 minute mute · system settings", "Mute for 30 min · settings", "30 मिनट म्यूट · सिस्टम सेटिंग्स").forLanguage(language)) }
                TextButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://cybercrime.gov.in"))) }
                }, modifier = Modifier.heightIn(min = 48.dp), colors = textColors) { Text(ToneCopy("Report", "Report", "रिपोर्ट").forLanguage(language)) }
            }
            Text(ToneCopy("Report: cybercrime.gov.in · helpline 1930 · TRAI DND 1909 · Sanchar Saathi", "Report: cybercrime.gov.in · helpline 1930 · TRAI DND 1909 · Sanchar Saathi", "रिपोर्ट: cybercrime.gov.in · हेल्पलाइन 1930 · TRAI DND 1909 · संचार साथी").forLanguage(language), style = MaterialTheme.typography.bodySmall)
        }
    }
}
