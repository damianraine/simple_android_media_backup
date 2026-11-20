package app.carry.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import app.carry.core.model.Destination
import app.carry.core.sync.BackupPaths
import app.carry.core.sync.ShareNames
import app.carry.data.explain
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BrowseScreen(container: AppContainer, navigator: Navigator, draftPad: DraftPad) {
    val draft = draftPad.draft
    val scope = rememberCoroutineScope()
    var shares by remember(draft?.host) { mutableStateOf<List<String>?>(null) }
    var folders by remember(draft?.share, draft?.folder) { mutableStateOf<List<String>?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var shareName by remember { mutableStateOf("") }
    var folderName by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    if (draft == null) {
        LaunchedEffect(Unit) { navigator.back() }
        return
    }
    val current = draft

    LaunchedEffect(current.host, current.username, current.share) {
        if (current.share != null) return@LaunchedEffect
        problem = null
        val found = withContext(Dispatchers.IO) {
            runCatching {
                container.smb.shares(current.host, current.username, current.password, current.domain)
            }
        }
        found.onSuccess { shares = it }.onFailure { problem = explain(it, listOfNotNull(current.password)) }
    }

    LaunchedEffect(current.share, current.folder) {
        val share = current.share ?: return@LaunchedEffect
        folders = null
        problem = null
        val found = withContext(Dispatchers.IO) {
            runCatching {
                container.smb.folders(
                    current.host,
                    current.username,
                    current.password,
                    current.domain,
                    share,
                    current.folder,
                )
            }
        }
        found.onSuccess { folders = it }.onFailure { problem = explain(it, listOfNotNull(current.password)) }
    }

    fun save() {
        val share = current.share ?: return
        if (busy) return
        busy = true
        scope.launch {
            val id = current.existingId ?: UUID.randomUUID().toString()
            val destination = Destination(
                id = id,
                host = current.host,
                label = current.label.ifBlank { current.host },
                share = share,
                folder = BackupPaths.folder(current.folder),
                username = current.username?.ifBlank { null },
                domain = current.domain?.ifBlank { null },
            )
            container.settings.update { saved ->
                saved.copy(
                    destinations = saved.destinations.filterNot { it.id == id } + destination,
                    activeDestinationId = id,
                )
            }
            val password = current.password
            if (password.isNullOrEmpty()) container.vault.forgetPassword(id) else container.vault.setPassword(id, password)
            busy = false
            navigator.replace(Route.Home)
        }
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
        Text(current.label, color = Mist, fontSize = 28.sp, fontWeight = FontWeight.Black)
        Text(
            if (current.share == null) "Choose a share" else pathLabel(current.share, current.folder),
            color = Dim,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
        )
        Text(
            "Copied photos and videos go in the folder you pick. Something like media/backup/phone.",
            color = Dim,
            fontSize = 16.sp,
        )
        if (current.share == null) {
            if (shares == null && problem == null) CircularProgressIndicator(color = Sky)
            shares.orEmpty().forEach { name ->
                FocusCard(
                    onClick = { draftPad.draft = current.copy(share = name, folder = "") },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(name, color = Mist, fontSize = 18.sp, modifier = Modifier.padding(16.dp))
                }
            }
            Field("or type the share name", shareName) { shareName = it }
            Pill("Open share") {
                val tidy = ShareNames.tidy(shareName)
                if (tidy == null) problem = "That share name cannot be used."
                else draftPad.draft = current.copy(share = tidy, folder = "")
            }
        } else {
            if (current.folder.isNotEmpty()) {
                Pill("Up") {
                    val parent = current.folder.substringBeforeLast('/', "")
                    draftPad.draft = current.copy(folder = parent)
                }
            }
            if (folders == null && problem == null) CircularProgressIndicator(color = Sky)
            if (folders != null && folders!!.isEmpty()) {
                Text("Nothing in here yet. Use this folder, or make one.", color = Dim, fontSize = 16.sp)
            }
            folders.orEmpty().forEach { name ->
                FocusCard(
                    onClick = {
                        val next = listOf(current.folder, name).filter { it.isNotBlank() }.joinToString("/")
                        draftPad.draft = current.copy(folder = next)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(name, color = Mist, fontSize = 18.sp, modifier = Modifier.padding(16.dp))
                }
            }
            Pill(if (busy) "saving" else "Use this folder") { save() }
            Field("new folder", folderName) { folderName = it }
            Pill("Make a folder here") {
                val share = current.share
                if (folderName.isBlank() || busy) return@Pill
                busy = true
                scope.launch {
                    val made = withContext(Dispatchers.IO) {
                        runCatching {
                            container.smb.makeFolder(
                                current.host,
                                current.username,
                                current.password,
                                current.domain,
                                share,
                                current.folder,
                                folderName,
                            )
                        }
                    }
                    busy = false
                    made.onSuccess {
                        folderName = ""
                        draftPad.draft = current.copy(folder = it)
                    }.onFailure { problem = explain(it, listOfNotNull(current.password)) }
                }
            }
        }
        problem?.let { Text(it, color = Coral, fontSize = 16.sp) }
    }
}

private fun pathLabel(share: String, folder: String): String =
    if (folder.isBlank()) share else "$share/${folder.trim('/')}"
