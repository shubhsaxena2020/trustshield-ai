package ai.trustshield.android.ui.onboarding

import android.animation.ValueAnimator
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ai.trustshield.android.data.preferences.AppLanguage
import ai.trustshield.android.data.preferences.resolveLanguage
import ai.trustshield.android.ui.theme.ToneCopy
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun OnboardingScreen(
    language: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pager = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()
    val resolved = resolveLanguage(language, Locale.getDefault().toLanguageTag())
    val reducedMotion = !ValueAnimator.areAnimatorsEnabled()

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = ToneCopy("TrustShield AI", "TrustShield AI", "TrustShield AI").forLanguage(resolved),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                OnboardingIllustration(page, MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.secondaryContainer)
                Spacer(Modifier.height(28.dp))
                Text(
                    text = title(page).forLanguage(resolved),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().semantics { heading() }
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = body(page).forLanguage(resolved),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                if (page == 2) {
                    Spacer(Modifier.height(22.dp))
                    LanguageChoices(language, resolved, onLanguageChange)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(3) { page ->
                Surface(
                    color = if (pager.currentPage == page) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    shape = CircleShape,
                    modifier = Modifier.size(if (pager.currentPage == page) 10.dp else 8.dp)
                ) { }
            }
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                if (pager.currentPage == 2) onComplete()
                else scope.launch {
                    if (reducedMotion) pager.scrollToPage(pager.currentPage + 1)
                    else pager.animateScrollToPage(pager.currentPage + 1)
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text(
                if (pager.currentPage == 2) {
                    ToneCopy("Shuru karein", "Get started", "शुरू करें").forLanguage(resolved)
                } else {
                    ToneCopy("Aage", "Next", "आगे").forLanguage(resolved)
                }
            )
        }
        if (pager.currentPage < 2) {
            OutlinedButton(colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                onClick = onComplete,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text(ToneCopy("Abhi nahi", "Skip", "छोड़ें").forLanguage(resolved)) }
        }
    }
}

@Composable
private fun LanguageChoices(
    selected: AppLanguage,
    language: AppLanguage,
    onSelect: (AppLanguage) -> Unit
) {
    val options = listOf(
        AppLanguage.SYSTEM to ToneCopy("System", "System", "सिस्टम"),
        AppLanguage.HINGLISH to ToneCopy("Hinglish", "Hinglish", "हिंग्लिश"),
        AppLanguage.ENGLISH to ToneCopy("English", "English", "अंग्रेज़ी"),
        AppLanguage.HINDI to ToneCopy("Hindi", "Hindi", "हिंदी")
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(ToneCopy("Bhasha", "Language", "भाषा").forLanguage(language), style = MaterialTheme.typography.titleSmall)
        options.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { (option, label) ->
                    if (selected == option) {
                        Button(onClick = { onSelect(option) }, modifier = Modifier.weight(1f)) { Text(label.forLanguage(language)) }
                    } else {
                        OutlinedButton(colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface), onClick = { onSelect(option) }, modifier = Modifier.weight(1f)) { Text(label.forLanguage(language)) }
                    }
                }
            }
        }
    }
}

private fun title(page: Int) = when (page) {
    0 -> ToneCopy("Pehle jaanch, phir bharosa", "Check before you trust", "भरोसा करने से पहले जाँचें")
    1 -> ToneCopy("Aapka message aapka hai", "Your message stays yours", "आपका संदेश आपके पास रहता है")
    else -> ToneCopy("Aapki bhasha, aapka control", "Your language, your control", "आपकी भाषा, आपका नियंत्रण")
}

private fun body(page: Int) = when (page) {
    0 -> ToneCopy(
        "Message ya link paste karein. TrustShield dikhayega ki kaun se risk signals mile.",
        "Paste a message or link to see which risk signals were found.",
        "संदेश या लिंक चिपकाएँ और देखें कि कौन से जोखिम संकेत मिले।"
    )
    1 -> ToneCopy(
        "Message check isi device par hota hai. History mein sirf verdict aur score save hote hain.",
        "Checks happen on this device. History stores only the verdict and score.",
        "जाँच इसी डिवाइस पर होती है। इतिहास में केवल नतीजा और स्कोर सहेजे जाते हैं।"
    )
    else -> ToneCopy(
        "Bhasha chunein. Camera ya microphone ki permission sirf us feature ko kholne par maangi jayegi.",
        "Choose a language. Camera or microphone access is requested only when you open that feature.",
        "भाषा चुनें। कैमरा या माइक्रोफ़ोन की अनुमति संबंधित सुविधा खोलने पर ही माँगी जाएगी।"
    )
}

@Composable
private fun OnboardingIllustration(page: Int, primary: Color, container: Color) {
    Surface(
        color = container,
        shape = RoundedCornerShape(32.dp),
        modifier = Modifier.size(190.dp)
    ) {
        Canvas(Modifier.fillMaxSize().padding(34.dp)) {
            val center = Offset(size.width / 2, size.height / 2)
            when (page) {
                0 -> {
                    drawCircle(primary.copy(alpha = .12f), size.minDimension * .46f, center)
                    val path = Path().apply {
                        moveTo(center.x, size.height * .12f)
                        lineTo(size.width * .78f, size.height * .27f)
                        lineTo(size.width * .72f, size.height * .60f)
                        quadraticTo(center.x, size.height * .89f, size.width * .28f, size.height * .60f)
                        lineTo(size.width * .22f, size.height * .27f)
                        close()
                    }
                    drawPath(path, primary)
                    drawLine(Color.White, Offset(size.width * .38f, size.height * .48f), Offset(size.width * .47f, size.height * .57f), 4.dp.toPx())
                    drawLine(Color.White, Offset(size.width * .47f, size.height * .57f), Offset(size.width * .65f, size.height * .38f), 4.dp.toPx())
                }
                1 -> {
                    drawCircle(primary.copy(alpha = .12f), size.minDimension * .46f, center)
                    drawRoundRect(primary, Offset(size.width * .22f, size.height * .42f), Size(size.width * .56f, size.height * .42f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx()))
                    drawArc(primary, 180f, 180f, false, Offset(size.width * .33f, size.height * .16f), Size(size.width * .34f, size.height * .56f), style = Stroke(5.dp.toPx()))
                    drawCircle(Color.White, 4.dp.toPx(), Offset(center.x, size.height * .59f))
                }
                else -> {
                    drawCircle(primary.copy(alpha = .12f), size.minDimension * .46f, center)
                    drawRoundRect(primary, Offset(size.width * .18f, size.height * .2f), Size(size.width * .64f, size.height * .6f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(18.dp.toPx()))
                    for (index in 0..2) {
                        drawLine(Color.White.copy(alpha = .92f), Offset(size.width * .3f, size.height * (.36f + .14f * index)), Offset(size.width * .7f, size.height * (.36f + .14f * index)), 3.dp.toPx())
                    }
                }
            }
        }
    }
}
