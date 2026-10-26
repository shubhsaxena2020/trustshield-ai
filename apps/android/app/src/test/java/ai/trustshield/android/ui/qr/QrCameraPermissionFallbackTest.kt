package ai.trustshield.android.ui.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Camera-permission fallback contract for QR Guard (P4-fallbacks).
 *
 * Plain JVM, JUnit 4 only: `QrPermissionPolicy`, `QrPermissionUi`, `QrPermissionFallback` and
 * `QrHostCopy` are all dependency-free data/copy, and `QrReducer` is the pure C06 reducer. Nothing
 * here instantiates an `android.*` type, touches Compose, requests a permission or opens a camera —
 * the point of the seams is that "permission refused still leaves a labelled way forward" is a
 * testable claim rather than a screenshot.
 *
 * The fixtures are the repo's own synthetic ids (`QrGuardScreenTest`'s shop handle); no real payee
 * data appears anywhere in this file, and no copy assertion reveals a UPI id.
 *
 * NOTE (deliberate, reported): `QrHostCopy.simulationHeading` is the same string in Hinglish and
 * English ("Simulate scan (demo)"), so it cannot be pinned as "English != Hinglish". The badge and
 * the TalkBack description do differ; that difference is what test 4 pins.
 */
class QrCameraPermissionFallbackTest {

    /** The repo's synthetic shop handle (same fixture `QrGuardScreenTest` uses). Never a real payee. */
    private val shopUpi = "dukaan.ravi@okaxis"
    private val shopName = "Sharma Kirana"

    /** A different plausible handle: what a swapped sticker would decode to. Synthetic. */
    private val strangerUpi = "9876543210@ybl"

    private val policy: QrPolicy = QrPolicy.withPin("1234")

    /** Every permission state, so the matrix can be asserted without repeating lists by hand. */
    private val allStates = QrPermissionState.entries.toList()

    // ------------------------------------------------------------------------------- fixtures ----

    /**
     * A live session built exactly the way the host builds it: permission granted, the real QR
     * registered, then StartWatch. `RegisteredPayee(handle, name)` is the production constructor
     * (verified in `QrUiState.kt`), so registering through the reducer also proves it is reached.
     */
    private fun watchingState(): QrUiState {
        var s = QrReducer.reduce(QrUiState(), QrEvent.PermissionGranted, policy)
        s = QrReducer.reduce(s, QrEvent.RegisterPayee(shopUpi, shopName), policy)
        return QrReducer.reduce(s, QrEvent.StartWatch(atMs = 0L), policy)
    }

    private fun QrUiState.act(event: QrEvent): QrUiState = QrReducer.reduce(this, event, policy)

    private fun QrPermissionUi.pair(): Pair<QrPermissionState, QrPermissionAction> = state to action

    // ------------------------------------------- 1. denial is not "no camera hardware" ----------

