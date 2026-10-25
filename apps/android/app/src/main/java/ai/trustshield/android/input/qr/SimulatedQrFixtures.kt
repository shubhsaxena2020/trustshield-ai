package ai.trustshield.android.input.qr

/**
 * P3-15 / issue #94 — deterministic "Simulate scan" fixtures and driver for QR Guard.
 *
 * ## What this file is for
 *
 * Phase 3 definition of done, item 5 (`docs/android/HEAD_ORDERS_PHASE3.md`) asks for a
 * **"Simulate scan"** path "for emulator testing and for demos without a second QR". Chipping a fake
 * sticker over a real QR needs two paper QRs and a steady hand; this file provides the same three demo
 * flows — **matching**, **mismatch** and **unreadable** — as synthetic payloads that are replayed through
 * the *already merged* decision core:
 *
 *  - B02 [`UpiQrParser`] does the structural parse (a `upi://pay` payload is really parsed, `pa` really
 *    extracted, a URL really classified as [`QrPayloadKind.URL`]).
 *  - B06 [`QrWatcher`] does the counting, masking and state machine.
 *
 * This is a **simulation/demo path only**. It is not evidence that live camera decoding works: a real
 * decode depends on CameraX + ML Kit (P3-05) and is verified on the emulator, not here. Nothing in this
 * file is fed to the UI as a substitute for a camera frame; it is an explicitly labelled demo input.
 *
 * ## Rules this file demonstrates (and never weakens)
 *
 * | Flow | Rule demonstrated |
 * | --- | --- |
 * | [QrSimulationFlow.Match] | report §7 `SETUP -> WATCH`: the same `pa` keeps the watcher green, alert `None`, counter `0`. |
 * | [QrSimulationFlow.Mismatch] | `BEHAVIOR_SPEC.md` "QR and payment semantics": **three consecutive valid decoded mismatches** raise the warning — two is not enough. |
 * | [QrSimulationFlow.Unreadable] | `BEHAVIOR_SPEC.md`: "No decode/glare pauses the mismatch counter and starts an occlusion timer; after five minutes display unreadable/occluded, **not tampered**." |
 * | [QrSimulationFlow.MismatchAfterDarkFrames] | report §7 edge case "Raat ya kam roshni": an unreadable stretch **pauses** the counter, it never increments and never resets it. |
 * | [QrSimulationFlow.DualStand] | report §7 edge case "Stand pe 2 QR (PhonePe + Paytm)": several registered payees all count as matches. |
 *
 * ## Privacy and safety reasoning
 *
 *  - A QR payload is inert **data**. This file does not fetch, open, resolve, preview or execute a
 *    destination: no Android intent, no network client, no WebView, no file access. It has **no imports
 *    at all** (same package as the parser and the watcher), and `SimulatedQrFixturesTest` reads this
 *    file's own source and fails if that ever changes.
 *  - Every value is synthetic and obviously fake. Payees are `dukaan.demo@okaxis`, `dukaan.demo@paytm`
 *    and `demo.payee@ybl` — invented local parts, no real shop, no phone number, no OTP, no PIN. The one
 *    web address uses the RFC 2606 reserved TLD `example.invalid`, which cannot resolve even in
 *    principle, so a stray "open this link" bug could not reach a live host.
 *  - Only **masked** identifiers can leave this file: status messages come from [`QrWatcher`] (masked),
 *    narration lines are built with [`PayeeMasking.mask`], and every result `toString()` is an aggregate
 *    (`flow=..., state=..., mismatches=...`) with no message body and no payee. A demo screen, a
 *    screenshot or a pasted log therefore cannot leak a UPI id.
 *  - Unreadable is **never** reported as tampered. There is no fixture that turns a bad read into a
 *    mismatch: a garbage frame, a payload without `pa`, a structurally broken `pa` and a URL all extract
 *    `null` and go down the unreadable path, exactly like a glare frame.
 *
 * ## Determinism
 *
 * Time comes from a private fake clock owned by the driver, starting at `0` and advanced by a fixed
 * script of offsets. There is no randomness, no wall clock, no locale-sensitive formatting and no
 * network, so the same flow always produces byte-identical steps — the property
 * `SimulatedQrFixturesTest.every flow produces identical results when run twice` depends on.
 */
