package ai.trustshield.android.ui.navigation

import ai.trustshield.android.core.model.Evidence
import ai.trustshield.android.core.model.Strength
import ai.trustshield.android.core.score.LocalScorer
import ai.trustshield.android.ui.result.ActionPriority
import ai.trustshield.android.ui.result.EvidenceSeverity
import ai.trustshield.android.ui.result.ResultActionInput
import ai.trustshield.android.ui.result.ResultEvidenceInput

/** Converts local engine copy into the bilingual Result presentation model. */
internal object LocalEvidenceAdapter {
    private data class EnglishCopy(val title: String, val detail: String)

    private val evidenceCopy = mapOf(
        "Sanstha ka dawa" to EnglishCopy("Claimed organization", "The message claims to represent an organization. This claim alone is not proof of a scam."),
        "Sarkari officer ka dawa" to EnglishCopy("Claimed government officer", "The sender claims to be an official or bank representative. Verify through an official channel."),
        "Account band karne ki dhamki" to EnglishCopy("Account closure threat", "The message threatens to block or suspend an account, KYC, SIM, or card."),
        "Arrest ya legal dhamki" to EnglishCopy("Arrest or legal threat", "The message uses arrest, legal action, or a warrant to create fear."),
        "Inaam ya lottery ka laalach" to EnglishCopy("Prize or lottery offer", "The message promises a prize, lottery, cashback, or guaranteed return."),
        "Ghar baithe kamai ka laalach" to EnglishCopy("Work-from-home earnings offer", "The message promises easy income for work from home."),
        "Warna police/case ki dhamki" to EnglishCopy("Police or case threat", "The message threatens police action or a case if you do not comply."),
        "Jaldbaazi (time pressure)" to EnglishCopy("Urgency and time pressure", "The message pressures you to act immediately. Urgency alone is not proof of a scam."),
        "Kisi ko batane se roka" to EnglishCopy("Told you to keep it secret", "The message asks you not to tell anyone, which can isolate you from advice."),
        "Confidential case" to EnglishCopy("Confidential case claim", "The message presents the matter as confidential to discourage outside advice."),
        "Call/line par rokne ki koshish" to EnglishCopy("Pressure to stay on the call", "The sender tries to keep you on the line and prevent independent verification."),
        "Video call par raho" to EnglishCopy("Instruction to stay on a video call", "The message tells you to remain on a video call."),
        "Akele raho" to EnglishCopy("Instruction to stay alone", "The message tells you to stay alone, which can prevent you from getting help."),
        "OTP/PIN maanga gaya" to EnglishCopy("OTP or PIN requested", "The message asks for a code or payment credential. Never share an OTP or PIN."),
        "Paise bhejne ko kaha gaya" to EnglishCopy("Payment requested", "The message asks you to send money. Verify the request independently."),
        "Remote access maanga gaya" to EnglishCopy("Remote access requested", "The sender asks you to install or use remote-access software."),
        "APK install karne ko kaha" to EnglishCopy("APK installation requested", "The message asks you to install an APK, which can expose your device and accounts."),
        "Personal detail maangi gayi" to EnglishCopy("Personal information requested", "The message asks for personal or identity information."),
        "Analyzer ko instruction dene ki koshish" to EnglishCopy("Attempt to manipulate the analysis", "The message tries to change how the analyzer works. Its instructions are treated as message content."),
        "Bijli/connection katne ki dhamki" to EnglishCopy("Utility disconnection threat", "The message threatens to disconnect a utility or service."),
        "Personal number par call karne ko kaha" to EnglishCopy("Asked you to call a personal number", "The message directs you to a personal number instead of an independently verified official channel."),
        "Bank/service ke naam par personal number par call karne ko kaha" to EnglishCopy("Personal callback number using a bank or service name", "The message uses a bank or service name while directing you to a personal number."),
        "Unofficial link par KYC/detail update karne ko kaha" to EnglishCopy("KYC update requested through an unofficial link", "The message asks you to update KYC or personal details through a link."),
        "Bank/service ke naam par personal mobile number" to EnglishCopy("Personal mobile number using a bank or service name", "The message associates a personal mobile number with a bank or service."),
        "Message mein link hai (khula nahi gaya)" to EnglishCopy("Message contains a link", "A link was detected. The app did not open or visit it."),
        "Digital arrest ka naam liya gaya" to EnglishCopy("Digital arrest claim", "The sender mentions digital arrest, which is not a legal process in India."),
        "Video call par bane rehne ko kaha" to EnglishCopy("Told you to stay on a video call", "The message instructs you to remain on a video call."),
        "Camera band rakhne ko kaha" to EnglishCopy("Told you to keep the camera off", "The message asks you to keep the camera off, which can conceal isolation."),
        "Ghar walon ko batane se roka" to EnglishCopy("Told you not to tell your family", "The sender asks you to keep the matter from family or trusted people."),
        "Kamre mein akele rehne ko kaha" to EnglishCopy("Told you to stay alone in a room", "The message tells you to stay alone and avoid outside advice."),
        "Nakli court order ya arrest warrant" to EnglishCopy("Fake court order or arrest warrant", "The message refers to a court order or arrest warrant as part of a digital-arrest pattern."),
        "FD tod kar transfer karne ko kaha" to EnglishCopy("Told you to break a fixed deposit and transfer money", "The message asks you to break a fixed deposit and transfer the funds."),
        "OTP/code share karne se mana kiya gaya hai" to EnglishCopy("Warns you not to share a code", "The message advises you not to share an OTP, PIN, or code."),
        "Bank khud OTP kabhi nahi maangta" to EnglishCopy("Says the bank never asks for an OTP", "The message gives protective advice that a bank will not ask for an OTP or PIN."),
        "Message sirf jaankari ke liye hai" to EnglishCopy("Information only", "The message says it is informational and does not ask you to act."),
        "Credit/balance alert - koi maang nahi" to EnglishCopy("Credit or balance notice", "This is a credit or balance notice and contains no request to send money or share a credential."),
        "Official channel - branch ya net banking" to EnglishCopy("Official channel", "The message points you to a branch, official app, or net banking rather than an unknown link."),
        "Bill ki due date di gayi hai" to EnglishCopy("Bill due-date reminder", "The message gives a routine bill due date without a disconnection threat."),
        "Delivery OTP darwaze par dene ke liye" to EnglishCopy("Delivery OTP for the doorstep handover", "The OTP is for an in-person delivery handover, not for a caller."),
        "Warning ke andar scam ka example quote kiya gaya hai" to EnglishCopy("Scam example quoted in a warning", "The message quotes a scam example and warns you about it."),
    )

