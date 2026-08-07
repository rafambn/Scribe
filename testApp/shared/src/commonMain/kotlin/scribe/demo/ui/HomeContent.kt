package scribe.demo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import scribe.demo.data.TimelineItem
import scribe.demo.platformName

@Composable
fun HomeContent(
    isBusy: Boolean,
    busyLabel: String,
    outputMessage: String,
    statusMessage: String,
    isRetired: Boolean,
    ignitionMessage: String,
    activeScrollIds: List<String>,
    archivistErrors: List<String>,
    lastRecord: String,
    timeline: List<TimelineItem>,
    onRunQuickScrollScenario: () -> Unit,
    onRunSecondQuickScrollScenario: () -> Unit,
    onRunCheckoutScenario: () -> Unit,
    onRunInspectionScenario: () -> Unit,
    onRunMarginScenario: () -> Unit,
    onRunJsonSerializationScenario: () -> Unit,
    onRunStringTemplateScenario: () -> Unit,
    onRunArchivistScenario: () -> Unit,
    onRunOverflowScenario: () -> Unit,
    onRunArchivistFailureScenario: () -> Unit,
    onRehireMainScribe: () -> Unit,
    onRunRetireScenario: () -> Unit,
    onRunPlanRetireScenario: () -> Unit,
    onWireIgnitionScenario: () -> Unit,
) {
    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFFF6F1E8),
                            Color(0xFFE8F0EE),
                            Color(0xFFF8F6F2),
                        ),
                    ),
                ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                HeroCard()
                StatusCard(
                    outputMessage = outputMessage,
                    statusMessage = statusMessage,
                    isRetired = isRetired,
                    ignitionMessage = ignitionMessage,
                    activeScrollIds = activeScrollIds,
                    archivistErrors = archivistErrors,
                    isBusy = isBusy,
                    busyLabel = busyLabel,
                )
                ActionGroup(
                    title = "Quick Scrolls",
                    description = "Immediately sealed one-shot scroll events.",
                    buttons = listOf(
                        "Emit quick scroll" to onRunQuickScrollScenario,
                        "Emit second quick scroll" to onRunSecondQuickScrollScenario,
                    ),
                    enabled = !isBusy,
                )
                ActionGroup(
                    title = "Scrolls",
                    description = "Wide-event flows with generated/custom IDs, direct map writes, and margins.",
                    buttons = listOf(
                        "Checkout flow" to onRunCheckoutScenario,
                        "Map read/remove" to onRunInspectionScenario,
                        "Margins + seal(failure)" to onRunMarginScenario,
                    ),
                    enabled = !isBusy,
                )
                ActionGroup(
                    title = "Console Rendering Checks",
                    description = "Validate nested JSON object serialization and string-template rendering in console records.",
                    buttons = listOf(
                        "JSON object serialization" to onRunJsonSerializationScenario,
                        "String template message" to onRunStringTemplateScenario,
                    ),
                    enabled = !isBusy,
                )
                ActionGroup(
                    title = "Archivists And Delivery",
                    description = "Use the archivist types, queue overflow behavior, and archivist error handling.",
                    buttons = listOf(
                        "Archivist mixed flow" to onRunArchivistScenario,
                        "Overflow demo" to onRunOverflowScenario,
                        "Archivist failure demo" to onRunArchivistFailureScenario,
                    ),
                    enabled = !isBusy,
                )
                ActionGroup(
                    title = "Shutdown And Safety",
                    description = "Use retire() shutdown flows and wire the onIgnition callback safely.",
                    buttons = listOf(
                        "Re-hire Scribe" to onRehireMainScribe,
                        "retire() (light queue)" to onRunRetireScenario,
                        "retire() with backlog" to onRunPlanRetireScenario,
                        "Wire onIgnition" to onWireIgnitionScenario,
                    ),
                    enabled = !isBusy,
                )
                TimelineCard(lastRecord, timeline)
            }
        }
    }
}

@Composable
private fun HeroCard() {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF14213D)),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Scribe Console Showcase",
                color = Color(0xFFFFF7E6),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Guided demos for quick scrolls, wide events, margins, queue delivery, and archivist behavior. Every delivered record is printed to the console.",
                color = Color(0xFFE7ECEF),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = "Platform: ${platformName()}",
                color = Color(0xFFFCA311),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun StatusCard(
    outputMessage: String,
    statusMessage: String,
    isRetired: Boolean,
    ignitionMessage: String,
    activeScrollIds: List<String>,
    archivistErrors: List<String>,
    isBusy: Boolean,
    busyLabel: String,
) {
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Console Output", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(outputMessage, style = MaterialTheme.typography.bodyMedium)
            Text("Status: $statusMessage", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Scribe instance: ${if (isRetired) "retired" else "active"}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (isRetired) Color(0xFF9C2F2F) else Color(0xFF1D5C63),
            )
            Text("Ignition: $ignitionMessage", style = MaterialTheme.typography.bodyMedium)
            if (activeScrollIds.isNotEmpty()) {
                Text(
                    "Active scrolls: ${activeScrollIds.joinToString()}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (archivistErrors.isNotEmpty()) {
                Text(
                    "Archivist errors: ${archivistErrors.joinToString()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF9C2F2F),
                )
            }
            if (isBusy) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(busyLabel, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun ActionGroup(
    title: String,
    description: String,
    buttons: List<Pair<String, () -> Unit>>,
    enabled: Boolean,
) {
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodyMedium)
            buttons.forEach { (label, action) ->
                OutlinedButton(
                    onClick = action,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(label)
                }
            }
        }
    }
}

@Composable
private fun TimelineCard(lastRecord: String, timeline: List<TimelineItem>) {
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Timeline", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("Last console record", style = MaterialTheme.typography.bodyMedium)
            if (lastRecord.isNotBlank()) {
                Surface(
                    color = Color(0xFF101820),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    SelectionContainer {
                        Text(
                            text = lastRecord,
                            modifier = Modifier.padding(14.dp),
                            color = Color(0xFFE9F1F7),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            timeline.forEachIndexed { index, item ->
                if (index > 0) {
                    HorizontalDivider()
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (item.success) Color(0xFF1D5C63) else Color(0xFF9C2F2F),
                    )
                    Text(item.detail, style = MaterialTheme.typography.bodyMedium)
                    if (item.payload.isNotBlank()) {
                        Surface(
                            color = Color(0xFFF2EFEA),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            SelectionContainer {
                                Text(
                                    item.payload,
                                    modifier = Modifier.padding(12.dp),
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
