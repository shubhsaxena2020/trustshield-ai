package ai.trustshield.android.input.qr

import ai.trustshield.android.ui.qr.QrHostCopy
import ai.trustshield.android.ui.qr.QrLanguage
import ai.trustshield.android.ui.qr.text
import ai.trustshield.android.ui.theme.ToneCopy
import androidx.camera.core.CameraUnavailableException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The busy-camera fallback, pinned as a JVM contract.
 *
 * CameraX reports "another session owns the lens" two ways — [CameraUnavailableException.CAMERA_IN_USE]
 * (one other client) and [CameraUnavailableException.CAMERA_MAX_IN_USE] (the maximum number of clients
 * already attached). Both mean exactly the same thing to a user, so both must collapse onto one
 * recoverable state ([CameraXQrScanner.FAULT_CAMERA_BUSY]) with one recovery story, and neither may
 * swallow any other fault.
 *
 * Everything under test is a pure function over plain Java exceptions and plain Kotlin data:
 * `CameraFaultMapper.token(Throwable)` (CameraXBarcodeDecoder.kt) and `QrHostCopy.faultText(String)`
 * (QrGuardHost.kt). No CameraX provider, `ProcessCameraProvider`, `Preview`/`ImageAnalysis` use case,
 * lifecycle owner, `Context` or device is touched here — constructing
 * [CameraUnavailableException] only allocates an `Exception` with an int reason, which the existing
 * `CameraFaultMapperTest` already proves works on a plain JVM. That is deliberate:
 * this test must pass with no camera on the machine and no phone in hand.
 */
class CameraBusyFallbackTest {

    // ------------------------------------------------------------------ helpers ---------------------------------

    /** A wrapped busy failure, shaped like the `bindToLifecycle` failure path. */
    private fun wrappedBusy(reason: Int): IllegalStateException =
        IllegalStateException("bind failed", CameraUnavailableException(reason))

    private fun busyCopy(): ToneCopy = QrHostCopy.faultText(CameraXQrScanner.FAULT_CAMERA_BUSY)

    /** Assert a token maps to a copy that cannot be mistaken for the busy recovery story. */
    private fun assertNotTheBusyStory(label: String, token: String) {
        val copy = QrHostCopy.faultText(token)
        assertTrue("$label ($token) must not reuse the busy copy: $copy", copy != busyCopy())
    }

    // ------------------------------------------------- 1. one state for both busy reasons -------------------------

    @Test
    fun bothBusyReasonsCollapseToOneRecoverableBusyState() {
        val inUse = CameraFaultMapper.token(
            CameraUnavailableException(CameraUnavailableException.CAMERA_IN_USE),
        )
        val maxInUse = CameraFaultMapper.token(
            CameraUnavailableException(CameraUnavailableException.CAMERA_MAX_IN_USE),
        )
        val wrappedInUse = CameraFaultMapper.token(wrappedBusy(CameraUnavailableException.CAMERA_IN_USE))
        val wrappedMaxInUse = CameraFaultMapper.token(
            wrappedBusy(CameraUnavailableException.CAMERA_MAX_IN_USE),
        )
        // Two levels deep: the mapper walks `cause`, so a re-wrapped bind failure must still be busy.
        val inner = IllegalStateException("bind failed", CameraUnavailableException(CameraUnavailableException.CAMERA_IN_USE))
        val doubleWrapped = CameraFaultMapper.token(IllegalStateException("bind failed", inner))

        assertEquals(CameraXQrScanner.FAULT_CAMERA_BUSY, inUse)
        assertEquals(CameraXQrScanner.FAULT_CAMERA_BUSY, maxInUse)
        assertEquals(CameraXQrScanner.FAULT_CAMERA_BUSY, wrappedInUse)
        assertEquals(CameraXQrScanner.FAULT_CAMERA_BUSY, wrappedMaxInUse)
        assertEquals(CameraXQrScanner.FAULT_CAMERA_BUSY, doubleWrapped)

        // The same token, whichever way the two busy reasons arrive: one recovery story, not two.
        assertEquals("CAMERA_IN_USE and CAMERA_MAX_IN_USE must share one token", inUse, maxInUse)
        assertEquals(inUse, wrappedInUse)
        assertEquals(inUse, wrappedMaxInUse)
        assertEquals(inUse, doubleWrapped)

        // ... and therefore one identical copy in both languages.
        val directCopy = QrHostCopy.faultText(inUse)
        assertEquals(directCopy, QrHostCopy.faultText(maxInUse))
        assertEquals(directCopy, QrHostCopy.faultText(wrappedInUse))
        assertEquals(directCopy, QrHostCopy.faultText(wrappedMaxInUse))
        assertEquals(directCopy, QrHostCopy.faultText(doubleWrapped))
        assertEquals(directCopy.english, QrHostCopy.faultText(wrappedMaxInUse).text(QrLanguage.ENGLISH))
        assertEquals(directCopy.hinglish, QrHostCopy.faultText(wrappedInUse).text(QrLanguage.HINGLISH))
    }