object SimulatedQrFixtures {

    /**
     * The payee the shop registers: the merchant's own UPI id on the real QR.
     *
     * Synthetic: `dukaan.demo` is not a person and does not exist. Real UPI handles are never used in a
     * fixture, because a fixture is a string that ends up in a demo video, a screenshot and a PR body.
     */
    const val MERCHANT_PAYEE: String = "dukaan.demo@okaxis"

    /**
     * The `pn` label. BEHAVIOR_SPEC: `pn` is a **potentially spoofable label** and is never compared —
     * the sticker below copies this exact name, and the watcher still raises the alert, because only
     * `pa` decides.
     */
    const val MERCHANT_DISPLAY_NAME: String = "Sharma Kirana Demo"

    /**
     * The second payee on a shared stand (PhonePe + Paytm on one counter, report §7). Distinct from
     * [MERCHANT_PAYEE] so it is visible that a *set* of payees is registered, not one string.
     */
    const val SECOND_MERCHANT_PAYEE: String = "dukaan.demo@paytm"

    /**
     * The fraudster's substituted payee — the "nakli sticker" from the report's demo. Deliberately a
     * different local part *and* a different handle so the masked strings differ (`de***@ybl` vs
     * `du***@okaxis`) and the demo screen shows which side is which.
     */
    const val STICKER_PAYEE: String = "demo.payee@ybl"

    /** Reserved TLD (RFC 2606): the one web address in this file can never resolve to a live host. */
    private const val DEMO_URL: String = "https://example.invalid/dukaan-demo-offer"

    /**
     * Fixed demo salt for the HMAC payee registry. **Not a secret and not a privacy guarantee** — it only
     * makes the demo repeatable on every device. The shipped app must take its salt from the keystore
     * (`SaltedPayeeRegistry` KDoc, `docs/android/ARCHITECTURE.md` boundaries).
     */
    private val DEMO_SALT: ByteArray = ByteArray(32) { index -> (0x51 + index).toByte() }

    // ---------------------------------------------------------------- payloads (inert data)

    /**
     * The merchant QR the shop registers: `upi://pay` with [MERCHANT_PAYEE], the display name and `INR`.
     * No amount, so the demo cannot be mistaken for a real payment request.
     */
    fun merchantQr(): String =
        "upi://pay?pa=$MERCHANT_PAYEE&pn=Sharma+Kirana+Demo&cu=INR&tn=QR+Guard+Demo"

    /** The second counter's QR on the same stand, used by [QrSimulationFlow.DualStand]. */
    fun secondMerchantQr(): String =
        "upi://pay?pa=$SECOND_MERCHANT_PAYEE&pn=Sharma+Kirana+Demo&cu=INR&tn=QR+Guard+Demo"

    /**
     * The substituted sticker: a valid `upi://pay` payload with [STICKER_PAYEE] and the **same** `pn`.
     * A structurally perfect payload is exactly the point — the tamper is in `pa`, and only `pa` is
     * compared against the salted registry.
     */
    fun stickerQr(): String =
        "upi://pay?pa=$STICKER_PAYEE&pn=Sharma+Kirana+Demo&cu=INR&tn=QR+Guard+Demo"

    /**
     * A UPI intent with **no `pa`**. Parses as [`QrPayloadKind.UPI`] with [`UpiFlag.MISSING_PAYEE`], and
     * yields no payee, so the watcher treats it exactly like a glare frame: unreadable, never a mismatch.
     */
    fun noPayeeQr(): String = "upi://pay?pn=Sharma+Kirana+Demo&cu=INR"

    /**
     * A UPI intent whose `pa` is structurally broken (no `@`). Parses with [`UpiFlag.MALFORMED_PAYEE`];
     * the driver's extractor still returns `null`, because a broken payee is not evidence of tampering —
     * it is evidence that this frame could not be read (report §7: "Sticker aadha chipka -> decode fail").
     */
    fun malformedPayeeQr(): String = "upi://pay?pa=dukaan-demo-tanpa-handle&pn=Sharma+Kirana+Demo&cu=INR"