    /**
     * A refused permission and a phone without a lens are different facts and must produce different
     * states — telling someone to grant a permission for a camera that does not exist is a lie.
     *
     * The "can we still ask?" fact is `hasAsked`/`showRationale` together: a single boolean cannot
     * separate "never asked" from "don't ask again", which is why the policy takes both.
     */
    @Test
    fun denialIsNotConflatedWithADeviceWithoutACamera() {
        // Never asked yet: Android would still show the dialog.
        val firstAsk = QrPermissionPolicy.decide(
            granted = false, hasAsked = false, showRationale = false, hasCamera = true
        )
        assertEquals(QrPermissionState.CAN_ASK, firstAsk.state)
        assertEquals(QrPermissionAction.ASK, firstAsk.action)
        assertFalse("nothing may use the camera without a grant", firstAsk.canUseCamera)

        // Asked once and still eligible for the rationale: still askable.
        val rationaleShown = QrPermissionPolicy.decide(
            granted = false, hasAsked = true, showRationale = true, hasCamera = true
        )
        assertEquals(QrPermissionState.CAN_ASK, rationaleShown.state)
        assertEquals(QrPermissionAction.ASK, rationaleShown.action)
        assertFalse(rationaleShown.canUseCamera)

        // Asked, refused, and the dialog is now suppressed: only Settings can recover it.
        val refused = QrPermissionPolicy.decide(
            granted = false, hasAsked = true, showRationale = false, hasCamera = true
        )
        assertEquals(QrPermissionState.PERMANENTLY_DENIED, refused.state)
        assertEquals(QrPermissionAction.OPEN_SETTINGS, refused.action)
        assertFalse(refused.canUseCamera)

        // No lens at all: a hardware fact, not a permission state.
        val noLens = QrPermissionPolicy.decide(
            granted = false, hasAsked = true, showRationale = false, hasCamera = false
        )
        assertEquals(QrPermissionState.NO_CAMERA, noLens.state)
        assertEquals(QrPermissionAction.MANUAL_ENTRY_ONLY, noLens.action)
        assertFalse(noLens.canUseCamera)

        // Same refusal facts, opposite hardware fact: the two states must not collapse.
        assertNotEquals(
            "denied permission must not be reported as a missing camera",
            noLens.state,
            refused.state
        )

        // A stale rationale flag must never hide a working camera.
        val granted = QrPermissionPolicy.decide(
            granted = true, hasAsked = true, showRationale = true, hasCamera = true
        )
        assertEquals(QrPermissionState.GRANTED, granted.state)
        assertEquals(QrPermissionAction.NONE, granted.action)
        assertTrue("a granted permission is the only usable camera", granted.canUseCamera)

        // canUseCamera is false for every non-granted combination of the four facts.
        for (hasAsked in listOf(false, true)) {
            for (showRationale in listOf(false, true)) {
                for (hasCamera in listOf(false, true)) {
                    val ui = QrPermissionPolicy.decide(
                        granted = false,
                        hasAsked = hasAsked,
                        showRationale = showRationale,
                        hasCamera = hasCamera
                    )
                    assertFalse(
                        "canUseCamera must be false for hasAsked=$hasAsked " +
                            "showRationale=$showRationale hasCamera=$hasCamera",
                        ui.canUseCamera
                    )
                }
            }
        }
    }

    // ------------------------------------ 2. the labelled simulate path survives every denial ---

    /**
     * Every state that cannot use the camera still offers the labelled demo control, and the state
     * that can use the camera offers nothing at all (there is nothing to fall back from).
     */
    @Test
    fun denialStillOffersTheLabelledSimulation() {
        for (state in listOf(
            QrPermissionState.CAN_ASK,
            QrPermissionState.PERMANENTLY_DENIED,
            QrPermissionState.NO_CAMERA
        )) {
            val fallback = QrHostCopy.permissionFallback(state)
            assertTrue(
                "state $state must keep a demo path without a camera",
                fallback.offersSimulation
            )
            assertTrue(
                "state $state must offer SIMULATE_SCAN explicitly",
                fallback.actions.contains(QrFallbackAction.SIMULATE_SCAN)
            )
        }

        val grantedFallback = QrHostCopy.permissionFallback(QrPermissionState.GRANTED)
        assertFalse(
            "a working camera has nothing to fall back from",
            grantedFallback.offersSimulation
        )
        assertTrue(
            "GRANTED must not offer fallback routes",
            grantedFallback.actions.isEmpty()
        )

        // The help line is real copy in both languages, and it is genuinely translated.
        for (state in allStates) {
            val help = QrHostCopy.permissionFallback(state).help
            assertTrue("help.hinglish must not be blank for $state", help.hinglish.isNotBlank())
            assertTrue("help.english must not be blank for $state", help.english.isNotBlank())
            assertTrue(
                "help copy for $state must survive text(HINGLISH)",
                help.text(QrLanguage.HINGLISH).isNotBlank()
            )
            assertTrue(
                "help copy for $state must survive text(ENGLISH)",
                help.text(QrLanguage.ENGLISH).isNotBlank()
            )
            assertNotEquals(
                "help copy for $state must actually be translated",
                help.hinglish,
                help.english
            )
        }
    }

    // ------------------------------------------------- 3. offered routes match the state -------

