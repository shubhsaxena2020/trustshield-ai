package ai.trustshield.android.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C01 — back-stack model tests.
 *
 * These run on a plain JVM through `testDebugUnitTest`: `AppRoute.kt` carries no `android.*` and no
 * `androidx.compose.*` import at all, so nothing here needs Robolectric, a device or a Compose UI-test
 * dependency (Codex owns `app/build.gradle.kts` and has not added one).
 *
 * What is covered, and why each case matters to a shipped app:
 *  * forward/back order — the LIFO contract every screen leans on;
 *  * single-top — a double-tapped scan button must not stack two Result screens;
 *  * back-from-root — must report "nothing to pop" so the system exits instead of looping;
 *  * serialise/restore — process death and intent re-entry must not lose or corrupt the stack;
 *  * unknown/legacy wire names — an older build's saved state must not crash the app.
 */
class NavTest {

    // ------------------------------------------------------------------ the model's invariants

    @Test
    fun freshStackIsHomeOnlyAndCannotPop() {
        val fresh = NavBackStack.Home

        assertEquals(AppRoute.HOME, fresh.current)
        assertEquals(1, fresh.size)
        assertEquals(listOf(AppRoute.HOME), fresh.routes)
        assertFalse("a stack of one must not offer a pop", fresh.canPop)
        assertNull("back at the root means the app exits, not a navigation", fresh.pop())
    }

    @Test
    fun everyRouteRoundTripsThroughItsWireName() {
        // The wire names are a persisted format: they appear in saved instance state and in the
        // re-entry intent extra. Changing one silently breaks a user's restored stack, so pin them.
        assertEquals(
            listOf("home", "result", "call_shield", "qr_guard", "history", "settings"),
            AppRoute.entries.map { it.wireName }
        )
        AppRoute.entries.forEach { route ->
            assertEquals("wire name must parse back to the same route", route, AppRoute.fromWire(route.wireName))
        }
    }

    @Test
    fun fivePrimaryTabsExistAndWireNamesAreUnique() {
        assertEquals(5, AppRoute.primaryTabs.size)
        assertEquals(
            listOf(AppRoute.HOME, AppRoute.CALL_SHIELD, AppRoute.QR_GUARD, AppRoute.HISTORY, AppRoute.SETTINGS),
            AppRoute.primaryTabs
        )
        assertEquals(
            6,
            AppRoute.entries.size
        )
        assertEquals(
            "duplicate wire names would make serialise/restore ambiguous",
            AppRoute.entries.size,
            AppRoute.entries.map { it.wireName }.toSet().size
        )
    }

    // ------------------------------------------------------------------ push / pop order

    @Test
    fun pushAdvancesAndGrowsTheStack() {
        val home = NavBackStack.Home
        val onResult = home.push(AppRoute.RESULT)

        assertEquals(AppRoute.RESULT, onResult.current)
        assertEquals(2, onResult.size)
        assertEquals(listOf(AppRoute.HOME, AppRoute.RESULT), onResult.routes)
        assertTrue(onResult.canPop)
        assertEquals("push must not mutate the receiver", listOf(AppRoute.HOME), home.routes)
        assertNotSame(home, onResult)
    }

    @Test
    fun popReturnsThroughTheStackInReverseOrder() {
        val deep = NavBackStack.Home
            .push(AppRoute.CALL_SHIELD)
            .push(AppRoute.SETTINGS)
            .push(AppRoute.QR_GUARD)

        assertEquals(listOf("home", "call_shield", "settings", "qr_guard"), deep.serialise())

        val back1 = deep.pop()
        assertNotNull(back1)
        assertEquals(AppRoute.SETTINGS, back1!!.current)

        val back2 = back1.pop()
        assertNotNull(back2)
        assertEquals(AppRoute.CALL_SHIELD, back2!!.current)

        val back3 = back2.pop()
        assertNotNull(back3)
        assertEquals(AppRoute.HOME, back3!!.current)
        assertFalse(back3.canPop)
        assertNull("stack exhausted", back3.pop())
    }

    // ------------------------------------------------------------------ single-top

    @Test
    fun pushingTheCurrentRouteIsSingleTop() {
        val onResult = NavBackStack.Home.push(AppRoute.RESULT)
        val again = onResult.push(AppRoute.RESULT)

        assertSame("re-navigating to the visible route must be a no-op", onResult, again)
        assertEquals(2, again.size)
        assertEquals(AppRoute.RESULT, again.current)
    }

    @Test
    fun singleTopLooksOnlyAtTheTopNotAtHistory() {
        // Home -> Settings -> Settings is a no-op, but Home -> Settings -> Home -> Settings is not:
        // a repeated route deeper in the stack is real history, not a duplicate.
        val a = NavBackStack.Home.push(AppRoute.SETTINGS).push(AppRoute.SETTINGS)
        assertEquals(2, a.size)

        val b = NavBackStack.Home
            .push(AppRoute.SETTINGS)
            .push(AppRoute.HOME)
            .push(AppRoute.SETTINGS)
        assertEquals(4, b.size)
        assertEquals(listOf("home", "settings", "home", "settings"), b.serialise())
    }

    // ------------------------------------------------------------------ replace

    @Test
    fun replaceSwapsTheTopWithoutGrowingTheStack() {
        val replaced = NavBackStack.Home.push(AppRoute.RESULT).replace(AppRoute.CALL_SHIELD)

        assertEquals(AppRoute.CALL_SHIELD, replaced.current)
        assertEquals(2, replaced.size)
        assertEquals(listOf("home", "call_shield"), replaced.serialise())
        assertEquals("Back still returns to the screen that opened it", AppRoute.HOME, replaced.pop()?.current)
    }

