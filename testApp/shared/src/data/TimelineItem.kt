package scribe.demo.data

data class TimelineItem(
    val title: String,
    val detail: String,
    val payload: String,
    val success: Boolean,
)