    /**
     * Plain text: what a blurred, angled or half-covered QR usually decodes to, if it decodes at all.
     * Classifies as [`QrPayloadKind.TEXT`].
     */
    fun garbageFrame(): String = "DUKAAN QR DEMO - adhoora frame, padha nahi gaya"

    /**
     * A web URL QR (an "offer" link someone stuck next to a counter). Classifies as
     * [`QrPayloadKind.URL`] and is **never opened**: the QR Guard path only ever extracts `pa`, so this
     * frame is unreadable content, not a destination and not a mismatch.
     */
    fun urlQr(): String = DEMO_URL

    // ---------------------------------------------------------------- extractor seam

    /**
     * The integration binding described in [`PayeeExtractor`]: B02's structural parse reduced to the
     * payee, with the two "cannot trust this payee" flags turned into `null`.
     *
     * Why the flags matter: [`UpiQrParser`] keeps a malformed `pa` string so the *analysis* screen can
     * quote it, but the watcher must not compare a broken value with the registry — that would turn a
     * bad read into a false tamper alarm. Missing, malformed or non-UPI all mean "no payee", which B06
     * already defines as the unreadable path.
     */
    fun payeeExtractor(): PayeeExtractor = DEMO_EXTRACTOR

    private val DEMO_EXTRACTOR: PayeeExtractor = PayeeExtractor { payload ->
        val parsed = UpiQrParser.parse(payload)
        val vpa = parsed.payeeVpa
        when {
            parsed.kind != QrPayloadKind.UPI -> null
            parsed.hasFlag(UpiFlag.MISSING_PAYEE) -> null
            parsed.hasFlag(UpiFlag.MALFORMED_PAYEE) -> null
            vpa.isNullOrBlank() -> null
            else -> vpa
        }
    }

    /** A fresh registry over the fixed demo salt, so each run starts from a known, repeatable state. */
    fun newRegistry(): PayeeRegistry = SaltedPayeeRegistry(DEMO_SALT.copyOf())

    // ---------------------------------------------------------------- driver

    /**
     * Runs [flow] against a fresh demo registry and returns every step plus the final status.
     *
     * @see run for the overload that reuses a caller-owned registry.
     */
    fun run(flow: QrSimulationFlow): QrSimulationResult = run(flow, newRegistry())

