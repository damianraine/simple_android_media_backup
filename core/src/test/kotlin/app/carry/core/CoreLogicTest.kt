package app.carry.core

import app.carry.core.media.MediaPlaces
import app.carry.core.model.Destination
import app.carry.core.model.Layout
import app.carry.core.model.LocalMedia
import app.carry.core.model.RemoteFile
import app.carry.core.model.CarrySettings
import app.carry.core.net.HostText
import app.carry.core.net.Ipv4Subnets
import app.carry.core.net.Netbios
import app.carry.core.net.NetbiosName
import app.carry.core.store.SecretCodec
import app.carry.core.store.SettingsCodec
import app.carry.core.sync.BackupPaths
import app.carry.core.sync.BackupPlan
import app.carry.core.sync.FolderSearch
import app.carry.core.sync.MediaFilter
import app.carry.core.sync.ShareNames
import app.carry.core.sync.SyncClock
import app.carry.core.text.FileNames
import app.carry.core.text.Quiet
import app.carry.core.text.ReleaseNotes
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreLogicTest {
    private val zone: ZoneId = ZoneId.of("UTC")

    @Test
    fun subnetScanStaysInsideOneSlash24() {
        val hosts = Ipv4Subnets.hosts("10.1.2.30", 16)
        assertTrue(hosts.all { it.startsWith("10.1.2.") })
        assertFalse("10.1.2.30" in hosts)
        assertFalse(hosts.any { it.endsWith(".0") || it.endsWith(".255") })
        assertEquals("10.1.2.1", hosts.first())
        assertEquals("10.1.2.2", hosts[1])
        assertEquals(253, hosts.size)
    }

    @Test
    fun subnetScanSkipsThePhone() {
        val hosts = Ipv4Subnets.hosts("192.168.0.1", 24)
        assertFalse("192.168.0.1" in hosts)
        assertEquals("192.168.0.2", hosts.first())
    }

    @Test
    fun rejectsABlankOrDecoratedHost() {
        assertNull(HostText.acceptable("  "))
        assertNull(HostText.acceptable("smb://nas/share"))
        assertEquals("nas.home", HostText.acceptable(" nas.home "))
    }

    @Test
    fun netbiosQueryAsksForNodeStatus() {
        val query = Netbios.query(0x4341)
        assertEquals(50, query.size)
        assertEquals(0x43, query[0].toInt())
        assertEquals(32, query[12].toInt() and 0xFF)
        assertEquals('C'.code.toByte(), query[13])
        assertEquals('K'.code.toByte(), query[14])
        assertEquals(0x21, query[47].toInt() and 0xFF)
    }

    @Test
    fun netbiosPrefersTheWorkstationName() {
        val names = Netbios.parse(nodeStatus("HARBOR", "WORKGROUP"))
        assertEquals("HARBOR", Netbios.computerName(names))
        assertTrue(names.any { it.group && it.name == "WORKGROUP" })
    }

    @Test
    fun brokenNetbiosPacketsYieldNothing() {
        assertTrue(Netbios.parse(ByteArray(0)).isEmpty())
        assertTrue(Netbios.parse(ByteArray(12)).isEmpty())
        assertNull(Netbios.computerName(emptyList()))
    }

    @Test
    fun startDateDropsOlderMedia() {
        val start = LocalDate.of(2026, 1, 1).toEpochDay()
        val older = ZonedDateTime.of(2025, 12, 31, 23, 0, 0, 0, zone).toInstant().toEpochMilli()
        val newer = ZonedDateTime.of(2026, 1, 1, 0, 30, 0, 0, zone).toInstant().toEpochMilli()
        assertFalse(MediaFilter.kept(older, start, zone))
        assertTrue(MediaFilter.kept(newer, start, zone))
        assertFalse(MediaFilter.kept(0, start, zone))
        assertTrue(MediaFilter.kept(0, null, zone))
    }

    @Test
    fun placesKeepCameraWhatsAppAndAlbums() {
        assertEquals("Camera", MediaPlaces.source("DCIM/Camera/", null))
        assertEquals("WhatsApp Images", MediaPlaces.source("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/", null))
        assertEquals("Downloads", MediaPlaces.source("Download/", null))
        assertEquals("Holiday", MediaPlaces.source("Pictures/Holiday/", "Holiday"))
        assertTrue(MediaPlaces.include("DCIM/Camera/"))
        assertFalse(MediaPlaces.include("DCIM/.thumbnails/"))
        assertFalse(MediaPlaces.include("Android/data/com.example/cache/"))
        assertFalse(MediaPlaces.include("WhatsApp/Media/.Statuses/"))
    }

    @Test
    fun yearMonthLayoutAndSourceLayout() {
        val photo = media("content://1", "IMG_0001.jpg", 10, march, "Camera")
        assertEquals("2026/03/IMG_0001.jpg", BackupPaths.relative(photo, Layout.YEAR_MONTH, zone))
        assertEquals("Camera/IMG_0001.jpg", BackupPaths.relative(photo, Layout.SOURCE_FOLDER, zone))
        assertEquals("undated/file", BackupPaths.relative(photo.copy(name = "???", takenEpochMs = 0), Layout.YEAR_MONTH, zone))
    }

    @Test
    fun planSkipsWhatIsAlreadyThereAndNeverReusesThatName() {
        val present = media("content://a", "a.jpg", 10, march, "Camera")
        val sameNameNewBytes = media("content://b", "a.jpg", 12, march + 1, "Camera")
        val other = media("content://c", "b.jpg", 8, march + 2, "Downloads")
        val remote = listOf(RemoteFile("2026/03/a.jpg", 10))
        val plan = BackupPlan.plan(listOf(present, sameNameNewBytes, other), remote, Layout.YEAR_MONTH, zone)
        assertEquals(1, plan.alreadyThere)
        assertEquals(2, plan.copies.size)
        assertEquals("2026/03/a (2).jpg", plan.copies[0].relativePath)
        assertEquals("2026/03/b.jpg", plan.copies[1].relativePath)
        assertTrue(plan.copies.none { it.relativePath.equals("2026/03/a.jpg", true) })
        assertEquals(plan.copies.map { it.relativePath.lowercase() }.distinct().size, plan.copies.size)
    }

    @Test
    fun twoNewFilesThatWantTheSameNameAreBothKept() {
        val first = media("content://1", "clip.mp4", 50, march, "Camera")
        val second = media("content://2", "clip.mp4", 50, march + 5, "WhatsApp Video")
        val plan = BackupPlan.plan(listOf(second, first), emptyList(), Layout.YEAR_MONTH, zone)
        assertEquals(0, plan.alreadyThere)
        assertEquals(listOf("2026/03/clip.mp4", "2026/03/clip (2).mp4"), plan.copies.map { it.relativePath })
        assertEquals("content://1", plan.copies[0].media.key)
    }

    @Test
    fun folderPathsDropDotDot() {
        assertEquals("media/backup/phone", BackupPaths.folder("/home/../media/backup/phone/"))
        assertEquals("phone", BackupPaths.folder("phone"))
    }

    @Test
    fun suffixKeepsTheExtension() {
        assertEquals("2026/03/a (2).jpg", FileNames.suffixed("2026/03/a.jpg", 2))
        assertEquals("note (3)", FileNames.suffixed("note", 3))
    }

    @Test
    fun shareNamesArePlainFolders() {
        assertTrue(ShareNames.usual.none { it.endsWith("$") || it.contains("/") })
        assertEquals(ShareNames.usual.distinct(), ShareNames.usual)
        assertEquals("media", ShareNames.tidy(" media "))
        assertNull(ShareNames.tidy("IPC$"))
        assertNull(ShareNames.tidy("a/b"))
    }

    @Test
    fun alarmsLandOnEighteenAndMidnight() {
        val afternoon = ZonedDateTime.of(2026, 11, 20, 17, 30, 0, 0, zone)
        assertEquals(ZonedDateTime.of(2026, 11, 20, 18, 0, 0, 0, zone), SyncClock.nextAlarm(afternoon))
        val justFired = ZonedDateTime.of(2026, 11, 20, 18, 0, 0, 0, zone)
        assertEquals(ZonedDateTime.of(2026, 11, 21, 0, 0, 0, 0, zone), SyncClock.nextAlarm(justFired))
        val afterMidnight = ZonedDateTime.of(2026, 11, 21, 0, 0, 30, 0, zone)
        assertEquals(ZonedDateTime.of(2026, 11, 21, 18, 0, 0, 0, zone), SyncClock.nextAlarm(afterMidnight))
        assertEquals("00:00 and 18:00", SyncClock.label())
    }

    @Test
    fun aSavedScheduleReplacesTheDefaultBells() {
        val morning = ZonedDateTime.of(2026, 11, 20, 7, 10, 0, 0, zone)
        val times = listOf(8 * 60 + 30, 21 * 60, 8 * 60 + 30, -15)
        assertEquals("08:30 and 21:00", SyncClock.label(times))
        assertEquals(ZonedDateTime.of(2026, 11, 20, 8, 30, 0, 0, zone), SyncClock.nextAlarm(morning, times))
        assertEquals("2026-11-19T21:00", SyncClock.slotId(morning, times))
        assertNull(SyncClock.nextAlarm(morning, emptyList()))
        assertNull(SyncClock.slotId(morning, emptyList()))
        assertEquals("Sync now only", SyncClock.label(emptyList()))
        val saved = SettingsCodec.decode("""{"syncMinutes":[510,1260]}""")
        assertEquals(listOf(510, 1260), saved.syncMinutes)
        assertEquals(listOf(0, 18 * 60), SettingsCodec.decode("{}").syncMinutes)
    }

    @Test
    fun folderSearchMatchesAPathAndDropsDotSegments() {
        val names = listOf("media", "Photos", "backup")
        assertEquals(listOf("Photos"), FolderSearch.narrow(names, "pho"))
        assertEquals(names, FolderSearch.narrow(names, "  "))
        assertTrue(FolderSearch.matches("media/backup/phone", "Backup"))
        assertFalse(FolderSearch.matches("media/backup/phone", "camera"))
        assertEquals("media/backup/phone", FolderSearch.path(" /media/../media/backup/phone/ "))
        assertNull(FolderSearch.path(".."))
    }

    @Test
    fun slotIsTheLastBellAndAManualCopyIgnoresIt() {
        val afternoon = ZonedDateTime.of(2026, 11, 20, 17, 30, 0, 0, zone)
        val evening = ZonedDateTime.of(2026, 11, 20, 18, 5, 0, 0, zone)
        assertEquals("2026-11-20T00:00", SyncClock.slotId(afternoon))
        assertEquals("2026-11-20T18:00", SyncClock.slotId(evening))
        assertTrue(SyncClock.alreadyDone("2026-11-20T18:00", "2026-11-20T18:00", manual = false))
        assertFalse(SyncClock.alreadyDone("2026-11-20T18:00", "2026-11-20T18:00", manual = true))
        assertFalse(SyncClock.alreadyDone("2026-11-20T00:00", "2026-11-20T18:00", manual = false))
    }

    @Test
    fun settingsRoundTripKeepsTheDestinationAndNotAPassword() {
        val saved = CarrySettings(
            activeDestinationId = "one",
            startFromEpochDay = 20000,
            layout = Layout.SOURCE_FOLDER,
            destinations = listOf(
                Destination(
                    id = "one",
                    host = "192.168.1.20",
                    label = "harbor",
                    share = "media",
                    folder = "backup/phone",
                    username = "ada",
                ),
            ),
        )
        val raw = SettingsCodec.encode(saved)
        assertFalse(raw.contains("secret-word"))
        assertEquals(saved, SettingsCodec.decode(raw))
        assertEquals(CarrySettings(), SettingsCodec.decode("{not json"))
        val book = SecretCodec.decode(SecretCodec.encode(app.carry.core.model.SecretBook(mapOf("one" to "secret-word"))))
        assertEquals("secret-word", book.passwords["one"])
    }

    @Test
    fun releaseNotesUseTheSameShapeAsTheWorkflow() {
        val body = """
            versionName: 1.0.0
            versionCode: 1

            Built from abc.
        """.trimIndent()
        val remote = ReleaseNotes.version(body)
        assertNotNull(remote)
        assertEquals(1, remote.code)
        assertEquals("1.0.0", remote.name)
        val indented = "          versionName: 1.2.0\n          versionCode: 4\n"
        assertEquals(4, ReleaseNotes.version(indented)?.code)
        assertEquals(ReleaseNotes.Choice.SAME, ReleaseNotes.choose(4, 4))
        assertEquals(ReleaseNotes.Choice.NEWER, ReleaseNotes.choose(5, 4))
        assertEquals(ReleaseNotes.Choice.DOWNLOAD, ReleaseNotes.choose(4, 5))
        assertNull(ReleaseNotes.version("no versions here"))
    }

    @Test
    fun passwordsAreStrippedFromShownErrors() {
        val shown = Quiet.redact("refused for password=secret-word", listOf("secret-word"))
        assertFalse(shown.contains("secret-word"))
        assertTrue(shown.contains("***"))
    }

    private val march = ZonedDateTime.of(2026, 3, 2, 12, 0, 0, 0, zone).toInstant().toEpochMilli()

    private fun media(key: String, name: String, size: Long, taken: Long, source: String) =
        LocalMedia(key, name, size, taken, source)

    private fun nodeStatus(computer: String, group: String): ByteArray {
        val question = ByteArray(1 + 32 + 1 + 4)
        question[0] = 32
        val typeAt = 1 + 32 + 1
        question[typeAt + 1] = 0x21
        question[typeAt + 3] = 1
        val body = ByteArray(1 + 18 + 18)
        body[0] = 2
        writeName(body, 1, computer, 0x00, 0x0000)
        writeName(body, 19, group, 0x00, 0x8000)
        val meta = ByteArray(10)
        meta[1] = 0x21
        meta[3] = 1
        meta[8] = (body.size ushr 8).toByte()
        meta[9] = body.size.toByte()
        val header = ByteArray(12)
        header[1] = 1
        header[2] = 0x84.toByte()
        header[5] = 1
        header[7] = 1
        return header + question + byteArrayOf(0) + meta + body
    }

    private fun writeName(buffer: ByteArray, at: Int, name: String, suffix: Int, flags: Int) {
        val padded = name.uppercase().padEnd(15, ' ').take(15)
        padded.encodeToByteArray().copyInto(buffer, at)
        buffer[at + 15] = suffix.toByte()
        buffer[at + 16] = (flags ushr 8).toByte()
        buffer[at + 17] = flags.toByte()
    }
}
