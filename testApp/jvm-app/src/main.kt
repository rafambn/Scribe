import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import scribe.demo.Screen

fun main() = application {
    Window(onCloseRequest = ::exitApplication) {
        Screen()
    }
}
