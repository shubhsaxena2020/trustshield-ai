package ai.trustshield.android.ui.call

import ai.trustshield.android.ui.theme.ToneCopy

/*
 * Every user-facing string for Call Shield, Hinglish first with an English variant.
 *
 * Lives in Kotlin (not res/values/strings.xml) because the `res` tree is shared and owned by Codex
 * (C-package brief sec 2). Pure Kotlin: no android.*, no androidx.compose.*.
 *
 * Copy rules enforced by `CallScreenTest`:
 *  - the warning tells the user to END the call and VERIFY through an official number, and names
 *    1930 for money already sent;
 *  - no sentence accuses the person on the other end -- the alert is about the pattern of words;
 *  - no absolute-safety or guilt-probability claim ("100% safe", "pakka scam", "chor hai").
 */
object CallShieldCopy {

    val title = ToneCopy("Call Shield", "Call Shield")

    /** Never claim the remote side of the call is captured (HEAD_ORDERS / BEHAVIOR_SPEC). */
    val subtitle = ToneCopy(
        "Sirf woh awaaz jo phone ke mic tak pahunchti hai, aur sirf jab yeh screen khuli ho.",
        "Only audio that reaches the phone microphone, and only while this screen is open."
    )

    val honestyNote = ToneCopy(
        "Yeh app call ke doosre taraf ki awaaz nahi pakadta. Sirf aapki anumati se mic tak pahunchi awaaz check hoti hai.",
        "This app does not capture the other side of a call. It checks only audio reaching the microphone with your consent."
    )

    // ---- status ---------------------------------------------------------------------------------

    val micListening = ToneCopy("Mic: sun raha hai", "Mic: listening")
    val micNoSpeechYet = ToneCopy(
        "Sun raha hai - abhi koi awaaz nahi suni",
        "Listening - no speech heard yet",
        "सुन रहा है - अभी कोई आवाज़ नहीं सुनी",
    )
    val micPaused = ToneCopy("Mic: pause hai", "Mic: paused")
    val micOff = ToneCopy("Mic: band", "Mic: off")
    val micDenied = ToneCopy("Mic: anumati nahi mili", "Mic: permission not granted")

    fun analysisDisclosure(disclosure: RecognitionDisclosure): ToneCopy = when (disclosure) {
        RecognitionDisclosure.NOT_CHECKED -> ToneCopy(
            "Recognition path abhi check nahi hua; kuch audio device se bahar ja sakta hai.",
            "Recognition path not checked; some audio may leave this device."
        )
        RecognitionDisclosure.ON_DEVICE_UNVERIFIED -> ToneCopy(
            "On-device path report hua; offline kaam karna is device par test nahi hua.",
            "On-device path reported; offline behavior is unverified on this device."
        )
        RecognitionDisclosure.MAY_USE_NETWORK -> ToneCopy(
            "Recognizer internet use kar sakta hai; kuch audio device se bahar ja sakta hai.",
            "The recognizer may use the internet; some audio may leave this device."
        )
        RecognitionDisclosure.UNAVAILABLE -> ToneCopy(
            "Is device par speech recognition available nahi hai.",
            "Speech recognition is unavailable on this device."
        )
    }
    val backendOff = ToneCopy("Backend check: band", "Backend check: off")

    val timerLabel = ToneCopy("Samay", "Elapsed")
    val languageSwitch = ToneCopy("English", "Hinglish")

    fun nextLanguageLabel(language: CallLanguage): ToneCopy = when (language) {
        CallLanguage.HINGLISH -> ToneCopy("English", "English", "अंग्रेज़ी")
        CallLanguage.ENGLISH -> ToneCopy("Hindi", "Hindi", "हिन्दी")
        CallLanguage.HINDI -> ToneCopy("Hinglish", "Hinglish", "हिंग्लिश")
    }

    // ---- consent --------------------------------------------------------------------------------