    /**
     * The routes are not a flat menu: each state offers the recovery that Android will actually
     * honour, plus the camera-free routes. `REQUEST_PERMISSION` is the only route that starts a
     * camera flow, so it may appear only where the dialog would still be shown.
     */
    @Test
    fun offeredRoutesMatchTheState() {
        val askable = QrHostCopy.permissionFallback(QrPermissionState.CAN_ASK)
        assertTrue(
            "CAN_ASK must offer the system prompt",
            askable.actions.contains(QrFallbackAction.REQUEST_PERMISSION)
        )

        val refused = QrHostCopy.permissionFallback(QrPermissionState.PERMANENTLY_DENIED)
        assertTrue(
            "PERMANENTLY_DENIED must offer the app settings page",
            refused.actions.contains(QrFallbackAction.OPEN_SETTINGS)
        )
        assertFalse(
            "a suppressed dialog cannot be re-asked; it must not be offered",
            refused.actions.contains(QrFallbackAction.REQUEST_PERMISSION)
        )

        val noLens = QrHostCopy.permissionFallback(QrPermissionState.NO_CAMERA)
        assertTrue(
            "NO_CAMERA must offer manual UPI-id entry",
            noLens.actions.contains(QrFallbackAction.MANUAL_ENTRY)
        )
        assertFalse(
            "there is no camera to ask about",
            noLens.actions.contains(QrFallbackAction.REQUEST_PERMISSION)
        )

        // The contract across the whole matrix: no state offers a camera-opening route while the
        // camera is unusable, and a granted state offers no route at all.
        for (state in allStates) {
            val fallback = QrHostCopy.permissionFallback(state)
            val offersPrompt = fallback.actions.contains(QrFallbackAction.REQUEST_PERMISSION)
            if (state == QrPermissionState.CAN_ASK) {
                assertTrue("only CAN_ASK may (re)launch the system prompt", offersPrompt)
            } else {
                assertFalse(
                    "state $state must not launch a permission prompt",
                    offersPrompt
                )
            }
            if (state == QrPermissionState.GRANTED) {
                assertTrue("GRANTED offers no fallback", fallback.actions.isEmpty())
            } else {
                assertTrue(
                    "a non-granted state must still offer at least one way forward",
                    fallback.actions.isNotEmpty()
                )
                // canUseCamera derives from the state alone, so a non-granted state can never
                // claim a usable camera whichever action a caller pairs with it.
                for (action in QrPermissionAction.entries) {
                    assertFalse(
                        "state $state with action $action must not claim a usable camera",
                        QrPermissionUi(state, action).canUseCamera
                    )
                }
            }
        }
    }

    // ------------------------------------------------ 4. the demo is labelled as not live ------

    /**
     * A demo that looked like live decoding would be the exact mislabeling this app must not do, so
     * the label is asserted verbatim (the substring is copied from `QrHostCopy`).
     */
    @Test
    fun simulationIsLabelledAsNotLive() {
        val heading = QrHostCopy.simulationHeading
        val badge = QrHostCopy.simulationBadge

        assertTrue(heading.hinglish.isNotBlank())
        assertTrue(heading.english.isNotBlank())
        assertTrue(badge.hinglish.isNotBlank())
        assertTrue(badge.english.isNotBlank())

        // Exact substring from the shipped badge copy: "it is not live camera decoding".
        assertTrue(
            "the English badge must say the demo is not live camera decoding, was: ${badge.english}",
            badge.english.contains("it is not live camera decoding")
        )
        assertTrue(
            "the Hinglish badge must say the same thing",
            badge.hinglish.contains("live decode nahi")
        )
        assertNotEquals("the badge must be translated", badge.hinglish, badge.english)

        val described = QrLanguage.entries.map { QrHostCopy.simulationControlDescription(it) }
        for ((index, text) in described.withIndex()) {
            val language = QrLanguage.entries[index]
            assertTrue("TalkBack label must not be blank for $language", text.isNotBlank())
        }
        val englishDescription = QrHostCopy.simulationControlDescription(QrLanguage.ENGLISH)
        val hinglishDescription = QrHostCopy.simulationControlDescription(QrLanguage.HINGLISH)
        assertTrue(
            "the TalkBack label must carry the not-live fact, was: $englishDescription",
            englishDescription.contains("not live camera decoding")
        )
        assertNotEquals(
            "the TalkBack label must be translated",
            hinglishDescription,
            englishDescription
        )

        // The heading is a short control label shipped identically in both languages, so it is
        // pinned as language-invariant rather than translated. Reported as a spec divergence.
        assertEquals(
            "simulationHeading ships the same text in both languages",
            heading.hinglish,
            heading.english
        )
        assertEquals(
            "the heading must survive text() in both languages",
            heading.hinglish,
            heading.text(QrLanguage.ENGLISH)
        )
        assertEquals(
            heading.hinglish,
            heading.text(QrLanguage.HINGLISH)
        )
    }