    /**
     * Replays [flow] through a real [`QrWatcher`] over [registry], with a private fake clock.
     *
     * The driver is deliberately thin: it only decides *which payload arrives at which offset*. Every
     * state decision, counter change, mask and message is produced by the shipped B06 machine and the
     * shipped B02 parser, so the demo can never drift from the live behaviour.
     *
     * @param registry when empty, the flow's expected payees are registered through the watcher's normal
     *   setup gate (first payee) and then straight into the registry (the shared store the QR/settings
     *   screen owns, which is how a second counter's payee is added). When non-empty, the caller owns the
     *   contents and the driver does not touch them.
     * @param policy tunables; defaults are the shipped ones and a demo must not weaken them.
     */
    fun run(
        flow: QrSimulationFlow,
        registry: PayeeRegistry,
        policy: QrWatcherPolicy = QrWatcherPolicy(),
    ): QrSimulationResult {
        val clock = DemoClock()
        val steps = mutableListOf<QrSimulationStep>()

        fun record(kind: SimulatedFrameKind, narration: String, status: QrWatcherStatus) {
            steps += QrSimulationStep(
                index = steps.size,
                kind = kind,
                narration = narration,
                status = status,
            )
        }

        val watcher = QrWatcher(payeeExtractor(), registry, clock::read, policy)

        if (registry.registered().isEmpty()) {
            // SETUP -> WATCH through the production gate: one payee, then any extra counter payees.
            watcher.registerPayee(flow.registeredPayees.first())
            flow.registeredPayees.drop(1).forEach { registry.register(it) }
        }

        val registeredStatus = watcher.status()
        record(
            SimulatedFrameKind.REGISTERED,
            registeredNarration(flow, registeredStatus),
            registeredStatus,
        )

        when (flow) {
            QrSimulationFlow.Match -> {
                repeat(3) { iteration ->
                    clock.now = FRAME_INTERVAL_MS * (iteration + 1)
                    val status = watcher.observeDecoded(merchantQr())
                    record(
                        SimulatedFrameKind.EXPECTED_DECODE,
                        "Frame ${iteration + 1}/3: wahi UPI ID mila (${mask(MERCHANT_PAYEE)}). " +
                            "Nazar jaari hai, koi alert nahi.",
                        status,
                    )
                }
            }

            QrSimulationFlow.Mismatch -> {
                val expected = mask(MERCHANT_PAYEE)
                val seen = mask(STICKER_PAYEE)
                repeat(3) { iteration ->
                    clock.now = FRAME_INTERVAL_MS * (iteration + 1)
                    val status = watcher.observeDecoded(stickerQr())
                    val narration = when (iteration) {
                        0 -> "Sticker chipka hua QR: alag UPI ID ($seen) - mismatch 1/3. Abhi sirf warning."
                        1 -> "Alag UPI ID phir mila ($seen) - mismatch 2/3. Do baar se alert nahi aata."
                        else ->
                            "Teesra lagataar valid mismatch: expected $expected, mila $seen. " +
                                "Payment mat karein - dukaandaar se poochhein."
                    }
                    record(SimulatedFrameKind.FOREIGN_DECODE, narration, status)
                }
            }

            QrSimulationFlow.Unreadable -> {
                clock.now = FRAME_INTERVAL_MS
                record(
                    SimulatedFrameKind.EXPECTED_DECODE,
                    "Frame: dukaan ka QR saaf dikha (${mask(MERCHANT_PAYEE)}) - ek valid decode.",
                    watcher.observeDecoded(merchantQr()),
                )

                clock.now = FRAME_INTERVAL_MS * 2
                record(
                    SimulatedFrameKind.UNPARSEABLE_DECODE,
                    "Frame: QR adhoora tha, kuch padha nahi ja saka. Yeh tamper nahi hai.",
                    watcher.observeDecoded(garbageFrame()),
                )

                clock.now = FRAME_INTERVAL_MS * 4
                record(
                    SimulatedFrameKind.UNPARSEABLE_DECODE,
                    "Frame: QR mein payee (pa) hi nahi tha - unreadable, mismatch nahi.",
                    watcher.observeDecoded(noPayeeQr()),
                )

                clock.now = FRAME_INTERVAL_MS * 6
                record(
                    SimulatedFrameKind.UNPARSEABLE_DECODE,
                    "Frame: QR mein website ka link tha - sirf data, khola nahi gaya.",
                    watcher.observeDecoded(urlQr()),
                )

                clock.now = FRAME_INTERVAL_MS * 9
                record(
                    SimulatedFrameKind.UNPARSEABLE_DECODE,
                    "Frame: payee ka format toota hua tha - unreadable, mismatch nahi.",
                    watcher.observeDecoded(malformedPayeeQr()),
                )

                clock.now = FRAME_INTERVAL_MS * 15
                record(
                    SimulatedFrameKind.BLURRED_FRAME,
                    "Frame: roshni kam ya glare - camera ko kuch nahi mila. 4 minute 50 second ho gaye.",
                    watcher.observeUnreadable(),
                )

                clock.now = FRAME_INTERVAL_MS * 31
                record(
                    SimulatedFrameKind.BLURRED_FRAME,
                    "5 minute se koi valid decode nahi: QR dikh nahi raha (Occluded). " +
                        "Yeh tamper nahi hai - camera seedha QR pe rakhein.",
                    watcher.observeUnreadable(),
                )
            }

            QrSimulationFlow.MismatchAfterDarkFrames -> {
                val seen = mask(STICKER_PAYEE)
                clock.now = FRAME_INTERVAL_MS
                record(
                    SimulatedFrameKind.FOREIGN_DECODE,
                    "Alag UPI ID ($seen) - mismatch 1/3.",
                    watcher.observeDecoded(stickerQr()),
                )

                clock.now = FRAME_INTERVAL_MS * 2
                record(
                    SimulatedFrameKind.FOREIGN_DECODE,
                    "Alag UPI ID ($seen) - mismatch 2/3.",
                    watcher.observeDecoded(stickerQr()),
                )

                clock.now = FRAME_INTERVAL_MS * 3
                record(
                    SimulatedFrameKind.BLURRED_FRAME,
                    "Roshni kam ho gayi, QR dikhna band - counter ruka, badha nahi.",
                    watcher.observeUnreadable(),
                )

                clock.now = FRAME_INTERVAL_MS * 5
                record(
                    SimulatedFrameKind.UNPARSEABLE_DECODE,
                    "Frame phir se adhoora - counter abhi bhi 2 pe ruka hai, alert nahi.",
                    watcher.observeDecoded(garbageFrame()),
                )

                clock.now = FRAME_INTERVAL_MS * 6
                record(
                    SimulatedFrameKind.FOREIGN_DECODE,
                    "Teesra valid mismatch aate hi Tampered - pehle ke do mismatch yaad the.",
                    watcher.observeDecoded(stickerQr()),
                )
            }

            QrSimulationFlow.DualStand -> {
                val stand = listOf(
                    merchantQr() to MERCHANT_PAYEE,
                    secondMerchantQr() to SECOND_MERCHANT_PAYEE,
                    merchantQr() to MERCHANT_PAYEE,
                    secondMerchantQr() to SECOND_MERCHANT_PAYEE,
                )
                stand.forEachIndexed { index, (payload, payee) ->
                    clock.now = FRAME_INTERVAL_MS * (index + 1)
                    record(
                        SimulatedFrameKind.EXPECTED_DECODE,
                        "Frame ${index + 1}/4: stand ka doosra QR (${mask(payee)}) - dono register hain, match.",
                        watcher.observeDecoded(payload),
                    )
                }
            }
        }

        val finalStatus = watcher.status()
        return QrSimulationResult(
            flow = flow,
            steps = steps.toList(),
            finalStatus = finalStatus,
            elapsedMs = clock.now,
        )
    }

