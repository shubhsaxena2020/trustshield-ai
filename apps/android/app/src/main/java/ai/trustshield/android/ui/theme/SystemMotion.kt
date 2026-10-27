package ai.trustshield.android.ui.theme

import android.content.ContentResolver
import android.provider.Settings

/**
 * Which motion policy the app must obey this launch: the user's own "Reduce animations" switch
 * (`UserPreferences.reducedMotion`, work package C04) **or** the Android system "Remove animations"
 * setting (`Settings.Global.ANIMATOR_DURATION_SCALE == 0`).
 *
 * ## Why this file exists
 * Until now reduced motion was only the app's own preference. A user who had already told Android
 * "Remove animations" in Accessibility / Developer options still got every TrustShield transition,
 * because nothing in the app read the platform switch. `docs/android/UI_SPEC.md` ("Tokens and
 * motion") requires that "reduced-motion / animator scale zero removes nonessential transitions",
 * `HEAD_DIRECTIVE_PHASE6_UX_LANDING.md` §U5 requires that "animations respect the system 'remove
 * animations' setting", and `QUALITY_GATES.md` item 6 is the release gate that fails if they do not.
 *
 * ## Two inputs, one answer, and the answer is a floor
 * The inputs combine with OR, never with "system overrides user": the *system* switch is a floor
 * under the *app* switch, so a user who turned animations off at OS level does not have to find this
 * app's toggle as well, and a user who turned the in-app switch on still gets reduced motion on a
 * phone whose animator scale is 1. Neither input can ever turn motion back **on** for the other.
 *
 * | `animatorDurationScale` | `userPrefReducedMotion` | [mustCollapseTransitions] |
 * |---|---|---|
 * | `null` (unreadable / never read) | `false` | `false` — a host that cannot read the platform value must not silently disable motion for everyone |
 * | `null` | `true` | `true` |
 * | `0f` (Android "Remove animations") | either | `true` |
 * | negative (malformed) | either | `true` — a negative duration cannot mean "animate", so it is read as off |
 * | `0.5f` (slow motion) | `false` | `false` — see [mustCollapseTransitions] |
 * | `1f` (default) | `false` | `false` |
 * | `2f` (fast) | `false` | `false` |
 * | `NaN`, `+Infinity`, `-Infinity` | `false` | `false` — not a duration at all, so treated as unreadable |
 *
 * The result is a **single boolean**, not a duration multiplier: [TsMotion.duration] and every
 * `tween(durationMillis = TsMotion.duration(...))` call site already take exactly that boolean, and
 * UI_SPEC asks for the non-essential transitions to be *removed*, not shortened. Partial scales are
 * deliberately not emulated here — see [mustCollapseTransitions] for why.
 *
 * ## Testability
 * The policy is a pure function over `Float?` and `Boolean`: no `android.*` beyond the two imports
 * needed by [readAnimatorDurationScale], no I/O, no clock, no state. `SystemMotionTest` therefore
 * proves every row of the table above on a plain JVM (`testDebugUnitTest`, no Robolectric, no
 * device), which is the only kind of proof this lane can offer — nothing here was compiled or run
 * by the author (see the report).
 */
object SystemMotion {

    /**
     * The one animator scale that means "off". Android's own "Remove animations" inverts
     * `ANIMATOR_DURATION_SCALE` to this value; anything above it is a real animation speed.
     */
    const val COLLAPSED_SCALE: Float = 0f

    /**
     * Does the **system** alone ask for collapsed transitions?
     *
     * True only for a finite scale at or below [COLLAPSED_SCALE]. `null` (the value could not be
     * read — no resolver, a restricted profile, a `SecurityException` from the settings provider)
     * and the non-finite values `NaN`, `+Infinity`, `-Infinity` all answer `false`, because a host
     * that does not know the platform value must not force reduced motion on every user. A negative
     * finite scale answers `true`: it is malformed, and the only safe reading of a negative duration
     * is "no animation".
     *
     * Exposed separately from [mustCollapseTransitions] so a caller can tell *why* motion is off —
     * the Settings screen, for example, can distinguish "you turned this on" from "your phone asks
     * for it" without duplicating the scale rules.
     */
    fun systemRequestsReducedMotion(animatorDurationScale: Float?): Boolean {
        val scale = animatorDurationScale ?: return false
        if (!scale.isFinite()) return false
        return scale <= COLLAPSED_SCALE
    }