    // ------------------------------------- 2. the copy is clear and offers the demo ------------------------------

    @Test
    fun busyCopyExplainsRecoveryAndKeepsDemoAvailable() {
        val copy = busyCopy()

        // Verified verbatim against QrGuardHost.kt `faultCameraBusy` (English half).
        val recoverySentence =
            "Another app is using the camera. Close it and try again, or run the QR Guard demo."
        assertTrue(
            "busy English copy must carry the full recovery sentence, was: ${copy.english}",
            copy.english.contains(recoverySentence),
        )

        // Verified verbatim against QrGuardHost.kt `faultCameraBusy` (Hinglish half).
        assertTrue(
            "busy Hinglish copy must name the cause, the close-and-retry route and the demo",
            copy.hinglish.contains("Camera kisi aur app mein chal raha hai") &&
                copy.hinglish.contains("band karke dobara try karein") &&
                copy.hinglish.contains("QR Guard ka demo"),
        )

        assertTrue("busy English copy must not be blank", copy.english.isNotBlank())
        assertTrue("busy Hinglish copy must not be blank", copy.hinglish.isNotBlank())
        assertTrue("Hinglish and English halves must differ", copy.hinglish != copy.english)

        // The toggle really selects the halves (no English leaking into the Hinglish default).
        assertEquals(copy.hinglish, copy.text(QrLanguage.HINGLISH))
        assertEquals(copy.english, copy.text(QrLanguage.ENGLISH))
        assertTrue(copy.text(QrLanguage.HINGLISH).isNotBlank())
        assertTrue(copy.text(QrLanguage.ENGLISH).isNotBlank())
    }

    // ----------------------------------- 3. unrelated faults keep their own copy --------------------------------

    @Test
    fun busyFallbackDoesNotSwallowOtherFaults() {
        // A disabled camera and a busy-looking message with no CameraUnavailableException are bind failures.
        assertEquals(
            CameraXQrScanner.FAULT_BIND_FAILED,
            CameraFaultMapper.token(CameraUnavailableException(CameraUnavailableException.CAMERA_DISABLED)),
        )
        assertEquals(
            CameraXQrScanner.FAULT_BIND_FAILED,
            CameraFaultMapper.token(wrappedBusy(CameraUnavailableException.CAMERA_DISABLED)),
        )
        assertEquals(
            CameraXQrScanner.FAULT_BIND_FAILED,
            CameraFaultMapper.token(IllegalStateException("busy?")),
        )

        val generic = QrHostCopy.faultText(CameraXQrScanner.FAULT_BIND_FAILED)
        val frame = QrHostCopy.faultText(CameraXQrScanner.FAULT_FRAME_PIPELINE)
        val emulator = QrHostCopy.faultText(CameraXQrScanner.FAULT_EMULATOR_CAMERA_DISABLED)
        val unknown = QrHostCopy.faultText("camera-unknown-token")

        // Bind failures carry the generic "camera could not start" copy, not the busy story.
        assertEquals(generic, QrHostCopy.faultText(CameraFaultMapper.token(IllegalStateException("busy?"))))
        assertEquals(
            generic,
            QrHostCopy.faultText(
                CameraFaultMapper.token(CameraUnavailableException(CameraUnavailableException.CAMERA_DISABLED)),
            ),
        )

        // Frame pipeline and emulator-disabled keep their own words, distinct from each other and generic.
        assertTrue("frame copy must be its own text", frame != generic && frame != emulator)
        assertTrue("emulator copy must be its own text", emulator != generic && emulator != frame)

        // An unknown token never echoes the token and falls back to the generic copy.
        assertEquals(generic, unknown)
        assertTrue(
            "unknown token must not be echoed to the user: $unknown",
            !unknown.english.contains("camera-unknown-token") && !unknown.hinglish.contains("camera-unknown-token"),
        )

        // Nothing that is not busy may render the busy recovery story.
        assertNotTheBusyStory("bind failed", CameraXQrScanner.FAULT_BIND_FAILED)
        assertNotTheBusyStory("frame pipeline", CameraXQrScanner.FAULT_FRAME_PIPELINE)
        assertNotTheBusyStory("emulator camera off", CameraXQrScanner.FAULT_EMULATOR_CAMERA_DISABLED)
        assertNotTheBusyStory("unknown token", "camera-unknown-token")
        assertNotTheBusyStory(
            "provider unavailable",
            CameraXQrScanner.FAULT_PROVIDER_UNAVAILABLE,
        )
        assertNotTheBusyStory(
            "permission revoked",
            CameraXQrScanner.FAULT_PERMISSION_REVOKED,
        )

        // And the busy copy is not the generic one either — the fix added a story, it did not rename one.
        assertTrue("busy copy must differ from the generic copy", busyCopy() != generic)
    }