    /** Masked-only registration line: which expected payees this demo has on file. */
    private fun registeredNarration(flow: QrSimulationFlow, status: QrWatcherStatus): String {
        val expected = flow.registeredPayees.joinToString(", ") { mask(it) }
        return "Demo setup (${flow.title}): register hua $expected - " +
            "${status.registeredCount} payee, state ${status.state}."
    }

    /** Single masking implementation, so a narration line cannot accidentally print a raw VPA. */
    private fun mask(vpa: String): String = PayeeMasking.mask(vpa)

    /** Fixed offset between demo frames: 10 s, inside the report's "every 10-30 second" watch loop. */
    private const val FRAME_INTERVAL_MS: Long = 10_000L

    /**
     * The driver's only clock: a plain counter advanced by the flow script. No wall clock, no
     * `System.nanoTime`, no scheduling — two runs of the same flow are identical by construction.
     */
    private class DemoClock {
        var now: Long = 0L
        fun read(): Long = now
    }
}

/**
 * The demo flows, exhaustive by construction (a `when` over this type must cover all of them).
 *
 * Each flow declares the payees it registers, so the driver never guesses and a test can assert that
 * the shop's own payee is on file before any frame arrives.
 */
sealed interface QrSimulationFlow {

    /** Stable identifier for a log line, a test name or an analytics-free route key. */
    val wire: String

    /** Hinglish label for the demo button; UI copy is owned by UI_SPEC, this is the demo source. */
    val title: String

    /** Expected payees registered before the first frame. Synthetic values only. */
    val registeredPayees: List<String>

    /** Happy path: the shop's own QR, three valid matches, no alert ever. */
    data object Match : QrSimulationFlow {
        override val wire: String = "match"
        override val title: String = "Match - dukaan ka hi QR"
        override val registeredPayees: List<String> = listOf(SimulatedQrFixtures.MERCHANT_PAYEE)
    }

    /** The fraudster's sticker: exactly three consecutive valid mismatches, then `Tampered`. */
    data object Mismatch : QrSimulationFlow {
        override val wire: String = "mismatch"
        override val title: String = "Tamper - sticker wala QR"
        override val registeredPayees: List<String> = listOf(SimulatedQrFixtures.MERCHANT_PAYEE)
    }

