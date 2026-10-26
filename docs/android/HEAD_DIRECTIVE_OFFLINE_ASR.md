# HEAD DIRECTIVE P0: Call Shield must understand Hindi speech OFFLINE

Issued 27 Oct 2026 00:20 IST by the head after a real-phone test (Redmi Note 5 Pro, Android 9, airplane mode).

## Evidence
With airplane mode on, Call Shield live mic fails. Google recognizer log on the phone: "Failed to get language pack of required locale: error" then "#onError". Cause: no offline Hindi language pack is installed in the Google app, and Android SpeechRecognizer cannot be forced offline (EXTRA_PREFER_OFFLINE is only a hint). A demo, or a user with no signal, therefore gets no transcript. The product claims on-device privacy, so this must work without the network.

## Required (package P5-ASR, run as nine-task waves)
1. Bundle an on-device offline recognizer as the automatic fallback: Vosk Android (`com.alphacephei:vosk-android`, Apache-2.0) with the small Hindi model (`vosk-model-small-hi-0.22`, about 42 MB). The model ships in the APK assets and is unpacked to app files on first use; it is never downloaded at runtime. Verify license text, attribute it in the app licenses/About screen, and record the APK size delta (arm64 release APK should stay under about 80 MB; if size forces it, ship the model only in the arm64 and universal splits).
2. New `OfflineSpeechEngine` behind the existing speech session interface (CallSpeechSession / SpeechRecognitionMode): feed AudioRecord (16 kHz mono PCM) to Vosk, emit partial and final text through the same event stream the normaliser and playbook already consume. Vosk Hindi emits Devanagari, so add Devanagari digital-arrest / OTP / 1930 phrase fixtures and make sure the 5-stage playbook fires on Devanagari transcripts.
3. Policy: if the network is unreachable, OR the Google offline pack is missing, OR the Google recognizer errors twice, switch to the offline engine automatically and show a small clear status ("Offline mode: on-device recognizer"). Never leave the session dead (see issue 146: restart on NO_MATCH / SPEECH_TIMEOUT in under 1 s, no 47 s gap).
4. Privacy rules unchanged: no raw audio stored, transcript only in session memory, wiped on Stop.
5. Tests: JVM tests with a fake PCM source; a synthetic Hindi WAV fixture run through the engine on the emulator. The head then runs the live test on the real phone in airplane mode (serial 63b0de52, the head owns it).
6. CPU and battery: single decoding thread, release the model on Stop. The head measures on the phone.

Acceptance: airplane mode on, the three digital-arrest sentences spoken in Hindi/Hinglish produce a transcript and the meter reaches "Dhyan dein" or higher; a 3-minute session with silences stays alive.