    // ------------------------------------------- 5. a denial starts no camera analysis ---------

    /**
     * Denial pauses; it does not rewrite evidence and does not leave the watcher looking armed.
     * The counter collected before the refusal survives, but nothing may start a camera session and
     * the screen must say the camera is the blocker.
     */
    @Test
    fun denialStartsNoCameraAnalysis() {
        var s = watchingState()

        // Pre-conditions: a real, running watch.
        assertEquals(QrPhase.WATCH, s.phase)
        assertEquals(CameraUiStatus.ACTIVE, s.camera)
        assertEquals(
            "the watch must be over a real registered payee",
            listOf(RegisteredPayee(shopUpi, shopName)),
            s.payees
        )

        // One valid mismatch is on record before the permission is revoked.
        s = s.act(QrEvent.ValidFrame(strangerUpi, null, 1_000L))
        assertEquals(1, s.consecutiveMismatches)

        s = s.act(QrEvent.PermissionDenied)

        assertEquals(CameraUiStatus.PERMISSION_DENIED, s.camera)
        assertFalse("no camera => nothing may start a watch", s.canStartWatch)
        assertEquals(QrGuidance.GRANT_CAMERA_PERMISSION, s.guidance)
        assertEquals(QrSignal.CAMERA_BLOCKED, s.signal)
        assertFalse("a blocked camera is not tamper evidence", s.isTamperEvidence)
        assertEquals(
            "a refusal is not evidence: the streak must be neither inflated nor erased",
            1,
            s.consecutiveMismatches
        )

        // And StartWatch cannot re-arm a camera that is not granted.
        val afterDenial = s.act(QrEvent.StartWatch(atMs = 2_000L))
        assertEquals(CameraUiStatus.PERMISSION_DENIED, afterDenial.camera)
        assertFalse(afterDenial.canStartWatch)

        // The camera-free states report the same "no analysis is running" fact.
        val noLens = QrUiState(camera = CameraUiStatus.NO_CAMERA)
        assertFalse(noLens.canStartWatch)
        val unprobed = QrUiState()
        assertEquals(CameraUiStatus.UNKNOWN, unprobed.camera)
        assertFalse("an unprobed camera never counts as usable", unprobed.canStartWatch)
    }

    // -------------------------------------------------------------- 6. determinism / idempotence

    /**
     * The decision has no hidden state: the same four facts give the same answer every time, and a
     * second identical denial changes nothing (so a re-delivered event cannot drift the screen).
     */
    @Test
    fun decideAndReduceAreDeterministic() {
        val booleans = listOf(false, true)
        for (granted in booleans) {
            for (hasAsked in booleans) {
                for (showRationale in booleans) {
                    for (hasCamera in booleans) {
                        val first = QrPermissionPolicy.decide(
                            granted, hasAsked, showRationale, hasCamera
                        )
                        val second = QrPermissionPolicy.decide(
                            granted, hasAsked, showRationale, hasCamera
                        )
                        assertEquals(
                            "state must be stable for granted=$granted hasAsked=$hasAsked " +
                                "showRationale=$showRationale hasCamera=$hasCamera",
                            first.state,
                            second.state
                        )
                        assertEquals(
                            "action must be stable for granted=$granted hasAsked=$hasAsked " +
                                "showRationale=$showRationale hasCamera=$hasCamera",
                            first.action,
                            second.action
                        )
                        assertEquals(first.pair(), second.pair())
                        assertEquals(first, second)
                        assertEquals(first.canUseCamera, second.canUseCamera)
                    }
                }
            }
        }

        // reduce(): one denial, then the same denial again, must be the same state.
        val watching = watchingState()
        val once = watching.act(QrEvent.PermissionDenied)
        val twice = once.act(QrEvent.PermissionDenied)
        assertEquals("a repeated denial must not change the state", once, twice)
        assertEquals(CameraUiStatus.PERMISSION_DENIED, twice.camera)
        assertFalse(twice.canStartWatch)
        assertEquals(QrSignal.CAMERA_BLOCKED, twice.signal)
    }
}