    @Test
    fun replaceAtTheRootKeepsARootedStack() {
        val replaced = NavBackStack.Home.replace(AppRoute.SETTINGS)

        assertEquals(listOf("settings"), replaced.serialise())
        assertFalse("replacing the root must not create a poppable history", replaced.canPop)
        assertSame("replacing with the current route is a no-op", replaced, replaced.replace(AppRoute.SETTINGS))
    }

    // ------------------------------------------------------------------ serialise / restore

    @Test
    fun serialiseRestoreRoundTripsTheWholeStack() {
        val original = NavBackStack.Home
            .push(AppRoute.RESULT)
            .push(AppRoute.SETTINGS)

        val wire = original.serialise()
        val restored = NavBackStack.restore(wire)

        assertEquals("restored stack must be an equal stack, not merely a non-empty one", original, restored)
        assertEquals(original.routes, restored.routes)
        assertEquals(original.current, restored.current)
        assertEquals(original.size, restored.size)
        assertEquals("serialise must be stable across a save/restore cycle", wire, restored.serialise())
    }

    @Test
    fun restoredStackIsNeverEmpty() {
        val cases = listOf(
            null,
            emptyList<String>(),
            listOf(""),
            listOf("   "),
            listOf("nonsense", "result_v2"),
            listOf("garbage_home")
        )

        cases.forEach { input ->
            val restored = NavBackStack.restore(input)
            assertTrue("restore($input) produced an empty stack", restored.routes.isNotEmpty())
            assertEquals("restore($input) must land on Home", AppRoute.HOME, restored.current)
            assertEquals(1, restored.size)
        }
    }

    @Test
    fun unknownRouteStringsAreDroppedAndTheRestSurvives() {
        // Simulates state written by a build that had a route we since removed.
        val restored = NavBackStack.restore(listOf("home", "legacy_intro", "result", "old_settings_sheet"))

        assertEquals(listOf("home", "result"), restored.serialise())
        assertEquals(AppRoute.RESULT, restored.current)
    }

    @Test
    fun aSingleUnknownTopRouteFallsBackToTheLastKnownGoodRoute() {
        val restored = NavBackStack.restore(listOf("home", "result", "gone_route"))

        assertEquals(listOf("home", "result"), restored.serialise())
        assertEquals(AppRoute.RESULT, restored.current)
        assertTrue(restored.canPop)
    }

    @Test
    fun restoreToleratesLegacyCasingAndWhitespace() {
        val restored = NavBackStack.restore(listOf(" HOME ", "Call_Shield", "QR_GUARD"))

        assertEquals(listOf("home", "call_shield", "qr_guard"), restored.serialise())
        assertEquals(AppRoute.QR_GUARD, restored.current)
    }

    @Test
    fun routeParsingRejectsBlanksAndNull() {
        assertNull(AppRoute.fromWire(null))
        assertNull(AppRoute.fromWire(""))
        assertNull(AppRoute.fromWire("   "))
        assertNull(AppRoute.fromWire("sett"))
        assertEquals(AppRoute.SETTINGS, AppRoute.fromWire("settings"))
    }

    // ------------------------------------------------------------------ intent re-entry

    @Test
    fun intentEntryPushesTheTargetOnTopOfHomeSoBackReturnsHome() {
        val entry = NavBackStack.fromIntentEntry("qr_guard")

        assertEquals(listOf(AppRoute.HOME, AppRoute.QR_GUARD), entry.routes)
        assertEquals("back from an intent entry must return to Home, not exit", AppRoute.HOME, entry.pop()?.current)
    }

    @Test
    fun unknownOrHomeIntentEntryStartsAtHomeWithNothingToPop() {
        listOf(null, "", "whatsapp_share", "home").forEach { raw ->
            val entry = NavBackStack.fromIntentEntry(raw)
            assertEquals("fromIntentEntry($raw)", listOf(AppRoute.HOME), entry.routes)
            assertFalse("fromIntentEntry($raw) must not create a poppable history", entry.canPop)
        }
    }

    @Test
    fun intentEntryReplacesStaleHistoryRatherThanAppending() {
        // A new intent is a new navigation request: it must not pile up on an old stack.
        val entry = NavBackStack.fromIntentEntry("result")

        assertEquals(2, entry.size)
        assertEquals(listOf("home", "result"), entry.serialise())
    }

    @Test
    fun restoredIntentSequenceDoesNotReplayTheOriginalLaunchEntry() {
        assertFalse(shouldApplyIntentEntry(AppRoute.RESULT, requestSequence = 3, lastAppliedSequence = 3))
    }

    @Test
    fun aNewIntentSequenceAppliesEvenWhenItRepeatsTheSameRoute() {
        assertTrue(shouldApplyIntentEntry(AppRoute.RESULT, requestSequence = 4, lastAppliedSequence = 3))
    }

    // ------------------------------------------------------------------ equality semantics

    @Test
    fun structurallyEqualStacksAreEqualAndSkipRecomposition() {
        val a = NavBackStack.of(listOf(AppRoute.HOME, AppRoute.RESULT))
        val b = NavBackStack.restore(listOf("home", "result"))

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotSame(a, b)
    }

    @Test
    fun ofWithNoRoutesFallsBackToHome() {
        assertEquals(NavBackStack.Home, NavBackStack.of())
        assertEquals(NavBackStack.Home, NavBackStack.of(emptyList()))
        assertEquals(1, NavBackStack.of(emptyList()).size)
    }
}