    val consentTitle = ToneCopy("Pehle anumati", "Permission first")
    fun consentBody(disclosure: RecognitionDisclosure): ToneCopy {
        val path = analysisDisclosure(disclosure)
        val storageHi = "App audio recording store nahi karta; pehchana gaya text sirf is session ki memory me " +
            "rehta hai aur Stop par hata diya jata hai."
        val storageEn = "The app does not store audio recordings; recognized text stays in session memory only " +
            "and is cleared when you stop."
        val storageHindi = "ऐप ऑडियो रिकॉर्डिंग सेव नहीं करता; पहचाना गया टेक्स्ट केवल इस सेशन की मेमोरी में रहता है " +
            "और Stop करने पर मिटा दिया जाता है।"
        return ToneCopy(
            "Call Shield tabhi mic use karta hai jab aap shuru karein. ${path.hinglish} $storageHi",
            "Call Shield uses the microphone only after you start it. ${path.english} $storageEn",
            "Call Shield माइक्रोफ़ोन का उपयोग केवल आपके शुरू करने पर करता है। ${path.hindi} $storageHindi"
        )
    }
    val consentAction = ToneCopy("Main samajh gaya, anumati deta hoon", "I understand, give permission")
    val consentGiven = ToneCopy(
        "Anumati note hui. Mic aur speech check ke baad hi session shuru hoga.",
        "Permission noted. The session starts only after microphone and speech checks."
    )

    // ---- controls -------------------------------------------------------------------------------

    val start = ToneCopy("Session shuru karein", "Start session")
    val startNew = ToneCopy("Nayi session shuru karein", "Start a new session")
    val pause = ToneCopy("Thodi der rok dein", "Pause")
    val resume = ToneCopy("Phir shuru karein", "Resume")
    val stop = ToneCopy("Stop — sunna band karein", "Stop — stop listening")
    val stopHint = ToneCopy(
        "Stop dabate hi mic band ho jata hai. Back dabane par bhi session band ho jata hai.",
        "Stop immediately turns the microphone off. Back also ends the session."
    )
    val stoppedTitle = ToneCopy("Session band hai", "Session stopped")
    val stoppedBody = ToneCopy(
        "Mic band hai. Neeche ke stages sirf is session ke natije hain — baat-cheet ka text hata diya gaya hai.",
        "The microphone is off. The stages below are this session's outcome only — the spoken text has been cleared."
    )

    // ---- stages ---------------------------------------------------------------------------------

    fun stageLabel(stage: CallStage): ToneCopy = when (stage) {
        CallStage.AUTHORITY -> ToneCopy("Pehchaan", "Authority")
        CallStage.THREAT_REWARD -> ToneCopy("Darr ya laalach", "Threat or reward")
        CallStage.URGENCY -> ToneCopy("Jaldbaazi", "Urgency")
        CallStage.SECRECY_ISOLATION -> ToneCopy("Akela karna", "Secrecy or isolation")
        CallStage.ASK -> ToneCopy("Maang", "Ask")
    }

    fun stageDetail(stage: CallStage): ToneCopy = when (stage) {
        CallStage.AUTHORITY -> ToneCopy(
            "Bank, police, CBI, ED, customs ya courier ka daawa.",
            "A claimed bank, police, CBI, ED, customs or courier role."
        )

        CallStage.THREAT_REWARD -> ToneCopy(
            "Account band, arrest, parcel charge, lottery ya bada return.",
            "Account closure, arrest, parcel charge, lottery or an outsized return."
        )

        CallStage.URGENCY -> ToneCopy(
            "Abhi, aaj raat, ya deadline ka dabav.",
            "Now, tonight, or a deadline pressure."
        )

        CallStage.SECRECY_ISOLATION -> ToneCopy(
            "Kisi ko mat batana, call cut mat karna.",
            "Do not tell anyone, do not cut the call."
        )

        CallStage.ASK -> ToneCopy(
            "OTP, PIN, paise, remote access, APK ya personal data ki maang.",
            "A request for OTP, PIN, money, remote access, an APK or personal data."
        )
    }

    /** Stage icon channel (C02 supplies glyphs; the extended Material icon set is not a dependency). */
    fun stageGlyph(stage: CallStage): String = when (stage) {
        CallStage.AUTHORITY -> "\u2460"
        CallStage.THREAT_REWARD -> "\u2461"
        CallStage.URGENCY -> "\u2462"
        CallStage.SECRECY_ISOLATION -> "\u2463"
        CallStage.ASK -> "\u2464"
    }