    /** Dark shop / glare / covered QR: unreadable frames only, ending `Occluded` after five minutes. */
    data object Unreadable : QrSimulationFlow {
        override val wire: String = "unreadable"
        override val title: String = "Unreadable - roshni kam"
        override val registeredPayees: List<String> = listOf(SimulatedQrFixtures.MERCHANT_PAYEE)
    }

    /** Two counters on one stand: both registered payees are matches. */
    data object DualStand : QrSimulationFlow {
        override val wire: String = "dual_stand"
        override val title: String = "Do QR ek stand pe"
        override val registeredPayees: List<String> = listOf(
            SimulatedQrFixtures.MERCHANT_PAYEE,
            SimulatedQrFixtures.SECOND_MERCHANT_PAYEE,
        )
    }

    /** Two mismatches, then darkness, then the third mismatch: the counter pauses, it never resets. */
    data object MismatchAfterDarkFrames : QrSimulationFlow {
        override val wire: String = "mismatch_after_dark_frames"
        override val title: String = "Do mismatch ke baad andhera"
        override val registeredPayees: List<String> = listOf(SimulatedQrFixtures.MERCHANT_PAYEE)
    }
}

/** What the driver fed the watcher for one step. Lets a test locate a frame without parsing copy. */
enum class SimulatedFrameKind {
    /** The registration step (SETUP -> WATCH); no frame was consumed. */
    REGISTERED,

    /** A valid decode that matches a registered payee. */
    EXPECTED_DECODE,

    /** A valid decode whose payee is not registered — the only kind that can raise `Tampered`. */
    FOREIGN_DECODE,

    /** A payload that decoded but yielded no trustworthy payee: garbage, no `pa`, broken `pa`, or a URL. */
    UNPARSEABLE_DECODE,

    /** No decode at all: glare, blur, darkness, QR covered. */
    BLURRED_FRAME,
}

/**
 * One replayed step: the frame kind, a Hinglish narration line and the watcher's status afterwards.
 *
 * [narration] and [status] are masked at the source (`PayeeMasking` / `QrWatcher`), so a demo screen may
 * render them. [toString] stays aggregate anyway: a step can end up in a log or a test report, and an
 * aggregate line cannot leak a payee even if a future narration string is careless.
 */
data class QrSimulationStep(
    val index: Int,
    val kind: SimulatedFrameKind,
    val narration: String,
    val status: QrWatcherStatus,
) {
    override fun toString(): String =
        "QrSimulationStep($index, kind=$kind, state=${status.state}, " +
            "alert=${status.alertLevel}, mismatches=${status.consecutiveMismatches})"
}

/**
 * Result of one demo run: the full status sequence, the final status and elapsed demo time.
 *
 * Equality is structural (data class), which is what the determinism test compares. [toString] is
 * aggregate only — `flow`, step count, final state, alert level, counters — never a message body and
 * never a payee (raw or masked), because a demo result is exactly the object that gets pasted into a PR.
 */
data class QrSimulationResult(
    val flow: QrSimulationFlow,
    val steps: List<QrSimulationStep>,
    val finalStatus: QrWatcherStatus,
    val elapsedMs: Long,
) {
    /** True when any step reached `Tampered` (only a valid, foreign, repeated decode can). */
    val everTampered: Boolean get() = steps.any { it.status.state == QrGuardState.Tampered }

    /** True when any step reached `Occluded` (only the occlusion timer can). */
    val everOccluded: Boolean get() = steps.any { it.status.state == QrGuardState.Occluded }

    /** The narration lines in order — what a demo drawer shows under each replay step. */
    fun narrate(): String = steps.joinToString(separator = "\n") { step ->
        "${step.index + 1}. ${step.narration}"
    }

    override fun toString(): String =
        "QrSimulationResult(flow=${flow.wire}, steps=${steps.size}, " +
            "state=${finalStatus.state}, alert=${finalStatus.alertLevel}, " +
            "mismatches=${finalStatus.consecutiveMismatches}, " +
            "payees=${finalStatus.registeredCount}, " +
            "tampered=$everTampered, occluded=$everOccluded, elapsedMs=$elapsedMs)"
}
