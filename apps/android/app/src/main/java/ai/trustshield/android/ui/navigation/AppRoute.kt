package ai.trustshield.android.ui.navigation

/**
 * Five primary tabs plus the secondary Result route: Check, Call Shield, QR Guard, History, Settings.
 *
 * This file is deliberately **pure Kotlin** — no `android.*`, no `androidx.compose.*` imports — so
 * the navigation model can be exercised by `testDebugUnitTest` on a plain JVM (`NavTest.kt`). The
 * Compose bindings ([AppNavHost], [LocalNavActions]) live in `AppNav.kt` and only *use* this model.
 *
 * `wireName` is the persisted form of a route (saved instance state, intent extra). It is a frozen
 * contract: renaming it silently loses a user's back stack across a process death, so `NavTest`
 * pins the exact strings.
 */
enum class AppRoute(val wireName: String) {

    /** Home / Scan intake. Also the root of every back stack. */
    HOME("home"),

    /** Deterministic risk panel for one scan. */
    RESULT("result"),

    /** Call Shield — user-started analysis of audible microphone speech while the app is visible. */
    CALL_SHIELD("call_shield"),

    /** Optional on-device monitor for bursts of messages and calls. */
    BOMB_SHIELD("bomb_shield"),

    /** QR Guard — locally registered payee comparison. QR payloads are parsed as data, never opened. */
    QR_GUARD("qr_guard"),

    /** Metadata-only recent checks. Raw message content is never retained. */
    HISTORY("history"),

    /** Theme, language, sensitivity, haptics. */
    SETTINGS("settings");

    companion object {

        val primaryTabs: List<AppRoute> = listOf(HOME, CALL_SHIELD, QR_GUARD, HISTORY, SETTINGS)

        /** The root destination. Back from here leaves the app; a stack is never empty. */
        val ROOT: AppRoute = HOME

        /**
         * Intent extra carrying a [wireName] for re-entry (share intake, shortcuts, deep links).
         *
         * Codex owns `AndroidManifest.xml`; until a `VIEW`/`SEND` filter declares it, this extra is
         * simply ignored by the platform. Reading it is harmless when absent.
         */
        const val EXTRA_ROUTE: String = "ai.trustshield.android.extra.ROUTE"

        /**
         * Parses a persisted route name. Unknown, blank, `null` or legacy strings return `null` so
         * the caller can fall back to [ROOT] instead of crashing — a back stack written by an older
         * build, or a stale intent extra, must never take the app down.
         */
        fun fromWire(raw: String?): AppRoute? {
            val key = raw?.trim()?.lowercase() ?: return null
            if (key.isEmpty()) return null
            return entries.firstOrNull { it.wireName == key }
        }
    }
}

/** A route request is new only when its activity-maintained sequence differs from the saved one. */
internal fun shouldApplyIntentEntry(entry: AppRoute?, requestSequence: Int, lastAppliedSequence: Int): Boolean =
    entry != null && requestSequence != lastAppliedSequence

/**
 * Immutable back stack over [AppRoute].
 *
 * Every transition returns a new instance (or `this` when nothing changes), so a caller can hold one
 * reference per frame without defensive copying. Invariants, all enforced here rather than at the
 * call site:
 *
 *  * never empty — [Home] is a stack of one;
 *  * [current] is always the last element;
 *  * single-top: navigating to the route that is already on top is a no-op, so a double-tap on
 *    "Check this message" cannot stack two identical Result screens;
 *  * [pop] returns `null` at the root, which is the signal for "let the system exit the app"
 *    (in Compose that is `BackHandler(enabled = canPop)`).
 */
class NavBackStack private constructor(private val stack: List<AppRoute>) {

    /** Bottom-to-top route list. Index 0 is always [AppRoute.ROOT]. */
    val routes: List<AppRoute> get() = stack

    /** The visible destination. */
    val current: AppRoute get() = stack.last()

    val size: Int get() = stack.size

    /** `false` at the root: a back gesture there is the app's exit, not a navigation. */
    val canPop: Boolean get() = stack.size > 1

    /** Single-top forward navigation. Same route as [current] returns `this` unchanged. */
    fun push(route: AppRoute): NavBackStack =
        if (route == current) this else NavBackStack(stack + route)

    /**
     * Pops the top entry. Returns `null` when the stack is at its root, meaning "nothing left to
     * pop — the host should let the system finish the activity".
     */
    fun pop(): NavBackStack? =
        if (!canPop) null else NavBackStack(stack.subList(0, stack.size - 1).toList())

    /**
     * Replaces the top entry in place (Result -> Result after a re-scan, Settings -> Settings for a
     * different tab). Keeps the stack depth, so Back still returns to the screen that opened it.
     */
    fun replace(route: AppRoute): NavBackStack =
        if (route == current) this else NavBackStack(stack.subList(0, stack.size - 1) + route)

    /** Wire form for `rememberSaveable` / `onSaveInstanceState`. Reversible via [restore]. */
    fun serialise(): List<String> = stack.map { it.wireName }

    override fun equals(other: Any?): Boolean = other is NavBackStack && other.stack == stack

    override fun hashCode(): Int = stack.hashCode()

    override fun toString(): String = "NavBackStack(${stack.joinToString(" > ") { it.wireName }})"

    companion object {

        /** A fresh stack: Home only, nothing to pop. */
        val Home: NavBackStack = NavBackStack(listOf(AppRoute.ROOT))

        fun of(vararg routes: AppRoute): NavBackStack =
            if (routes.isEmpty()) Home else NavBackStack(routes.toList())

        fun of(routes: List<AppRoute>): NavBackStack =
            if (routes.isEmpty()) Home else NavBackStack(routes.toList())

        /**
         * Rebuilds a stack from [serialise] output (process recreation, saved instance state).
         *
         * Tolerant by design: unparseable entries are dropped, an all-unknown or empty list yields
         * [Home], and the result is never empty. History order is preserved as written.
         */
        fun restore(values: List<String>?): NavBackStack {
            val parsed = values.orEmpty().mapNotNull { AppRoute.fromWire(it) }
            return if (parsed.isEmpty()) Home else NavBackStack(parsed)
        }

        /**
         * Stack for re-entry through an intent (share intake, shortcut, deep link).
         *
         * The requested destination is pushed on top of [AppRoute.ROOT] rather than replacing the
         * stack, so Back lands on Home instead of dropping the user out of the app. An unknown or
         * absent route yields [Home].
         */
        fun fromIntentEntry(rawRoute: String?): NavBackStack {
            val target = AppRoute.fromWire(rawRoute) ?: return Home
            return if (target == AppRoute.ROOT) Home else NavBackStack(listOf(AppRoute.ROOT, target))
        }
    }
}
