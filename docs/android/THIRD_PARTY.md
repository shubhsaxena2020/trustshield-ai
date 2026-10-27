# Android third-party notices

## Vosk Android and Hindi model

The app uses Vosk Android (`com.alphacephei:vosk-android:0.3.75`) and the Vosk `vosk-model-small-hi-0.22` Hindi speech model, copyright Alpha Cephei Inc. Both are distributed under the Apache License, Version 2.0. The Android artifact's transitive native dependency is JNA `5.18.1`; JNA is also Apache-2.0.

The Vosk model archive is fetched only by the documented Gradle preparation task and shipped in the APK. At runtime, the app copies the bundled model to its private files directory and removes the model/recognizer objects when the session stops. No audio file is created.

License text: https://www.apache.org/licenses/LICENSE-2.0.txt
Vosk source and attribution: https://github.com/alphacep/vosk-api
Hindi model source: https://alphacephei.com/vosk/models
Artifact and dependency metadata: https://central.sonatype.com/artifact/com.alphacephei/vosk-android/0.3.75
