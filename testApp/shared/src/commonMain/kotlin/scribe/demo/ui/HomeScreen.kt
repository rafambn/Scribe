package scribe.demo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember

@Composable
fun HomeScreen() {
    val viewModel = remember { HomeViewModel() }
    val state by viewModel.state.collectAsState()

    DisposableEffect(viewModel) {
        onDispose { viewModel.close() }
    }

    HomeContent(
        state = state,
        onRunNoteScenario = viewModel::runNoteScenario,
        onRunFlingNoteScenario = viewModel::runFlingNoteScenario,
        onRunCheckoutScenario = viewModel::runCheckoutScenario,
        onRunInspectionScenario = viewModel::runInspectionScenario,
        onRunMarginScenario = viewModel::runMarginScenario,
        onRunJsonSerializationScenario = viewModel::runJsonSerializationScenario,
        onRunStringTemplateScenario = viewModel::runStringTemplateScenario,
        onRunEntrySaverScenario = viewModel::runEntrySaverScenario,
        onRunOverflowScenario = viewModel::runOverflowScenario,
        onRunSaverFailureScenario = viewModel::runSaverFailureScenario,
        onRehireMainScribe = viewModel::rehireMainScribe,
        onRunRetireScenario = viewModel::runRetireScenario,
        onRunPlanRetireScenario = viewModel::runPlanRetireScenario,
        onWireIgnitionScenario = viewModel::wireIgnitionScenario,
    )
}
