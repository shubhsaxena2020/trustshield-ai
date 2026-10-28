package ai.trustshield.android.core.flood

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.CallLog
import android.telephony.PhoneStateListener
import android.telephony.SmsMessage
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import ai.trustshield.android.MainActivity
import ai.trustshield.android.R
import ai.trustshield.android.ui.navigation.AppRoute
import java.security.MessageDigest
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Android adapters. All retained data is content-free and confined to the active process. */
object BombShieldRuntime {
    private var detector = FloodDetector()
    private var profile = FloodProfile.NORMAL
    private val mutableEvents = MutableStateFlow<List<FloodEvent>>(emptyList())
    val events = mutableEvents.asStateFlow()

    fun isEnabled(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    @Synchronized fun useProfile(value: FloodProfile) {
        if (profile != value) { profile = value; detector = FloodDetector(value); mutableEvents.value = emptyList() }
    }

    @Synchronized fun reset() { detector.clear(); mutableEvents.value = emptyList() }

    @Synchronized fun snapshot(): FloodResult = detector.snapshot()

    @Synchronized fun accept(context: Context, event: FloodEvent, source: String): FloodResult {
        val safeEvent = event.copy(sourceKey = shortHash(source))
        val result = detector.record(safeEvent)
        mutableEvents.value = (mutableEvents.value + safeEvent).takeLast(FloodDetector.MAX_EVENTS)
        if (result.severity == FloodSeverity.FLOOD) BombFloodNotification.show(context, result)
        return result
    }

    fun shortHash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).take(6).joinToString("") { "%02x".format(it) }

    private const val PREFS = "bomb_shield"
    private const val KEY_ENABLED = "enabled"
}

class BombSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.provider.Telephony.SMS_RECEIVED" || !BombShieldRuntime.isEnabled(context)) return
        val extras = intent.extras ?: return
        val pdus = extras.get("pdus") as? Array<*> ?: return
        val messages = pdus.mapNotNull { raw ->
            val bytes = raw as? ByteArray ?: return@mapNotNull null
            parsePdu(bytes, extras.getString("format"))
        }
        if (messages.isEmpty()) return
        val sender = messages.first().originatingAddress.orEmpty()
        // Content is read transiently only for the OTP heuristic, then discarded.
        val body = messages.joinToString(separator = "") { it.messageBody.orEmpty() }
        val otp = isOtpLike(body)
        val now = System.currentTimeMillis()
        BombShieldRuntime.accept(context, FloodEvent(now, "", FloodKind.SMS, isOtpLike = otp), sender)
    }

    @Suppress("DEPRECATION")
    private fun parsePdu(bytes: ByteArray, format: String?): SmsMessage? =
        if (Build.VERSION.SDK_INT >= 23) SmsMessage.createFromPdu(bytes, format) else SmsMessage.createFromPdu(bytes)
}

/** Call-state watcher is registered only while Bomb Shield is open; it never polls or holds a wake lock. */
class BombCallMonitor(context: Context) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(TelephonyManager::class.java)
    private var ringingNumber: String? = null
    private var ringingAt = 0L
    private var callback: TelephonyCallback? = null
    @Suppress("DEPRECATION") private var legacy: PhoneStateListener? = null

    fun start() {
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.READ_PHONE_STATE) != android.content.pm.PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.READ_CALL_LOG) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        if (Build.VERSION.SDK_INT >= 31) {
            val listener = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) = onState(state, null)
            }
            callback = listener
            manager.registerTelephonyCallback(ContextCompat.getMainExecutor(app), listener)
        } else {
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                override fun onCallStateChanged(state: Int, phoneNumber: String?) = onState(state, phoneNumber)
            }
            legacy = listener
            @Suppress("DEPRECATION") manager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        }
    }

    fun stop() {
        callback?.let(manager::unregisterTelephonyCallback)
        callback = null
        @Suppress("DEPRECATION") legacy?.let { manager.listen(it, PhoneStateListener.LISTEN_NONE) }
        legacy = null
    }

    private fun onState(state: Int, phoneNumber: String?) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> { ringingNumber = phoneNumber; ringingAt = System.currentTimeMillis() }
            TelephonyManager.CALL_STATE_IDLE -> {
                val start = ringingAt
                if (start > 0) {
                    val row = runCatching {
                        app.contentResolver.query(
                            CallLog.Calls.CONTENT_URI,
                            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE),
                            "${CallLog.Calls.DATE} >= ?",
                            arrayOf(start.toString()),
                            "${CallLog.Calls.DATE} DESC"
                        )?.use { cursor ->
                            if (!cursor.moveToFirst()) null else cursor.getString(0) to cursor.getInt(1)
                        }
                    }.getOrNull()
                    val number = row?.first ?: ringingNumber
                    val outcome = when (row?.second) {
                        CallLog.Calls.MISSED_TYPE -> CallOutcome.MISSED
                        CallLog.Calls.REJECTED_TYPE -> CallOutcome.REJECTED
                        else -> CallOutcome.ANSWERED
                    }
                    if (!number.isNullOrBlank()) BombShieldRuntime.accept(app, FloodEvent(System.currentTimeMillis(), "", FloodKind.CALL, outcome), number)
                }
                ringingAt = 0L
                ringingNumber = null
            }
        }
    }
}

private object BombFloodNotification {
    private const val CHANNEL = "bomb_shield_alerts"
    private const val NOTIFICATION_ID = 1108

    fun show(context: Context, result: FloodResult) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Bomb Shield alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Alerts for rapid bursts of messages or calls"
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
        )
        val sms = result.reason.name.startsWith("SMS")
        val title = if (sms) "Message flood: ${result.count} messages in 60 s" else "Call flood: ${result.count} calls in 5 min"
        val open = PendingIntent.getActivity(context, 1, Intent(context, MainActivity::class.java)
            .putExtra(AppRoute.EXTRA_ROUTE, AppRoute.BOMB_SHIELD.wireName), flags())
        val mute = PendingIntent.getActivity(context, 2, Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName), flags())
        val report = PendingIntent.getActivity(context, 3, Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://cybercrime.gov.in")), flags())
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(result.plainLanguageReason)
            .setStyle(Notification.BigTextStyle().bigText(result.plainLanguageReason))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setPriority(Notification.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Open Bomb Shield", open).build())
            .addAction(Notification.Action.Builder(null, "Mute for 30 min", mute).build())
            .addAction(Notification.Action.Builder(null, "Report", report).build())
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun flags() = PendingIntent.FLAG_UPDATE_CURRENT or
        (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
}
