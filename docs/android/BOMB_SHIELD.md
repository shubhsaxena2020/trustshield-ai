# Bomb Shield

Bomb Shield is an opt-in, on-device detector for sudden SMS/OTP floods and repeated-call floods. It is off by default. The Home and Settings entries open the secondary Bomb Shield screen; existing five-tab navigation stays unchanged.

## Detection

The pure Kotlin `core.flood.FloodDetector` accepts timestamps, a temporary source key, event kind, call outcome and an OTP-like Boolean. It never accepts or stores SMS text. `isOtpLike` derives the Boolean from OTP/code keywords and digit-code patterns; the receiver drops the joined SMS body immediately after checking it. Android adapters hash sender/caller identifiers with SHA-256 and retain only the first 12 hex characters for grouping in the process-local window.

Normal profile FLOOD thresholds:

- 8 SMS from any senders in 60 seconds.
- 5 SMS from one sender in 30 seconds.
- 6 OTP-like SMS from at least 3 services in 120 seconds.
- 3 calls from one number in 5 minutes when earlier calls were missed or rejected.
- 4 calls from different numbers in 5 minutes.

Strict profile lowers those thresholds to 6, 4, 4 from 2 services, 2 repeat calls and 3 different callers. WATCH begins near each threshold. Events are bounded to 256 in memory and expire after one hour; detection evaluates only its specified sliding windows. Older out-of-order timestamps are ignored so a clock rollback does not rewind the active window.

## Permissions and limits

The enable flow first explains the data use, then requests `RECEIVE_SMS`, `READ_PHONE_STATE`, `READ_CALL_LOG` and, on Android 13+, `POST_NOTIFICATIONS`. SMS body is inspected only transiently for the OTP-like Boolean. Sender/caller hashes, event kinds, outcomes and timestamps stay in memory; nothing is uploaded or persisted. The SMS receiver is dormant unless the opt-in preference is true. Call state is observed with `TelephonyCallback` on API 31+ and `PhoneStateListener` below API 31 while the Bomb Shield screen is open; call-log lookup supplies the recent outcome. No polling, wake lock or foreground service is used. Consequently, call monitoring stops when this screen closes and does not claim background call protection.

On FLOOD, a high-importance notification offers Bomb Shield, system app-notification settings labelled "Mute for 30 min", and the cybercrime.gov.in report page. Android does not let this app block numbers or silence other apps without default-dialer or policy access; the notification action opens system settings and does not claim to mute other apps. In-app guidance also lists helpline 1930, TRAI DND 1909 and Sanchar Saathi.

Demo buttons feed synthetic SMS/call events into the same detector and shared alert renderer. Demo requires no runtime permission. Demo events are clearly labelled and remain in memory.
