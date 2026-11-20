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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.carry.AppContainer
import app.carry.core.model.Destination
import app.carry.core.sync.BackupPaths
import app.carry.data.explain
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BrowseScreen(container: AppContainer, navigator: Navigator, draftPad: DraftPad) {
    val draft = draftPad.draft
    val scope = rememberCoroutineScope()
    var shares by remember { mutableStateOf<List<String>?>(null) }
    var open by remember { mutableStateOf(setOf<String>()) }
    var kids by remember { mutableStateOf(mapOf<String, List<String>>()) }
    var loading by remember { mutableStateOf(setOf<String>()) }
    var selectedShare by remember { mutableStateOf(draft?.share) }
    var selectedFolder by remember { mutableStateOf(draft?.folder.orEmpty()) }
    var problem by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    if (draft == null) {
        LaunchedEffect(Unit) { navigator.back() }
        return
    }
    val current = draft

    fun node(share: String, folder: String): String = "$share\u0000$folder"

    suspend fun loadKids(share: String, folder: String): List<String>? {
        val id = node(share, folder)
        if (id in kids) return kids.getValue(id)
        loading = loading + id
        val found = withContext(Dispatchers.IO) {
            runCatching {
                container.smb.folders(
                    current.host,
                    current.username,
                    current.password,
                    current.domain,
                    share,
                    folder,
                )
            }
        }
        loading = loading - id
        return found.getOrElse {
            problem = explain(it, listOfNotNull(current.password))
            null
        }?.also { listed ->
            kids = kids + (id to listed)
        }
    }

    fun expand(share: String, folder: String) {
        val id = node(share, folder)
        if (id in open && id in kids) return
        if (id in loading) return
        scope.launch {
            if (loadKids(share, folder) != null) open = open + id
        }
    }

    LaunchedEffect(current.host, current.username, attempt) {
        problem = null
        shares = null
        kids = emptyMap()
        open = emptySet()
        loading = emptySet()
        val found = withContext(Dispatchers.IO) {
            runCatching {
                container.smb.shares(current.host, current.username, current.password, current.domain)
            }
        }
        found.onFailure {
            problem = explain(it, listOfNotNull(current.password))
            shares = emptyList()
            return@LaunchedEffect
        }
        val disks = (found.getOrDefault(emptyList()) + listOfNotNull(current.share)).distinct()
        shares = disks
        val share = selectedShare?.takeIf { it in disks } ?: disks.firstOrNull()
        if (share == null) return@LaunchedEffect
        if (selectedShare == null || selectedShare !in disks) {
            selectedShare = share
            selectedFolder = ""
        }
        val path = if (selectedShare == current.share) selectedFolder else ""
        for (folder in folderChain(path)) {
            if (loadKids(share, folder) != null) open = open + node(share, folder)
        }
        for (disk in disks) {
            if (disk == share) continue
            if (loadKids(disk, "") != null) open = open + node(disk, "")
        }
    }

    fun pick(share: String, folder: String) {
        selectedShare = share
        selectedFolder = folder
        problem = null
        expand(share, folder)
    }

    fun save() {
        val share = selectedShare ?: return
        if (busy) return
        busy = true
        scope.launch {
            val id = current.existingId ?: UUID.randomUUID().toString()
            val destination = Destination(
                id = id,
                host = current.host,
                label = current.label.ifBlank { current.host },
                share = share,
                folder = BackupPaths.folder(selectedFolder),
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

    fun createFolder() {
        val share = selectedShare ?: return
        if (busy) return
        val parent = selectedFolder
        val parentId = node(share, parent)
        val name = freshFolder(kids[parentId].orEmpty())
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
                        parent,
                        name,
                    )
                }
            }
            busy = false
            made.onSuccess { relative ->
                kids = kids - parentId
                if (loadKids(share, parent) != null) open = open + parentId
                pick(share, relative)
            }.onFailure { problem = explain(it, listOfNotNull(current.password)) }
        }
    }

    val rows = treeRows(shares.orEmpty(), open, kids)
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Wordmark(40.sp)
            Text("Pick a folder", color = Mist, fontSize = 28.sp, fontWeight = FontWeight.Black)
            Text(current.label, color = Dim, fontSize = 16.sp)
            selectedShare?.let { share ->
                Text(
                    pathLabel(share, selectedFolder),
                    color = Sky,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                )
            }
            if (shares == null && problem == null) CircularProgressIndicator(color = Sky)
            rows.forEach { row ->
                val id = node(row.share, row.folder)
                val chosen = row.share == selectedShare && row.folder == selectedFolder
                TreeRow(
                    name = row.name,
                    depth = row.depth,
                    open = id in open,
                    waiting = id in loading,
                    chosen = chosen,
                    onClick = { pick(row.share, row.folder) },
                )
            }
            if (shares != null && shares!!.isEmpty()) {
                Text("No folders showed up on this server.", color = Dim, fontSize = 16.sp)
                Pill("Try again") { attempt += 1 }
            }
            problem?.let { Text(it, color = Coral, fontSize = 16.sp) }
        }
        if (selectedShare != null) {
            Column(
                Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ActionButton(
                    if (busy) "Adding" else "New folder",
                    modifier = Modifier.fillMaxWidth(),
                ) { createFolder() }
                ActionButton(
                    if (busy) "Saving" else "Use this folder",
                    primary = true,
                    modifier = Modifier.fillMaxWidth(),
                ) { save() }
            }
        }
    }
}

private data class TreeRowModel(val share: String, val folder: String, val name: String, val depth: Int)

private fun treeRows(shares: List<String>, open: Set<String>, kids: Map<String, List<String>>): List<TreeRowModel> {
    val rows = mutableListOf<TreeRowModel>()
    fun walk(share: String, folder: String, name: String, depth: Int) {
        rows += TreeRowModel(share, folder, name, depth)
        val id = "$share\u0000$folder"
        if (id !in open) return
        for (child in kids[id].orEmpty()) {
            val next = if (folder.isEmpty()) child else "$folder/$child"
            walk(share, next, child, depth + 1)
        }
    }
    shares.forEach { walk(it, "", it, 0) }
    return rows
}

private fun folderChain(folder: String): List<String> {
    if (folder.isBlank()) return listOf("")
    val parts = folder.split('/').filter { it.isNotBlank() }
    val chain = mutableListOf("")
    var path = ""
    for (part in parts) {
        path = if (path.isEmpty()) part else "$path/$part"
        chain += path
    }
    return chain
}

private fun freshFolder(existing: List<String>): String {
    val taken = existing.map { it.lowercase() }.toSet()
    if ("new folder" !in taken) return "New folder"
    var number = 2
    while ("new folder $number" in taken) number += 1
    return "New folder $number"
}

@Composable
private fun TreeRow(
    name: String,
    depth: Int,
    open: Boolean,
    waiting: Boolean,
    chosen: Boolean,
    onClick: () -> Unit,
) {
    val mark = when {
        waiting -> "…"
        open -> "▾"
        else -> "▸"
    }
    FocusCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 8.dp + (18.dp * depth), end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(mark, color = Sky, fontSize = 18.sp, fontFamily = FontFamily.Monospace)
            Text(
                name,
                color = if (chosen) Sky else Mist,
                fontSize = 18.sp,
                fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}

private fun pathLabel(share: String, folder: String): String =
    if (folder.isBlank()) share else "$share/${folder.trim('/')}"