    private val actionCopy = mapOf(
        "Abhi ruk jaayein: OTP, PIN, password ya paise mat dein." to "Stop for now. Do not share an OTP, PIN, password, or money.",
        "Bank ya police ko official number par khud call back karke verify karein." to "Call the bank or police using a number you find independently and verify the request.",
        "Paise bhej diye hon to turant 1930 par report karein." to "If you sent money, report it promptly by calling 1930.",
        "Is message mein code ya paise maange gaye hain. Kisi ko OTP, PIN ya paise na dein." to "This message asks for a code or money. Do not share an OTP, PIN, or money.",
        "Yeh message akele khatra nahi dikhata. Anjaan link par OTP ya paise phir bhi na dein." to "This message alone does not show a clear threat. Still, do not share an OTP or money through an unknown link.",
        "Message dabav ya dhamki bana raha hai. Turant kuch na karein: bank ya department ke official number/app se khud verify karein, aur OTP, PIN ya paise kisi ko na dein." to "The message uses pressure or threats. Pause and verify through the bank or department's official number or app. Do not share an OTP, PIN, or money.",
        "Hum ise poora check nahi kar sake. OTP, PIN ya paise kisi ko na dein." to "We could not fully check this message. Do not share an OTP, PIN, or money.",
        LocalScorer.ACTION_UNABLE_RETRY to "Rewrite the text in Roman or Devanagari Hindi, then check it again.",
    )

    fun evidence(item: Evidence): ResultEvidenceInput {
        val english = evidenceCopy[item.titleHinglish]
            ?: EnglishCopy("Signal detected", "This signal is shown with its exact quote and contributes to the overall assessment.")
        return ResultEvidenceInput(
            id = item.id,
            module = when (item.module) {
                "stage_rules" -> "Pattern check"
                "legit_indicators" -> "Legitimate signal"
                "local_fusion" -> "Local analysis"
                else -> "Local check"
            },
            titleHinglish = item.titleHinglish,
            titleEnglish = english.title,
            detailHinglish = item.detail,
            detailEnglish = english.detail,
            severity = when (item.strength) {
                Strength.WEAK -> EvidenceSeverity.INFO
                Strength.MODERATE -> EvidenceSeverity.WATCH
                Strength.STRONG -> EvidenceSeverity.STRONG
            },
            quote = item.quote.orEmpty(),
        )
    }

    fun action(line: String, index: Int): ResultActionInput = ResultActionInput(
        id = "local-action-$index",
        labelHinglish = line,
        labelEnglish = actionCopy[line] ?: "Pause and verify through an official channel.",
        detailHinglish = "Yeh agla kadam aapke faisle ke liye hai.",
        detailEnglish = "This is a suggested next step; confirm important requests independently.",
        priority = if (index == 0) ActionPriority.PRIMARY else ActionPriority.SECONDARY,
    )

    fun limitation(line: String): String = when {
        line.startsWith(LocalScorer.LIMIT_UNSUPPORTED_SCRIPT_PREFIX) ->
            "This language is not fully supported yet: " + line.removePrefix(LocalScorer.LIMIT_UNSUPPORTED_SCRIPT_PREFIX)
        line == LocalScorer.LIMIT_TRUNCATED -> "The message was too long, so only its beginning was checked."
        line == LocalScorer.LIMIT_NO_TEXT -> "No text was available to check."
        line == LocalScorer.LIMIT_TOO_LITTLE -> "The text was too short for a reliable check."
        line == LocalScorer.LIMIT_LOW_ASR -> "The audio was unclear, so this result is less reliable."
        line == LocalScorer.LIMIT_NO_EVIDENCE -> "No scam pattern was found. That does not prove the message is 100% safe."
        line == LocalScorer.LIMIT_WINDOW_DROPPED ->
            "Older call content left the analysis window; only recent words were fully checked."
        else -> "Some parts of the message may not be fully represented in this result."
    }
}
