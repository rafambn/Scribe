import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import scribe.demo.ui.HomeScreen

fun main() = application {
    Window(onCloseRequest = ::exitApplication) {
        HomeScreen()
    }
}
