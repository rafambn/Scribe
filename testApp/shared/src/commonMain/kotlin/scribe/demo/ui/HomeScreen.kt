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
        isBusy = state.isBusy,
        busyLabel = state.busyLabel,
        outputMessage = state.outputMessage,
        statusMessage = state.statusMessage,
        isRetired = state.isRetired,
        ignitionMessage = state.ignitionMessage,
        activeScrollIds = state.activeScrollIds,
        archivistErrors = state.archivistErrors,
        lastRecord = state.lastRecord,
        timeline = state.timeline,
        onRunQuickScrollScenario = viewModel::runQuickScrollScenario,
        onRunSecondQuickScrollScenario = viewModel::runSecondQuickScrollScenario,
        onRunCheckoutScenario = viewModel::runCheckoutScenario,
        onRunInspectionScenario = viewModel::runInspectionScenario,
        onRunMarginScenario = viewModel::runMarginScenario,
        onRunJsonSerializationScenario = viewModel::runJsonSerializationScenario,
        onRunStringTemplateScenario = viewModel::runStringTemplateScenario,
        onRunArchivistScenario = viewModel::runArchivistScenario,
        onRunOverflowScenario = viewModel::runOverflowScenario,
        onRunArchivistFailureScenario = viewModel::runArchivistFailureScenario,
        onRehireMainScribe = viewModel::rehireMainScribe,
        onRunRetireScenario = viewModel::runRetireScenario,
        onRunPlanRetireScenario = viewModel::runPlanRetireScenario,
        onWireIgnitionScenario = viewModel::wireIgnitionScenario,
    )
}
