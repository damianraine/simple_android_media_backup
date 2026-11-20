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
import app.carry.core.sync.FolderSearch
import app.carry.core.sync.ShareNames
import app.carry.data.explain
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BrowseScreen(container: AppContainer, navigator: Navigator, draftPad: DraftPad) {
    val draft = draftPad.draft
    val scope = rememberCoroutineScope()
    var shares by remember(draft?.host) { mutableStateOf<List<String>?>(null) }
    var folders by remember(draft?.share, draft?.folder) { mutableStateOf<List<String>?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var foundPaths by remember { mutableStateOf<List<String>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var diskName by remember { mutableStateOf("") }
    var making by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf("") }
    var holdAuto by remember { mutableStateOf(false) }
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

    LaunchedEffect(shares, current.share) {
        val list = shares ?: return@LaunchedEffect
        if (!holdAuto && current.share == null && list.size == 1) {
            draftPad.draft = current.copy(share = list.first(), folder = "")
        }
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

    LaunchedEffect(current.share, query) {
        val share = current.share
        val needle = query.trim()
        if (share == null || needle.isEmpty()) {
            searching = false
            foundPaths = null
            return@LaunchedEffect
        }
        searching = true
        foundPaths = null
        delay(250)
        val found = withContext(Dispatchers.IO) {
            runCatching {
                container.smb.findFolders(
                    current.host,
                    current.username,
                    current.password,
                    current.domain,
                    share,
                    needle,
                )
            }
        }
        searching = false
        found.onSuccess { foundPaths = it }.onFailure { problem = explain(it, listOfNotNull(current.password)) }
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

    fun openFolder(name: String) {
        val next = listOf(current.folder, name).filter { it.isNotBlank() }.joinToString("/")
        query = ""
        draftPad.draft = current.copy(folder = next)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Wordmark(40.sp)
            Text("Pick a folder", color = Mist, fontSize = 28.sp, fontWeight = FontWeight.Black)
            Text(current.label, color = Dim, fontSize = 16.sp)
            if (current.share != null) {
                Text(
                    pathLabel(current.share, current.folder),
                    color = Sky,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                )
            }
            if (current.share == null) {
                if (shares == null && problem == null) CircularProgressIndicator(color = Sky)
                if (shares.orEmpty().isNotEmpty()) {
                    SearchBox("Search", query) { query = it }
                    FolderSearch.narrow(shares.orEmpty(), query).forEach { name ->
                        FolderRow(name) { draftPad.draft = current.copy(share = name, folder = "") }
                    }
                }
                if (shares != null && shares!!.isEmpty()) {
                    Text("Type the disk name.", color = Dim, fontSize = 16.sp)
                    SearchBox("Disk name", diskName) { diskName = it }
                    Pill("Open") {
                        val tidy = ShareNames.tidy(diskName)
                        if (tidy == null) problem = "That name cannot be used."
                        else draftPad.draft = current.copy(share = tidy, folder = "")
                    }
                }
            } else {
                SearchBox("Search folders", query) { query = it }
                if (current.folder.isNotEmpty()) {
                    FolderRow("Up") {
                        query = ""
                        draftPad.draft = current.copy(folder = current.folder.substringBeforeLast('/', ""))
                    }
                }
                if (searching) CircularProgressIndicator(color = Sky)
                val needle = query.trim()
                val here = if (needle.isEmpty()) folders.orEmpty() else FolderSearch.narrow(folders.orEmpty(), needle)
                here.forEach { name -> FolderRow(name) { openFolder(name) } }
                if (needle.isNotEmpty()) {
                    val hits = foundPaths.orEmpty().filter { path ->
                        path.substringAfterLast('/') !in here && path !in here
                    }
                    if (!searching && foundPaths != null && here.isEmpty() && hits.isEmpty()) {
                        Text("No folder matches that.", color = Dim, fontSize = 16.sp)
                    }
                    hits.forEach { path ->
                        FolderRow(path) {
                            query = ""
                            draftPad.draft = current.copy(folder = path)
                        }
                    }
                    val typed = FolderSearch.path(needle)
                    if (typed != null) {
                        Pill("Open $typed") {
                            query = ""
                            draftPad.draft = current.copy(folder = typed)
                        }
                    }
                }
                if (folders == null && problem == null && needle.isEmpty()) CircularProgressIndicator(color = Sky)
                if (needle.isEmpty() && folders != null && folders!!.isEmpty()) {
                    Text("This folder is empty. Use it, or make one.", color = Dim, fontSize = 16.sp)
                }
                if ((shares?.size ?: 0) > 1) {
                    Pill("Other disk") {
                        holdAuto = true
                        query = ""
                        problem = null
                        draftPad.draft = current.copy(share = null, folder = "")
                    }
                }
                if (!making) {
                    Pill("New folder") { making = true }
                } else {
                    SearchBox("New folder name", folderName) { folderName = it }
                    Pill("Create") {
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
                                making = false
                                draftPad.draft = current.copy(folder = it)
                            }.onFailure { problem = explain(it, listOfNotNull(current.password)) }
                        }
                    }
                }
            }
            problem?.let { Text(it, color = Coral, fontSize = 16.sp) }
        }
        if (current.share != null) {
            ActionButton(
                if (busy) "Saving" else "Use this folder",
                primary = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            ) { save() }
        }
    }
}

@Composable
private fun FolderRow(label: String, onClick: () -> Unit) {
    FocusCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = Mist, fontSize = 18.sp, modifier = Modifier.padding(16.dp))
    }
}

private fun pathLabel(share: String, folder: String): String =
    if (folder.isBlank()) share else "$share/${folder.trim('/')}"
