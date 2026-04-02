package scribe.demo

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun Screen() {
    val controller = remember { ShowcaseController() }
    val state by controller.state.collectAsState()

    DisposableEffect(controller) {
        onDispose { controller.close() }
    }

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
                HeroCard(state)
                StatusCard(state, controller)
                ActionGroup(
                    title = "Notes",
                    description = "Standalone events through the suspending note(...) API.",
                    buttons = listOf(
                        "Run note(...)" to controller::runNoteScenario,
                        "Run second note(...)" to controller::runFlingNoteScenario,
                    ),
                    enabled = !state.isBusy,
                )
                ActionGroup(
                    title = "Scrolls",
                    description = "Wide-event flows with generated/custom IDs, direct map writes, and margins.",
                    buttons = listOf(
                        "Checkout flow" to controller::runCheckoutScenario,
                        "Map read/remove" to controller::runInspectionScenario,
                        "Margins + seal(error)" to controller::runMarginScenario,
                    ),
                    enabled = !state.isBusy,
                )
                ActionGroup(
                    title = "OpenObserve Rendering Checks",
                    description = "Validate nested JSON object serialization and string-template rendering in logs.",
                    buttons = listOf(
                        "JSON object serialization" to controller::runJsonSerializationScenario,
                        "String template message" to controller::runStringTemplateScenario,
                    ),
                    enabled = !state.isBusy,
                )
                ActionGroup(
                    title = "Savers And Delivery",
                    description = "Use the three saver types, queue overflow behavior, and saver error handling.",
                    buttons = listOf(
                        "EntrySaver mixed flow" to controller::runEntrySaverScenario,
                        "Overflow demo" to controller::runOverflowScenario,
                        "Saver failure demo" to controller::runSaverFailureScenario,
                    ),
                    enabled = !state.isBusy,
                )
                ActionGroup(
                    title = "Shutdown And Safety",
                    description = "Use retire() shutdown flows and wire the onIgnition callback safely.",
                    buttons = listOf(
                        "Recreate Scribe" to controller::recreateMainScribe,
                        "retire() (light queue)" to controller::runRetireScenario,
                        "retire() with backlog" to controller::runPlanRetireScenario,
                        "Wire onIgnition" to controller::wireIgnitionScenario,
                    ),
                    enabled = !state.isBusy,
                )
                TimelineCard(state)
            }
        }
    }
}

@Composable
private fun HeroCard(state: ShowcaseUiState) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF14213D)),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Scribe + OpenObserve",
                color = Color(0xFFFFF7E6),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Guided demos for notes, wide events, margins, queue delivery, and saver behavior. Everything uploads into the ${state.streamName} stream.",
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
private fun StatusCard(state: ShowcaseUiState, controller: ShowcaseController) {
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("OpenObserve Status", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(state.connectionMessage, style = MaterialTheme.typography.bodyMedium)
            Text("Status: ${state.statusMessage}", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Scribe instance: ${if (state.isRetired) "retired" else "active"}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.isRetired) Color(0xFF9C2F2F) else Color(0xFF1D5C63),
            )
            Text("Ignition: ${state.ignitionMessage}", style = MaterialTheme.typography.bodyMedium)
            if (state.activeScrollIds.isNotEmpty()) {
                Text(
                    "Active scrolls: ${state.activeScrollIds.joinToString()}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (state.saverErrors.isNotEmpty()) {
                Text(
                    "Saver errors: ${state.saverErrors.joinToString()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF9C2F2F),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = controller::refreshConnection, enabled = !state.isBusy) {
                    Text("Refresh OpenObserve")
                }
                if (state.isBusy) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(state.busyLabel, style = MaterialTheme.typography.bodyMedium)
                    }
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
private fun TimelineCard(state: ShowcaseUiState) {
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Timeline", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("Last upload: ${state.lastUploadMessage}", style = MaterialTheme.typography.bodyMedium)
            if (state.lastPayload.isNotBlank()) {
                Surface(
                    color = Color(0xFF101820),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    SelectionContainer {
                        Text(
                            text = state.lastPayload,
                            modifier = Modifier.padding(14.dp),
                            color = Color(0xFFE9F1F7),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            state.timeline.forEachIndexed { index, item ->
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
