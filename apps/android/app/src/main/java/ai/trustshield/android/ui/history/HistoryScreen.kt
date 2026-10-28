package ai.trustshield.android.ui.history

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ai.trustshield.android.core.model.RiskLabel
import ai.trustshield.android.data.preferences.AppLanguage
import ai.trustshield.android.ui.theme.ToneCopy
import ai.trustshield.android.ui.theme.RiskTone
import ai.trustshield.android.ui.theme.accent
import ai.trustshield.android.ui.theme.onSurface
import ai.trustshield.android.ui.theme.TsTokens

@Composable
fun HistoryScreen(
    entries: List<MetadataCheck>,
    language: AppLanguage,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val copy: (ToneCopy) -> String = { it.forLanguage(language) }
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                copy(ToneCopy("Recent checks", "Recent checks", "हाल की जाँच")),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() }
            )
            if (entries.isNotEmpty()) {
                TextButton(onClick = onClear, modifier = Modifier.height(TsTokens.MinTouchTarget), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
                    Text(copy(ToneCopy("Clear all", "Clear all", "सभी हटाएँ")))
                }
            }
        }
        if (entries.isEmpty()) {
            EmptyHistory(copy)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(entries) { item ->
                    HistoryRow(item = item, language = language)
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(item: MetadataCheck, language: AppLanguage) {
    val context = LocalContext.current
    val time = androidx.compose.runtime.remember(context, item.checkedAtMillis) {
        DateFormat.getTimeFormat(context).format(item.checkedAtMillis)
    }
    val (label, riskTone) = when (item.verdict) {
        RiskLabel.LOW_RISK -> ToneCopy("Kam khatra", "Low risk", "कम जोखिम") to RiskTone.GREEN
        RiskLabel.CAUTION -> ToneCopy("Dhyan dein", "Caution", "सावधानी") to RiskTone.AMBER
        RiskLabel.SUSPICIOUS -> ToneCopy("Sanket mile", "Suspicious", "संदिग्ध") to RiskTone.RED
        RiskLabel.HIGH_RISK -> ToneCopy("Zyada khatra", "High risk", "उच्च जोखिम") to RiskTone.RED
        RiskLabel.UNABLE_TO_VERIFY -> ToneCopy("Jaanch nahi ho saki", "Unable to verify", "जाँच नहीं हो सकी") to RiskTone.NEUTRAL
    }
    val dotTone = if (riskTone == RiskTone.NEUTRAL) MaterialTheme.colorScheme.onSurfaceVariant else riskTone.accent()
    val labelTone = if (riskTone == RiskTone.NEUTRAL) MaterialTheme.colorScheme.onSurfaceVariant else riskTone.onSurface()
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(TsTokens.CornerSmall),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = TsTokens.MinTouchTarget).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Canvas(Modifier.size(12.dp)) { drawCircle(dotTone) }
            Column(Modifier.weight(1f)) {
                Text(label.forLanguage(language), style = MaterialTheme.typography.titleSmall, color = labelTone)
                Text(time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun EmptyHistory(copy: (ToneCopy) -> String) {
    val illustrationFill = MaterialTheme.colorScheme.secondaryContainer
    val illustrationStroke = MaterialTheme.colorScheme.onSurface
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Canvas(Modifier.size(72.dp)) {
            drawCircle(illustrationFill, size.minDimension / 2)
            drawCircle(illustrationStroke, size.minDimension * .31f, style = Stroke(2.dp.toPx()))
            drawLine(illustrationStroke, androidx.compose.ui.geometry.Offset(size.width * .5f, size.height * .5f), androidx.compose.ui.geometry.Offset(size.width * .5f, size.height * .31f), 2.dp.toPx())
            drawLine(illustrationStroke, androidx.compose.ui.geometry.Offset(size.width * .5f, size.height * .5f), androidx.compose.ui.geometry.Offset(size.width * .66f, size.height * .57f), 2.dp.toPx())
        }
        Text(
            copy(ToneCopy("Abhi koi check nahi", "No checks yet", "अभी कोई जाँच नहीं")),
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            copy(ToneCopy("Koi message check karenge to yahan sirf uska time, verdict aur score dikhega.", "When you check a message, only its time, verdict and score appear here.", "संदेश जाँचने पर यहाँ केवल समय, परिणाम और स्कोर दिखेंगे।")),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
