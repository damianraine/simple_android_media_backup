package app.carry.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.carry.AppContainer
import app.carry.core.model.FoundDrive
import app.carry.data.explain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun FindScreen(container: AppContainer, navigator: Navigator, draftPad: DraftPad) {
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("looking on this network") }
    var drives by remember { mutableStateOf<List<FoundDrive>>(emptyList()) }
    var pending by remember { mutableStateOf<FoundDrive?>(null) }
    var typed by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        scanning = true
        val found = runCatching { container.discover.scan { status = it } }.getOrElse {
            status = explain(it)
            emptyList()
        }
        drives = found
        scanning = false
        if (found.isEmpty() && status.startsWith("checking")) status = "nothing answered on port 445"
    }

    fun consider(drive: FoundDrive) {
        pending = drive
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Wordmark(40.sp)
        Text(
            if (scanning) "Searching for destinations..." else "Destinations",
            color = Mist,
            fontSize = 28.sp,
            fontWeight = FontWeight.Black,
        )
        Text(status, color = Sky, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
        if (scanning) CircularProgressIndicator(color = Sky)
        drives.forEach { drive ->
            FocusCard(onClick = { if (!busy) consider(drive) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(drive.name, color = Mist, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(drive.host, color = Dim, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                }
            }
        }
        if (!scanning && drives.isEmpty()) {
            Text("Nothing answered. Type an address.", color = Dim, fontSize = 16.sp)
        }
        Field("address", typed) { typed = it }
        Pill("Use this address") {
            val drive = container.discover.typed(typed)
            if (drive == null) problem = "That address cannot be used." else consider(drive)
        }
        problem?.let { Text(it, color = Coral, fontSize = 16.sp) }
        if (navigator.canBack) Pill("Back") { navigator.back() }
    }

    pending?.let { drive ->
        Notice(
            title = "Do you want to add this as a saved destination?",
            body = "${drive.name} · ${drive.host}",
            confirm = "Add it",
            dismiss = "Not this one",
            onConfirm = {
                if (busy) return@Notice
                busy = true
                problem = null
                val chosen = drive
                pending = null
                scope.launch {
                    val opened = withContext(Dispatchers.IO) {
                        runCatching { container.smb.shares(chosen.host, null, null, null) }
                    }
                    busy = false
                    opened.onSuccess {
                        draftPad.draft = Draft(host = chosen.host, label = chosen.name)
                        navigator.push(Route.Browse)
                    }.onFailure { error ->
                        val text = explain(error)
                        if (text.contains("password", ignoreCase = true) || text.contains("refused", ignoreCase = true)) {
                            draftPad.draft = Draft(host = chosen.host, label = chosen.name)
                            navigator.push(Route.SignIn)
                        } else {
                            problem = text
                        }
                    }
                }
            },
            onDismiss = { pending = null },
        )
    }
}

@Composable
fun SignInScreen(container: AppContainer, navigator: Navigator, draftPad: DraftPad) {
    val draft = draftPad.draft
    val scope = rememberCoroutineScope()
    var username by remember(draft?.host) { mutableStateOf(draft?.username.orEmpty()) }
    var password by remember(draft?.host) { mutableStateOf(draft?.password.orEmpty()) }
    var domain by remember(draft?.host) { mutableStateOf(draft?.domain.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    if (draft == null) {
        LaunchedEffect(Unit) { navigator.back() }
        return
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Wordmark(40.sp)
        Text(draft.label, color = Mist, fontSize = 28.sp, fontWeight = FontWeight.Black)
        Text(draft.host, color = Dim, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
        Text("This drive wants a name and password.", color = Dim, fontSize = 16.sp)
        Field("name", username) { username = it }
        Field("password", password, secret = true) { password = it }
        Field("domain, if it asks", domain) { domain = it }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Pill(if (busy) "signing in" else "sign in") {
                if (busy) return@Pill
                busy = true
                problem = null
                scope.launch {
                    val opened = withContext(Dispatchers.IO) {
                        runCatching { container.smb.shares(draft.host, username, password, domain) }
                    }
                    busy = false
                    opened.onSuccess {
                        draftPad.draft = draft.copy(
                            username = username.ifBlank { null },
                            password = password.ifBlank { null },
                            domain = domain.ifBlank { null },
                        )
                        navigator.push(Route.Browse)
                    }.onFailure { problem = explain(it, listOf(password)) }
                }
            }
        }
        problem?.let { Text(it, color = Coral, fontSize = 16.sp) }
    }
}