    /**
     * The whole policy: must non-essential transitions be collapsed this launch?
     *
     * `true` when the user asked for reduced motion in app, **or** when the system's animator
     * duration scale says animations are off — the OS setting is a floor under the app switch, never
     * an alternative to it, so neither input can re-enable motion the other one disabled.
     *
     * ### Partial scales
     * `0.5f` and `2f` answer `false`, and that is intentional rather than an oversight: this
     * function answers "remove the transitions or not", which is a yes/no decision, and UI_SPEC wants
     * the transition **removed** at scale zero rather than time-scaled at other values. A user on a
     * 0.5x phone is asking for slow motion, not for no motion; they get the app's own switch for the
     * stronger request. Compose 1.7+ already multiplies `tween` durations by the platform motion
     * duration scale on the paths that go through the framework, so a partial scale is honoured by
     * the animation core itself; this policy does not try to re-implement that multiplication (doing
     * so would apply it twice). What this policy guarantees is the part the framework cannot
     * guarantee for us: the all-or-nothing collapse, stated once, in one testable place.
     *
     * ### Unreadable scale
     * `null` means "the platform value is unknown" (see [readAnimatorDurationScale], which returns
     * `null` for every failure mode instead of a made-up `1f`). It must yield `false` so that a
     * missing read never disables motion for a user who never asked for it; the app's own preference
     * still applies, because it is a real user choice rather than a guess.
     *
     * @param animatorDurationScale `Settings.Global.ANIMATOR_DURATION_SCALE` as a float, or `null`
     *   when it could not be read (or was never read).
     * @param userPrefReducedMotion the stored `UserPreferences.reducedMotion` switch.
     * @return whether every non-essential transition must be collapsed to its end state.
     */
    fun mustCollapseTransitions(
        animatorDurationScale: Float?,
        userPrefReducedMotion: Boolean
    ): Boolean = userPrefReducedMotion || systemRequestsReducedMotion(animatorDurationScale)
}

/**
 * Reads the platform animator duration scale, the value Android's "Remove animations" sets to `0`.
 *
 * Returns `null` on **any** failure and never throws: an absent key (a fresh profile, an OEM that
 * does not write it), a value that is not a number, a `SecurityException` or any other throwable out
 * of the settings provider. It reads the raw string and parses it rather than using
 * `Settings.Global.getFloat(resolver, key, 1f)`, because that overload silently substitutes `1f`
 * for "missing" and "unparseable" alike — which would make an unreadable setting indistinguishable
 * from "animations on" and hide a real failure from [SystemMotion.mustCollapseTransitions].
 *
 * Call this **once** at startup on the main thread and hand the value to
 * [SystemMotion.mustCollapseTransitions]; it is a slow-ish provider read and is not meant to be
 * called per frame, per recomposition, or from a background worker. Reading it once also gives a
 * stable answer for the launch, so the UI cannot flicker between reduced and full motion mid-session
 * while a LiveData-style observer catches up.
 *
 * @param contentResolver normally `context.contentResolver`; must be the application or activity
 *   resolver — this function does no permission work of its own.
 * @return the scale in `0f..3f` as the platform stores it, or `null` when it could not be read.
 */
fun readAnimatorDurationScale(contentResolver: ContentResolver): Float? {
    val raw = try {
        Settings.Global.getString(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE)
    } catch (_: Throwable) {
        // Restricted profiles, a dead provider, a stubbed value: all of them mean "unknown".
        return null
    } ?: return null
    return raw.trim().toFloatOrNull()
}