    val stageFound = ToneCopy("mil gaya", "found")
    val stageNotFound = ToneCopy("abhi nahi mila", "not yet")

    // ---- risk + confidence ----------------------------------------------------------------------

    val riskHeading = ToneCopy("Khatre ka level", "Risk level")
    val riskExplainer = ToneCopy(
        "Level sirf baaton ke pattern se banta hai, kisi vyakti ke baare mein faisla nahi.",
        "The level comes from the pattern of words only; it is not a judgement about any person."
    )

    val confidenceHeading = ToneCopy("Confidence", "Confidence")
    val confidenceExplainer = ToneCopy(
        "Confidence batata hai ki awaaz kitni saaf samajh aayi — kisi par shak ka percentage nahi.",
        "Confidence describes how clearly the audio was understood — not a probability that anyone is guilty."
    )

    fun confidenceLevel(level: ConfidenceLevel): ToneCopy = when (level) {
        ConfidenceLevel.UNABLE_TO_VERIFY -> ToneCopy("Verify nahi ho paya", "Unable to verify")
        ConfidenceLevel.LOW -> ToneCopy("Kam", "Low")
        ConfidenceLevel.MEDIUM -> ToneCopy("Theek", "Medium")
        ConfidenceLevel.HIGH -> ToneCopy("Achhi", "High")
    }

    // ---- warnings -------------------------------------------------------------------------------

    val warningCaution = ToneCopy(
        "Dhyan dein: kuch scam jaise words mile. Call kaat kar official number se verify karein; " +
            "paise bheje hon to 1930 par call karein.",
        "Careful: some scam-like words were heard. End the call and verify through an official " +
            "number; if you already sent money, call 1930."
    )

    val warningHigh = ToneCopy(
        "Digital arrest scam ka pattern mila. Call kaat dijiye aur official number se verify karein; " +
            "paise bheje hon to 1930 par call karein. Koi police/CBI video call par arrest nahi karti " +
            "aur paise 'verify' nahi karwati.",
        "This matches a digital-arrest scam pattern. End the call and verify through an official " +
            "number; if you already sent money, call 1930. No police or CBI officer arrests anyone " +
            "over a video call, and nobody needs your money to 'verify' it."
    )

    val warningCritical = ToneCopy(
        "Digital arrest scam ka pattern mila, aur paise/OTP ki maang bhi hai. Turant call kaat dijiye. " +
            "Official number se verify karein; paise bheje hon to 1930 par call karein. Kisi ko OTP, " +
            "PIN ya remote access na dein.",
        "This matches a digital-arrest scam pattern and there is a request for money or a code. End " +
            "the call now. Verify through an official number; if you already sent money, call 1930. " +
            "Do not give anyone an OTP, PIN or remote access."
    )

    /**
     * The demo's full-screen alert — report §6's "Alert ka text", reproduced as the report writes it
     * (its longer middle clause is kept in [warningCritical] above, which the live screen already
     * uses). Issue #119 asks for this exact text at CRITICAL, so the demo quotes it verbatim instead
     * of paraphrasing; it never accuses a person, names the pattern and the safe next step.
     */
    val digitalArrestAlert = ToneCopy(
        hinglish = "Yeh digital arrest scam ka pattern hai. Koi bhi police ya CBI video call pe " +
            "arrest nahi karti. Call kaat dijiye aur 1930 pe call karein.",
        english = "This is a digital-arrest scam pattern. No police or CBI officer arrests anyone " +
            "over a video call. End the call and call 1930."
    )

    /** The alert never accuses; it only describes the pattern and the safe next step. */
    val noAccusation = ToneCopy(
        "Yeh alert sirf baaton ke pattern par hai. Yeh kisi vyakti par ilzaam nahi lagata.",
        "This alert is about the pattern of words only. It does not accuse any person."
    )

    fun warning(severity: AlertSeverity): ToneCopy = when (severity) {
        AlertSeverity.CRITICAL -> warningCritical
        AlertSeverity.HIGH -> warningHigh
        AlertSeverity.CAUTION -> warningCaution
        AlertSeverity.WATCH -> warningCaution
        AlertSeverity.NONE -> warningCaution
    }

    // ---- degraded states ------------------------------------------------------------------------