    // --------------------------------------- 4. the fallback path is hardware-free -------------------------------

    @Test
    fun busyFallbackIsAPureFunctionThatCannotStartACameraSession() {
        // Same instance twice -> same token (no hidden state, no first-call side effect).
        val busy = CameraUnavailableException(CameraUnavailableException.CAMERA_IN_USE)
        assertEquals(CameraFaultMapper.token(busy), CameraFaultMapper.token(busy))

        // A freshly constructed, structurally equal exception -> the same token.
        assertEquals(
            CameraFaultMapper.token(CameraUnavailableException(CameraUnavailableException.CAMERA_IN_USE)),
            CameraFaultMapper.token(CameraUnavailableException(CameraUnavailableException.CAMERA_IN_USE)),
        )
        assertEquals(
            CameraFaultMapper.token(wrappedBusy(CameraUnavailableException.CAMERA_MAX_IN_USE)),
            CameraFaultMapper.token(wrappedBusy(CameraUnavailableException.CAMERA_MAX_IN_USE)),
        )

        // An exception with no reason at all (no CameraUnavailableException anywhere in the chain) is a
        // bind failure, never busy: busy is a decision about a specific reason code, not a guess.
        assertEquals(
            CameraXQrScanner.FAULT_BIND_FAILED,
            CameraFaultMapper.token(RuntimeException()),
        )
        assertEquals(
            CameraXQrScanner.FAULT_BIND_FAILED,
            CameraFaultMapper.token(IllegalStateException("bind failed", RuntimeException())),
        )

        // Copy is a function of the token string alone: no Context, no camera, no session. Asking for a
        // fault's copy twice cannot start anything and cannot drift.
        val token = CameraFaultMapper.token(busy)
        assertEquals(QrHostCopy.faultText(token), QrHostCopy.faultText(token))
        assertEquals(QrHostCopy.faultText(token).english, QrHostCopy.faultText(token).english)

        // Pin the signature: every `faultText` overload takes exactly one String. If someone ever threads
        // a Context or a CameraX handle in here, presenting copy would become able to open a session.
        val faultTextMethods = QrHostCopy::class.java.declaredMethods.filter { it.name.startsWith("faultText") }
        assertTrue("QrHostCopy must expose faultText", faultTextMethods.isNotEmpty())
        faultTextMethods.forEach { method ->
            assertTrue(
                "faultText must take exactly one argument (the token), was: $method",
                method.parameterTypes.size == 1,
            )
            assertTrue(
                "faultText must take a String token and nothing camera-shaped, was: $method",
                method.parameterTypes[0] == String::class.java,
            )
        }
    }
}
