package app.carry.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import app.carry.AppContainer
import kotlinx.coroutines.delay

sealed interface Route {
    data object Boot : Route
    data object Find : Route
    data object SignIn : Route
    data object Browse : Route
    data object Home : Route
    data object Settings : Route
}

data class Draft(
    val existingId: String? = null,
    val host: String,
    val label: String,
    val username: String? = null,
    val password: String? = null,
    val domain: String? = null,
    val share: String? = null,
    val folder: String = "",
)

class DraftPad : ViewModel() {
    var draft by mutableStateOf<Draft?>(null)
}

class Navigator : ViewModel() {
    private val stack = ArrayDeque<Route>().apply { add(Route.Boot) }
    var route by mutableStateOf<Route>(Route.Boot)
        private set
    val canBack: Boolean get() = stack.size > 1

    fun replace(route: Route) {
        stack.clear()
        stack.add(route)
        this.route = route
    }

    fun push(route: Route) {
        stack.add(route)
        this.route = route
    }

    fun back() {
        if (stack.size > 1) {
            stack.removeLast()
            route = stack.last()
        }
    }
}

@Composable
fun CarryRoot(
    container: AppContainer,
    navigator: Navigator = viewModel(),
    draftPad: DraftPad = viewModel(),
) {
    val activity = LocalContext.current as? Activity
    var notice by remember { mutableStateOf<NoticeRequest?>(null) }
    BackHandler(enabled = true) {
        when {
            notice != null -> notice = null
            navigator.canBack -> navigator.back()
            else -> activity?.finish()
        }
    }
    Box(Modifier.fillMaxSize()) {
        when (navigator.route) {
            Route.Boot -> BootScreen(container, navigator)
            Route.Find -> FindScreen(container, navigator, draftPad)
            Route.SignIn -> SignInScreen(container, navigator, draftPad)
            Route.Browse -> BrowseScreen(container, navigator, draftPad)
            Route.Home -> HomeScreen(container, navigator)
            Route.Settings -> SettingsScreen(container, navigator, draftPad)
        }
        notice?.let { request ->
            Notice(
                title = request.title,
                body = request.body,
                confirm = request.confirm,
                dismiss = request.dismiss,
                onConfirm = {
                    notice = null
                    request.onConfirm()
                },
                onDismiss = { notice = null },
            )
        }
    }
}

data class NoticeRequest(
    val title: String,
    val body: String,
    val confirm: String,
    val dismiss: String?,
    val onConfirm: () -> Unit,
)

@Composable
private fun BootScreen(container: AppContainer, navigator: Navigator) {
    var line by remember { mutableStateOf("checking saved destinations") }
    LaunchedEffect(Unit) {
        delay(350)
        val saved = runCatching { container.settings.read() }.getOrNull()
        if (saved == null || saved.destinations.isEmpty()) {
            line = "Searching for destinations..."
            navigator.replace(Route.Find)
        } else {
            navigator.replace(Route.Home)
        }
    }
    Column(
        Modifier.fillMaxSize().background(Ink).padding(28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Wordmark()
        Text(line, color = Dim, fontFamily = FontFamily.Monospace, fontSize = 16.sp, modifier = Modifier.padding(top = 16.dp))
    }
}