    val lowConfidenceTitle = ToneCopy("Awaaz saaf nahi", "Audio not clear")
    val lowConfidenceBody = ToneCopy(
        "Kuch baat theek se samajh nahi aayi, isliye alert roka gaya hai. Dobara boliye, ya phone ke " +
            "paas aaiye, warna yeh alert nahi dikhega.",
        "Some speech could not be understood, so the alert is on hold. Repeat it, or move closer to " +
            "the phone, otherwise the alert is not shown."
    )
    val retry = ToneCopy("Dobara sunnein", "Listen again")

    val permissionDeniedTitle = ToneCopy("Mic ki anumati nahi mili", "Microphone permission not granted")
    val grantPermission = ToneCopy("Anumati dein", "Grant permission")
    val permissionDeniedBody = ToneCopy(
        "Phone ki Settings mein is app ke liye Microphone allow karein, phir wapas aakar session " +
            "shuru karein. Bina anumati ke app sun nahi sakta.",
        "Allow Microphone for this app in your phone Settings, then come back and start the session. " +
            "Without permission the app cannot listen."
    )

    val recognizerUnavailableTitle = ToneCopy(
        "Is phone par Hindi speech recognition nahi mila",
        "Hindi speech recognition is not available on this phone"
    )
    val recognizerUnavailableBody = ToneCopy(
        "Live speech-to-text is device par nahi chal raha, isliye koi live alert nahi milega. " +
            "Speech service install karein, ya neeche se typed transcript / staged demo use karein — " +
            "wo live ASR nahi hai.",
        "Live speech-to-text is not running on this device, so no live alert is available. Install a " +
            "speech service, or use the typed transcript / staged demo below — that is not live ASR."
    )

    val stagedDemo = ToneCopy("Staged demo (live mic nahi)", "Staged demo (not live mic)")
    val typedTranscript = ToneCopy("Typed transcript", "Typed transcript")

    val unableToVerifyTitle = ToneCopy("Verify nahi ho paya", "Unable to verify")

    fun unableToVerifyBody(reason: UnableToVerifyReason): ToneCopy = when (reason) {
        UnableToVerifyReason.UNREADABLE_AUDIO -> ToneCopy(
            "Awaaz theek se sunai nahi di (shor ya doori). Koi alert nahi diya gaya. Phone ke paas " +
                "aakar dobara boliye.",
            "Speech could not be heard clearly (noise or distance). No alert was raised. Move closer " +
                "to the phone and repeat."
        )

        UnableToVerifyReason.UNSUPPORTED_LANGUAGE -> ToneCopy(
            "Is bhasha ko abhi poori tarah check nahi kar sakte, isliye koi alert nahi diya gaya.",
            "This language cannot be fully checked yet, so no alert was raised."
        )

        UnableToVerifyReason.MIC_UNAVAILABLE -> ToneCopy(
            "Mic uplabdh nahi hai. Anumati dein ya typed transcript use karein.",
            "The microphone is not available. Grant permission or use a typed transcript."
        )

        UnableToVerifyReason.RECOGNIZER_UNAVAILABLE -> ToneCopy(
            "Speech recognition uplabdh nahi hai. Typed transcript ya staged demo use karein.",
            "Speech recognition is not available. Use a typed transcript or the staged demo."
        )

        UnableToVerifyReason.NO_SPEECH_YET -> ToneCopy(
            "Abhi tak koi baat samajh nahi aayi. Session chal raha hai, boliye.",
            "No speech understood yet. The session is running — go ahead and speak."
        )

        UnableToVerifyReason.NONE -> ToneCopy("", "")
    }

    // ---- misc -----------------------------------------------------------------------------------

    val clearTranscriptHint = ToneCopy(
        "Baat-cheet ka text 2 minute baad apne aap hat jata hai, aur Stop par turant.",
        "Spoken text drops out after 2 minutes on its own, and immediately on Stop."
    )

    val whyStages = ToneCopy(
        "Alert kyun aaya: neeche woh stages dekhein jinke saamne 'mil gaya' likha hai, har ek ke " +
            "saath suni gayi asli line.",
        "Why this alert: see the stages marked 'found' below — each one keeps the exact line that was heard."
    )
}
