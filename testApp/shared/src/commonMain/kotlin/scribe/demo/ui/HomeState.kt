package scribe.demo.ui

import scribe.demo.data.TimelineItem

data class HomeState(
    val isBusy: Boolean = false,
    val busyLabel: String = "",
    val statusMessage: String = "Ready to run demo scenarios.",
    val isRetired: Boolean = false,
    val activeScrollIds: List<String> = emptyList(),
    val lastRecord: String = "",
    val outputMessage: String = "Records are written to the application console.",
    val archivistErrors: List<String> = emptyList(),
    val timeline: List<TimelineItem> = emptyList(),
    val ignitionMessage: String = "The onIgnition hook is wired, but the demo does not crash itself to trigger it.",
)