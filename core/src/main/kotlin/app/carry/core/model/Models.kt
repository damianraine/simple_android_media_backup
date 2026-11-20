package app.carry.core.model

import kotlinx.serialization.Serializable

/** Where a copied file lands inside the folder the user picked. */
@Serializable
enum class Layout {
    YEAR_MONTH,
    SOURCE_FOLDER,
}

/**
 * A drive Carry already knows. The password is not in here.
 * It lives in the keystore book, keyed by [id].
 */
@Serializable
data class Destination(
    val id: String,
    val host: String,
    val label: String,
    val share: String,
    val folder: String = "",
    val username: String? = null,
    val domain: String? = null,
)

@Serializable
data class SyncReport(
    val atEpochMs: Long,
    val copied: Int,
    val alreadyThere: Int,
    val older: Int,
    val failed: Int,
    val note: String,
)

@Serializable
data class CarrySettings(
    val activeDestinationId: String? = null,
    val startFromEpochDay: Long? = null,
    val layout: Layout = Layout.YEAR_MONTH,
    val destinations: List<Destination> = emptyList(),
    val lastSync: SyncReport? = null,
    /** Slot id of the last automatic copy that finished, so the backstop does not do it again. */
    val completedSlot: String? = null,
) {
    fun active(): Destination? =
        destinations.firstOrNull { it.id == activeDestinationId } ?: destinations.firstOrNull()
}

@Serializable
data class SecretBook(
    val passwords: Map<String, String> = emptyMap(),
)

/** One photo or video on the phone, already reduced to what the planner needs. */
data class LocalMedia(
    val key: String,
    val name: String,
    val size: Long,
    val takenEpochMs: Long,
    val source: String,
)

/** A file already sitting in the destination folder. [relativePath] uses forward slashes. */
data class RemoteFile(
    val relativePath: String,
    val size: Long,
)

data class PlannedCopy(
    val media: LocalMedia,
    val relativePath: String,
)

data class SyncPlan(
    val copies: List<PlannedCopy>,
    val alreadyThere: Int,
)

data class FoundDrive(
    val host: String,
    val name: String,
)
