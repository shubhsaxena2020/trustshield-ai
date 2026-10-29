import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseSigningProperties = Properties()
rootProject.file("keystore/release.properties").takeIf { it.isFile }?.inputStream()?.use {
    releaseSigningProperties.load(it)
}

android {
    namespace = "ai.trustshield.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.trustshield.android"
        minSdk = 23
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86") }
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/${releaseSigningProperties.getProperty("storeFile", "trustshield-release.jks")}")
            storePassword = releaseSigningProperties.getProperty("storePassword")
            keyAlias = releaseSigningProperties.getProperty("keyAlias")
            keyPassword = releaseSigningProperties.getProperty("keyPassword")
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            // R8 minification is OFF on purpose: with it on, the release build rendered a blank screen on
            // Android 9 (API 28) because Compose text layout referenced android.text.Layout$TextInclusionStrategy
            // (API 35) unguarded after optimisation (NoClassDefFoundError, found on a real Redmi Note 5 Pro, not
            // reproducible on the API 36 emulator). Re-enable only together with a test on an API 26-28 device.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging { jniLibs.useLegacyPackaging = true }
}

// The 42 MB model is intentionally kept out of Git. This task fetches the pinned Apache-2.0
// model into the ignored assets directory before packaging; recognition never downloads it.
val prepareVoskHindiModel by tasks.registering {
    val modelDir = layout.projectDirectory.dir("src/main/assets/model-hi").asFile
    outputs.dir(modelDir)
    doLast {
        if (File(modelDir, "am/final.mdl").isFile) return@doLast
        val archive = File(temporaryDir, "vosk-model-small-hi-0.22.zip")
        val expectedSha256 = "7c50a10866889f0ac21d912c20537a055a597ed09fc1d3e5bcd798f9f0017e48"
        val localZip = providers.gradleProperty("voskModelZip").orNull?.let(::File)
        if (localZip != null) {
            check(localZip.isFile) { "voskModelZip does not exist: ${localZip.absolutePath}" }
            localZip.copyTo(archive, overwrite = true)
        } else if (!archive.isFile || sha256(archive) != expectedSha256) {
            val partial = File(temporaryDir, "vosk-model-small-hi-0.22.zip.part")
            var failure: Exception? = null
            for (attempt in 0 until 5) {
                try {
                    val offset = partial.takeIf(File::isFile)?.length() ?: 0L
                    val connection = URL("https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip")
                        .openConnection() as HttpURLConnection
                    connection.connectTimeout = 30_000
                    connection.readTimeout = 90_000
                    if (offset > 0L) connection.setRequestProperty("Range", "bytes=$offset-")
                    connection.connect()
                    val append = offset > 0L && connection.responseCode == HttpURLConnection.HTTP_PARTIAL
                    connection.inputStream.use { input ->
                        val output = if (append) FileOutputStream(partial, true) else partial.outputStream()
                        output.use { input.copyTo(it) }
                    }
                    connection.disconnect()
                    partial.copyTo(archive, overwrite = true)
                    if (sha256(archive) != expectedSha256) {
                        partial.delete()
                        archive.delete()
                        error("Downloaded Vosk model checksum did not match the pinned SHA-256")
                    }
                    partial.delete()
                    failure = null
                    break
                } catch (error: Exception) {
                    failure = error
                    if (attempt < 4) Thread.sleep(1_000L shl attempt)
                }
            }
            check(failure == null) {
                "Could not download Vosk Hindi model after 5 attempts. Retry with network, or pass -PvoskModelZip=<path to the verified zip>. Last error: ${failure?.message}"
            }
        }
        val digest = sha256(archive)
        check(digest == expectedSha256) { "Vosk Hindi model SHA-256 mismatch: $digest" }
        modelDir.deleteRecursively()
        modelDir.mkdirs()
        ZipFile(archive).use { zip ->
            val prefix = "vosk-model-small-hi-0.22/"
            zip.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
                require(entry.name.startsWith(prefix) && !entry.name.contains(".."))
                val target = File(modelDir, entry.name.removePrefix(prefix))
                target.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input -> target.outputStream().use { output -> input.copyTo(output) } }
            }
        }
        check(File(modelDir, "am/final.mdl").isFile) { "Vosk Hindi model archive is incomplete" }
    }
}

fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
    .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
tasks.named("preBuild").configure { dependsOn(prepareVoskHindiModel) }

dependencies {
    implementation("com.alphacephei:vosk-android:0.3.75")
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-devanagari:16.0.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
