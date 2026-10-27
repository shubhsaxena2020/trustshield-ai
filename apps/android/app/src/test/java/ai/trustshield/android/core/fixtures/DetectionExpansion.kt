package ai.trustshield.android.core.fixtures

import ai.trustshield.android.core.model.RiskLabel

/** Balanced, synthetic extension set. Each family has six scam and six legitimate messages. */
internal object DetectionExpansion {
    private data class Family(
        val name: String,
        val scams: List<String>,
        val legitimate: List<String>,
    )

    private val families = listOf(
        Family(
            "Digital arrest",
            listOf(
                "CBI officer bol raha hoon. Money laundering case mein arrest warrant hai. Kisi ko mat batana, abhi Rs 20,000 bhejo.",
                "Police cyber unit: your parcel is linked to drugs. Stay on video call and share the OTP now or face arrest.",
                "मैं पुलिस अधिकारी हूँ। आपके नाम वारंट है, किसी को मत बताना और अभी १५००० रुपये भेजो।",
                "CBI se bol raha hoon, aap digital arrest mein hain. Call mat kaatna, verification ke liye UPI PIN batao.",
                "Your Aadhaar is tied to a criminal case. Keep this confidential and transfer Rs 8,000 to clear the warrant today.",
                "आपके खिलाफ गिरफ्तारी वारंट है। परिवार को मत बताना, केस बंद करने के लिए अभी ओटीपी बताइए।",
            ),
            listOf(
                "RBI says police never ask for money or OTP on a video call. Verify any claim through an official number.",
                "Digital arrest is a scam. Do not send money or share an OTP; call 1930 if you need help.",
                "किसी भी पुलिस कॉल पर पैसे या ओटीपी न दें। आधिकारिक नंबर से खुद जांच करें।",
                "CBI cannot arrest you over a video call. Never transfer money to a so-called verification account.",
                "अगर कोई केस के नाम पर पैसे मांगे तो कॉल काटकर परिवार और पुलिस हेल्पलाइन से संपर्क करें।",
                "Police advisory: no agency asks you to stay alone on a call or pay to close a case.",
            ),
        ),
        Family(
            "KYC/bank",
            listOf(
                "Your SBI account will be blocked tonight. Update KYC on this link and share the OTP within one hour.",
                "Aapka bank KYC expire ho gaya, abhi link kholo aur account bachane ke liye OTP batao.",
                "आपका बैंक खाता आज बंद होगा। केवाईसी अपडेट करने के लिए ओटीपी अभी भेजें।",
                "HDFC KYC department: account suspend hoga, turant is personal number par call karke PIN verify karein.",
                "Final bank alert: verify your password and OTP now or your account will be frozen today.",
                "आपकी केवाईसी रद्द है, खाता बचाने के लिए अभी दिए नंबर पर फोन करें और कोड बताएं।",
            ),
            listOf(
                "Your KYC is due next month. Please visit your bank branch or use the official banking app.",
                "Bank statement ready: view your transactions in the official app whenever convenient.",
                "बैंक की सूचना: केवाईसी के लिए शाखा में पहचान पत्र लेकर आएं, ओटीपी किसी को न बताएं।",
                "Your account is active. Contact the number printed on your debit card for support.",
                "Aapki bank passbook branch mein taiyar hai, working hours mein collect kar sakte hain.",
                "एसबीआई में आपका बचत खाता खुल गया है। विवरण बैंक शाखा से प्राप्त करें।",
            ),
        ),
        Family(
            "Electricity/bill disconnection",
            listOf(
                "Electricity office: power will be cut tonight for unpaid bill. Call this mobile now and pay Rs 2,000 to reconnect.",
                "Aapka bijli connection 9 baje kat jayega, turant personal number par call karke bill bhejo.",
                "आपका बिजली कनेक्शन आज काट दिया जाएगा। अभी रुपये भेजें और बिजली फिर चालू होगी।",
                "Gas supply will stop in two hours. Pay the reconnection fee through this UPI ID immediately.",
                "Last warning: your meter will be disconnected today unless you transfer the pending amount now.",
                "बिजली बिल न भरने पर आज रात कनेक्शन कटेगा, अभी रुपये भेजें।",
            ),
            listOf(
                "Your electricity bill is due on 15 November. Pay in the official app or at a service centre.",
                "Gas bill payment received. Thank you; no action is needed on this receipt.",
                "बिजली बिल की अंतिम तारीख १५ नवंबर है। भुगतान आधिकारिक पोर्टल या कार्यालय में करें।",
                "Scheduled power maintenance tomorrow from 10 am to noon. No payment or callback is required.",
                "Aapka monthly bill Rs 840 hai, official bijli app mein dekh sakte hain.",
                "पिछले महीने का बिजली भुगतान प्राप्त हुआ। रसीद अपने खाते में देखें।",
            ),
        ),
        Family(
            "UPI collect/refund",
            listOf(
                "Your refund is ready. Scan this QR and enter your UPI PIN now to receive Rs 3,000.",
                "Cashback mila hai, collect request approve karo aur paise pane ke liye PIN daalo.",
                "रिफंड पाने के लिए यह क्यूआर स्कैन करें और यूपीआई पिन अभी बताएं।",
                "Galti se Rs 5,000 bheje, refund ke liye is UPI collect request ko turant approve karo.",
                "UPI support here: share your PIN and approve this collect request or your refund will expire.",
                "आपका कैशबैक अटका है। पैसे पाने के लिए कलेक्ट अनुरोध स्वीकार करें और पिन डालें।",
            ),
            listOf(
                "A friend sent Rs 500 to your UPI. Check your bank app; you do not need to enter a PIN to receive it.",
                "Refund of Rs 240 processed to your original payment method. No action is required.",
                "यूपीआई से पैसे पाने के लिए पिन नहीं डालना पड़ता। अनजान कलेक्ट अनुरोध अस्वीकार करें।",
                "Payment received from your contact. Verify the credit in your bank app before replying.",
                "Aapka merchant refund original payment method mein aa gaya, statement check kar lena.",
                "व्यापारी ने आपका भुगतान वापस कर दिया है। बैंक विवरण में जमा राशि देखें।",
            ),
        ),
        Family(
            "Job offers",
            listOf(
                "You are selected for a work-from-home job. Pay Rs 499 registration fee now to start earning tomorrow.",
                "Ghar baithe job pakki hai, roz Rs 3000 kamao. Aaj hi registration fee bhejo warna offer chala jayega.",
                "आपको नौकरी मिली है। नियुक्ति पक्की करने के लिए अभी ₹५०० शुल्क भेजें।",
                "Part-time job offer: deposit Rs 1,000 for training today and send the payment screenshot to HR.",
                "Congratulations, selected! Transfer the refundable joining deposit now to claim your monthly salary.",
                "घर से काम के लिए चुने गए हैं, सीट बचाने हेतु पंजीकरण राशि अभी भेजें।",
            ),
            listOf(
                "Interview invitation for Tuesday at our office. There is no application or registration fee.",
                "Your application was received. Shortlisting results will be shared by email next week.",
                "नौकरी का साक्षात्कार शुक्रवार को है। कोई पंजीकरण शुल्क नहीं है, पहचान पत्र साथ लाएं।",
                "Offer letter attached by our verified company careers portal. Joining is free of charge.",
                "Aapka interview kal 11 baje hai, office address official email mein diya gaya hai.",
                "आवेदन प्राप्त हुआ। चयन प्रक्रिया और नौकरी के लिए कोई शुल्क नहीं लिया जाता।",
            ),
        ),
        Family(
            "Parcel/customs",
            listOf(
                "Customs seized your parcel with illegal items. Pay Rs 2,400 now or police will arrest you.",
                "Courier se bol raha hoon, parcel customs mein ruka hai. OTP phone par batao warna delivery cancel hogi.",
                "आपके पार्सल में प्रतिबंधित सामान मिला। जुर्माने के २५०० रुपये अभी भेजें नहीं तो केस दर्ज होगा।",
                "Your shipment is on hold. Transfer the customs clearance fee to this UPI today to avoid a legal case.",
                "Parcel mein drugs mile hain. Kisi ko mat batana, release ke liye OTP aur Rs 900 bhejo.",
                "सीमा शुल्क विभाग बोल रहा है, आपका पैकेट रोका है। अभी शुल्क दें और कोड बताएं।",
            ),
            listOf(
                "Your parcel is out for delivery today. Track its status in the courier's official app.",
                "Customs duty was paid at checkout. Your international package is expected Friday.",
                "आपका पार्सल रास्ते में है। स्थिति आधिकारिक कुरियर वेबसाइट पर देखें।",
                "Delivery OTP 4821, share only with the agent at your door.",
                "Aapka package kal deliver hoga, address update official courier app mein kar sakte hain.",
                "कुरियर रसीद में अनुमानित डिलीवरी की तारीख दी गई है। कोई अतिरिक्त भुगतान बाकी नहीं।",
            ),
        ),
        Family(
            "Loan apps",
            listOf(
                "Instant loan approved. Install this APK and pay the processing fee now to release your loan.",
                "Loan milne se pehle Rs 700 advance bhejo aur contacts access dene wala app install karo.",
                "ऋण स्वीकृत है। राशि पाने के लिए अभी प्रसंस्करण शुल्क भेजें और ऐप डाउनलोड करें।",
                "Your loan will be cancelled in one hour. Share your OTP and pay insurance fee to unlock funds.",
                "Guaranteed loan today, first transfer verification charge to this personal UPI ID.",
                "तुरंत कर्ज पाने के लिए पहले शुल्क जमा करें और बैंक ओटीपी बताएं।",
            ),
            listOf(
                "Loan application received. Review the APR and repayment terms in the official bank app.",
                "Your monthly loan instalment is due next week. Pay through the lender's official portal.",
                "ऋण आवेदन की स्थिति आधिकारिक बैंक वेबसाइट पर देखें। आवेदन के लिए कोई अग्रिम शुल्क नहीं।",
                "Loan statement available in your account. Contact the lender using the number on its website.",
                "Aapki loan EMI jama ho gayi, receipt bank app mein dekh sakte hain.",
                "आपकी ऋण किस्त जमा हो गई। रसीद अपने बैंक खाते में सुरक्षित रखें।",
            ),
        ),
        Family(
            "OTP sharing",
            listOf(
                "Bank verification ke liye abhi OTP batao, warna account block ho jayega.",
                "Your parcel will be cancelled. Read the delivery OTP to me over the phone now.",
                "आपका कार्ड बंद होगा। पुष्टि के लिए बैंक ओटीपी अभी बताइए।",
                "KYC agent here, share the six digit code now to keep your SIM active.",
                "Do not hang up; tell me the OTP you received or your payment will fail today.",
                "पुरस्कार लेने के लिए आया हुआ ओटीपी अभी भेजें, जल्दी करें।",
            ),
            listOf(
                "Your login OTP is 654321. Never share it with anyone, including bank staff.",
                "Delivery code is for the verified agent at your door only. Do not read it over a call.",
                "बैंक आपको फोन पर ओटीपी नहीं पूछेगा। इसे किसी को न बताएं।",
                "A one-time code was sent for your sign-in. If you did not request it, ignore the message.",
                "Aapka transaction OTP hai, kisi ke saath share mat karein.",
                "सुरक्षा सूचना: अपना पिन और ओटीपी गोपनीय रखें।",
            ),
        ),
        Family(
            "Investment/crypto",
            listOf(
                "Guaranteed crypto returns of 40 percent this week. Transfer Rs 10,000 now to activate your profit.",
                "Double paisa daily chahiye? Crypto account mein deposit karo, offer sirf aaj tak hai.",
                "निवेश पर पक्का मुनाफा मिलेगा। खाता सक्रिय करने के लिए अभी रुपये भेजें।",
                "Our analyst guarantees profit. Send your seed phrase and fund this wallet before midnight.",
                "Bitcoin investment slot closing, pay joining fee now or lose the assured return.",
                "क्रिप्टो में पैसा आज इस वॉलेट में जमा करें और दोगुना मुनाफा पाएं।",
            ),
            listOf(
                "Investment values can rise or fall. Read the official risk disclosure before deciding.",
                "Your mutual fund statement is ready. View it through your registered investment account.",
                "निवेश में जोखिम होता है। किसी भी योजना की जानकारी आधिकारिक दस्तावेज़ से जांचें।",
                "Crypto is volatile; no return is guaranteed. Never share a wallet recovery phrase.",
                "Aapka SIP installment receive hua, portfolio statement official app par available hai.",
                "आपके निवेश की वार्षिक रिपोर्ट उपलब्ध है। इसे अपने पंजीकृत खाते में देखें।",
            ),
        ),
        Family(
            "Lottery",
            listOf(
                "You won Rs 8 lakh in a lucky draw. Pay Rs 2,000 tax by UPI now to claim the prize.",
                "KBC lottery mein naam nikla, inaam pane ke liye processing fee abhi bhejo.",
                "आपने लॉटरी में पुरस्कार जीता। रकम पाने के लिए अभी शुल्क जमा करें।",
                "You won a lottery prize. Pay the clearance fee today to receive it.",
                "Aapko lucky draw cashback mila, release ke liye OTP aur Rs 500 bhejo.",
                "लॉटरी इनाम लेने के लिए गोपनीय कोड बताएं और दावा शुल्क अभी भेजें।",
            ),
            listOf(
                "Lottery results will be posted on the official site; no purchase or payment is required.",
                "Your raffle entry was received. Winners will be announced publicly next month; no fee is due.",
                "इनाम के नाम पर मांगे गए पैसे न भेजें। असली पुरस्कार के लिए ओटीपी जरूरी नहीं।",
                "Contest results are published on our official website. We never charge a prize release fee.",
                "Aapke event ticket par raffle number hai, result organizer ki site par check karein.",
                "प्रतियोगिता का परिणाम आधिकारिक पृष्ठ पर घोषित होगा। पुरस्कार का दावा निःशुल्क है।",
            ),
        ),
    )

    private val languages = listOf(
        "Hinglish", "English", "Hindi (Devanagari)",
        "Hinglish", "English", "Hindi (Devanagari)",
    )

    val rows: List<Fixture> = families.flatMapIndexed { familyIndex, family ->
        val scamRows = family.scams.mapIndexed { index, text ->
            Fixture(
                id = "N%02d-S%02d".format(familyIndex + 1, index + 1),
                family = family.name,
                language = languages[index],
                kind = FixtureKind.SCAM,
                expected = RiskLabel.CAUTION,
                specBasis = "Labeled scam; detection target is at least CAUTION (score >=25).",
                text = text,
            )
        }
        val legitimateRows = family.legitimate.mapIndexed { index, text ->
            Fixture(
                id = "N%02d-L%02d".format(familyIndex + 1, index + 1),
                family = family.name,
                language = languages[index],
                kind = FixtureKind.LEGITIMATE,
                expected = RiskLabel.LOW_RISK,
                specBasis = "Labeled legitimate control; must remain LOW_RISK (score <=24).",
                text = text,
            )
        }
        scamRows + legitimateRows
    }
}
