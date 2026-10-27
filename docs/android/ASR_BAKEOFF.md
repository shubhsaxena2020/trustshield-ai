# Offline Hindi ASR bake-off

Decision for issue #148: **Vosk Android with `vosk-model-small-hi-0.22`**. The downloaded archive is 44,458,845 bytes (SHA-256 `7c50a10866889f0ac21d912c20537a055a597ed09fc1d3e5bcd798f9f0017e48`); extracted assets are about 82 MB. The model is bundled in the APK and copied to app-private files on first use. No model or audio download occurs at runtime. Build with `:app:prepareVoskHindiModel` (also a `preBuild` dependency); it downloads from the official Vosk model host, verifies the pinned SHA-256, and extracts to `apps/android/app/src/main/assets/model-hi/`. The extracted directory is ignored by Git because this repo has no LFS attributes configured.

| Candidate | Model size | Runtime / latency | License | Decision |
|---|---:|---|---|---|
| Vosk small Hindi 0.22 | 42 MB archive; 82 MB extracted | Designed for mobile; Redmi Note 5 Pro chunk latency not measured yet | Apache-2.0 | Selected: maintained Android AAR, official Hindi model, smallest mobile-ready option |
| Vosk full Hindi 0.22 | 1.5 GB | Server model; unsuitable for APK and target phone | Apache-2.0 | Rejected for size/RAM |
| sherpa-onnx + Whisper tiny multilingual, chunked | Whisper tiny weights about 75 MB fp16 / 150 MB fp32, plus runtime and Android native libraries | Chunked mode adds latency; target phone timing not measured | sherpa-onnx Apache-2.0; Whisper MIT | Not selected: larger runtime stack and no demonstrated Redmi latency |

The comparison is a packaging and integration decision, not an accuracy claim. Vosk's published Hindi WER is 20.89 on IITM and 24.72 on MUCS; no matched digital-arrest/Hinglish audio bake-off or target-device latency result is available in this implementation. Measure the Redmi before making a latency claim. Model listing: https://alphacephei.com/vosk/models. Vosk Android artifact: `com.alphacephei:vosk-android:0.3.75`; Maven Central POM pins JNA `5.18.1`. ABI filters keep arm64-v8a, armeabi-v7a, and x86_64 only.

APK size measurement: the final arm64 debug APK is 86,494,510 bytes. Its 14 bundled model assets account for 46,121,216 compressed bytes. It exceeds the ~80 MB target even after ABI filtering, so keep this warning visible. The release APK was not produced because `apps/android/keystore/trustshield-release.jks` is absent; signing configuration is required by this checkout.
