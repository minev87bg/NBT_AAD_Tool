package com.nbtaad.editor.contribute

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nbtaad.editor.experimental.Topic
import com.nbtaad.editor.install.HeadUnit
import com.nbtaad.editor.install.ReleaseLogic
import com.nbtaad.editor.log.AppLog
import com.nbtaad.editor.log.deviceText
import com.nbtaad.editor.log.versionText
import com.nbtaad.editor.net.NetworkBinder
import com.nbtaad.editor.net.SshCancelled
import com.nbtaad.editor.net.friendlyError
import com.nbtaad.editor.net.runOnHeadUnit
import java.io.IOException
import java.time.ZonedDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** The zip that was made, and what was left out of it. */
class PackedLogs(
    val name: String,
    val bytes: ByteArray,
    val passwords: Int,
    val vins: Int,
    val positions: Int,
    /** Files that could not be read: "name: why". */
    val skipped: List<String>,
    val files: List<String>,
)

/**
 * The state behind the CONTRIBUTE page: find the daemon's logs on the head unit, tick the ones to send, pick the issue or
 * pull request they are for, read them (slowly), hide the WiFi password and the VIN, pack them into one zip, save it, and
 * open the page so the zip can be attached there. Nothing leaves the phone by itself: attaching is done by the person,
 * on GitHub, which is also why no login is needed.
 */
class ContributeController(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val host: () -> String?,
    private val rescan: () -> String?,
    private val problem: () -> String?,
    /** The version of the installed nbt-aad as far as the Install page knows it, for the note inside the zip. */
    private val headUnitVersion: () -> String?,
) {
    // the logs on the head unit
    var logs by mutableStateOf<List<HeadLog>?>(null); private set
    var looking by mutableStateOf(false); private set
    var lookMessage by mutableStateOf(""); private set
    var lookError by mutableStateOf(false); private set
    var picked by mutableStateOf<Set<String>>(emptySet()); private set
    var withEnv by mutableStateOf(true); private set
    var withAppLog by mutableStateOf(true); private set

    // where it goes: a page of the project, or (null) a new issue
    var target by mutableStateOf<Topic?>(null)
    var choosing by mutableStateOf(false)

    // reading and packing
    var packing by mutableStateOf(false); private set
    var packLabel by mutableStateOf(""); private set
    var packDone by mutableStateOf(0L); private set
    var packTotal by mutableStateOf(0L); private set
    var packMessage by mutableStateOf(""); private set
    var packError by mutableStateOf(false); private set
    var packed by mutableStateOf<PackedLogs?>(null); private set
    var saveNote by mutableStateOf(""); private set
    var saved by mutableStateOf(false); private set

    @Volatile private var cancel = false

    // clearing the logs on the head unit
    var askClear by mutableStateOf(false)
    var clearing by mutableStateOf(false); private set
    var clearMessage by mutableStateOf(""); private set
    var clearError by mutableStateOf(false); private set

    // choosing the sessions of a log on the USB stick
    /** The log whose sessions the window shows; null while it is closed. */
    var sessionLog by mutableStateOf<HeadLog?>(null); private set
    var sessionList by mutableStateOf<List<LogSession>?>(null); private set
    var sessionsLooking by mutableStateOf(false); private set
    var sessionMessage by mutableStateOf(""); private set
    var sessionError by mutableStateOf(false); private set
    /** Per log (by path) the numbers of the sessions to pack; a log that is not in here is packed whole. */
    var chosenSessions by mutableStateOf<Map<String, Set<Int>>>(emptyMap()); private set
    private val knownSessions = HashMap<String, List<LogSession>>()
    @Volatile private var cancelSessions = false

    val working: Boolean get() = looking || packing || clearing || sessionsLooking

    /** The page to attach the zip on: the chosen issue or pull request (scrolled to the comment box), else a new issue. */
    fun pageUrl(): String =
        target?.url?.takeIf { it.startsWith("https://") }?.let { PageLinks.commentBox(it) } ?: NEW_ISSUE_URL

    /** "Issue #187: title" */
    fun targetText(): String = target?.let { (if (it.isPr) "Pull request #" else "Issue #") + "${it.number}: ${it.title}" } ?: "A new issue"

    /** The page opened from an issue's own page ("send logs here") keeps that page as the target. */
    fun open(topic: Topic?) {
        if (topic != null) target = topic
        if (logs == null && !looking) look()
    }

    private fun lookingNote(): String =
        if (host() == null) "No vehicle known yet. Looking for the vehicle…" else "No connection. Looking for the vehicle again…"

    /** Looks at the head unit for the log files (one quick command). */
    fun look() {
        if (working) return
        looking = true
        lookError = false
        lookMessage = if (host() == null) "Looking for the vehicle…" else "Looking at the head unit…"
        scope.launch(Dispatchers.IO) {
            try {
                NetworkBinder.bindToLocalNetwork(ctx)
                val found = runOnHeadUnit(host, rescan, onRescan = { lookMessage = lookingNote() }, whyNot = problem) {
                    lookMessage = "Looking at the head unit…"
                    HeadUnit.findLogs(it)
                }
                logs = found
                // a new look may show other files: what was chosen of the old ones is not valid any more
                knownSessions.clear()
                chosenSessions = emptyMap()
                // the daemon's own log and the app's are ticked; older and extra ones are for the person to add
                picked = found.filter { it.name == "nbt-aad.log" || it.name == "nbt-aad-app.log" }.map { it.path }.toSet()
                lookMessage = ""
                forgetPacked()
            } catch (e: Exception) {
                AppLog.e("Contribute", "Looking for the logs failed", e)
                lookError = true
                lookMessage = friendlyError(e)
            } finally {
                looking = false
            }
        }
    }

    /** Deletes every log file that is listed (in memory and on the stick), then looks at the head unit again. */
    fun clearLogs() {
        askClear = false
        if (working) return
        val files = logs.orEmpty()
        if (files.isEmpty()) return
        clearing = true
        clearError = false
        clearMessage = "Deleting the logs…"
        scope.launch(Dispatchers.IO) {
            try {
                NetworkBinder.bindToLocalNetwork(ctx)
                val problems = runOnHeadUnit(host, rescan, onRescan = { clearMessage = lookingNote() }, whyNot = problem) {
                    HeadUnit.clearLogs(it, files)
                }
                clearError = problems.isNotEmpty()
                clearMessage = if (problems.isEmpty()) {
                    "The logs were deleted."
                } else {
                    "Not everything was deleted: " + problems.joinToString("; ")
                }
                AppLog.i("Contribute", "Clear logs: ${files.size} files, problems: ${problems.ifEmpty { listOf("none") }}")
            } catch (e: Exception) {
                AppLog.e("Contribute", "Clearing the logs failed", e)
                clearError = true
                clearMessage = friendlyError(e)
            } finally {
                clearing = false
            }
            look()
        }
    }

    /** Opens the window to choose sessions of [l]; the head unit is asked for the list once (it is kept until the next look). */
    fun openSessions(l: HeadLog) {
        if (working) return
        sessionLog = l
        sessionError = false
        sessionMessage = ""
        sessionList = knownSessions[l.path]
        if (sessionList == null) listSessions(l)
    }

    /** Asks the head unit for the session headers of [l] (slow: it reads through the whole file on the stick). */
    fun listSessions(l: HeadLog) {
        if (sessionsLooking) return
        cancelSessions = false
        sessionsLooking = true
        sessionError = false
        sessionMessage = if (host() == null) "Looking for the vehicle…" else "Looking through ${l.name} on the USB stick…"
        scope.launch(Dispatchers.IO) {
            try {
                NetworkBinder.bindToLocalNetwork(ctx)
                val found = runOnHeadUnit(host, rescan, onRescan = { sessionMessage = lookingNote() }, whyNot = problem) {
                    sessionMessage = "Looking through ${l.name} on the USB stick…"
                    HeadUnit.findSessions(it, l) { cancelSessions }
                }
                knownSessions[l.path] = found
                if (sessionLog?.path == l.path) sessionList = found
                sessionMessage = ""
            } catch (e: SshCancelled) {
                AppLog.i("Contribute", "Looking for sessions stopped by the person")
                sessionMessage = "Stopped."
            } catch (e: Exception) {
                AppLog.e("Contribute", "Looking for the sessions failed", e)
                sessionError = true
                sessionMessage = friendlyError(e)
            } finally {
                sessionsLooking = false
            }
        }
    }

    /** Closes the window; a list that is still being looked for is stopped. */
    fun closeSessions() {
        cancelSessions = true
        sessionLog = null
        sessionError = false
        sessionMessage = ""
    }

    /** Packs only these sessions of [l] (it is ticked too). */
    fun useSessions(l: HeadLog, numbers: Set<Int>) {
        if (numbers.isEmpty()) return
        chosenSessions = chosenSessions + (l.path to numbers)
        picked = picked + l.path
        forgetPacked()
        closeSessions()
    }

    /** Back to the whole log. */
    fun useWholeLog(l: HeadLog) {
        chosenSessions = chosenSessions - l.path
        picked = picked + l.path
        forgetPacked()
        closeSessions()
    }

    /** The sessions that [l] is packed with (oldest first), or empty for the whole log. */
    fun sessionsOf(l: HeadLog): List<LogSession> {
        val nums = chosenSessions[l.path] ?: return emptyList()
        return knownSessions[l.path].orEmpty().filter { it.number in nums }
    }

    fun setEnv(on: Boolean) {
        withEnv = on
        forgetPacked()
    }

    fun setAppLog(on: Boolean) {
        withAppLog = on
        forgetPacked()
    }

    fun toggle(path: String) {
        picked = if (path in picked) picked - path else picked + path
        forgetPacked()
    }

    /** What was packed is for the choices of that moment: a changed choice asks for a new zip. */
    private fun forgetPacked() {
        if (!packing) {
            packed = null
            saved = false
            saveNote = ""
            packMessage = ""
            packError = false
        }
    }

    fun chooseTarget(t: Topic?) {
        target = t
        choosing = false
        forgetPacked()
    }

    fun nothingChosen(): Boolean = picked.isEmpty() && !withEnv && !withAppLog

    /** Reads the chosen files from the head unit (slow), hides the secrets, and packs the zip. */
    fun pack() {
        if (working) return
        if (nothingChosen()) {
            packError = true
            packMessage = "Tick at least one file."
            return
        }
        val chosen = logs.orEmpty().filter { it.path in picked }
        val forEnv = withEnv
        val forApp = withAppLog
        cancel = false
        packing = true
        packError = false
        packed = null
        saved = false
        saveNote = ""
        packLabel = ""
        packDone = 0
        packTotal = 0
        packMessage = "Reading from the head unit…"
        scope.launch(Dispatchers.IO) {
            try {
                NetworkBinder.bindToLocalNetwork(ctx)
                val reads = if (chosen.isEmpty() && !forEnv) {
                    emptyList()
                } else {
                    runOnHeadUnit(host, rescan, onRescan = { packMessage = lookingNote() }, whyNot = problem) {
                        HeadUnit.readLogs(
                            it, chosen, forEnv,
                            onProgress = { name, done, total -> packLabel = name; packDone = done; packTotal = total },
                            cancelled = { cancel },
                            sessions = chosen.mapNotNull { l -> sessionsOf(l).takeIf { s -> s.isNotEmpty() }?.let { s -> l.path to s } }.toMap(),
                        )
                    }
                }
                packMessage = "Hiding the WiFi password, the VIN and the GPS positions…"
                var passwords = 0
                var vins = 0
                var positions = 0
                val entries = ArrayList<PackEntry>()
                val skipped = ArrayList<String>()
                fun add(name: String, bytes: ByteArray, note: String = "") {
                    val m = LogMask.mask(String(bytes, Charsets.UTF_8))
                    passwords += m.passwords
                    vins += m.vins
                    positions += m.positions
                    // a file with nothing to hide goes in as it is, byte for byte
                    entries += PackEntry(name, if (m.any) m.text.toByteArray(Charsets.UTF_8) else bytes, note)
                }
                for (r in reads) {
                    val b = r.bytes
                    if (b == null) skipped += "${r.name}: ${r.problem}" else add(r.name, b)
                }
                if (forApp) add("nbt-aad-tool-app-log.txt", AppLog.text().toByteArray(Charsets.UTF_8), "the phone app's own log")
                if (entries.isEmpty()) throw IOException("None of the files could be read.")
                val now = ZonedDateTime.now()
                val info = PackInfo(
                    AppLog.versionText(), AppLog.deviceText(), now, headUnitVersion(),
                    target?.let { targetText() }, passwords, vins, positions,
                )
                val zip = LogPack.zip(entries, info)
                val made = PackedLogs(LogPack.zipName(now), zip, passwords, vins, positions, skipped, entries.map { it.name })
                AppLog.i("Contribute", "Packed ${entries.size} files into ${zip.size} bytes; hid $passwords passwords, $vins VINs and $positions positions; skipped: ${skipped.ifEmpty { "none" }}")
                packed = made
                // the zip goes straight into Downloads, where the browser's file picker finds it: no extra step for the person
                if (saveToDownloads(made)) {
                    saved = true
                    saveNote = "Saved in your Downloads folder (${LogFinder.sizeText(zip.size.toLong())})."
                    AppLog.i("Contribute", "Zip saved in Downloads: ${made.name}")
                }
                packMessage = ""
            } catch (e: SshCancelled) {
                packMessage = "Stopped."
                packError = false
                AppLog.i("Contribute", "Stopped by the person")
            } catch (e: Exception) {
                AppLog.e("Contribute", "Packing the logs failed", e)
                packError = true
                packMessage = friendlyError(e)
            } finally {
                packing = false
            }
        }
    }

    fun cancelPack() {
        cancel = true
    }

    /** Puts the zip in the phone's Downloads folder (Android 10 and later, no permission needed); false when that does not work. */
    private fun saveToDownloads(z: PackedLogs): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return try {
            val resolver = ctx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, z.name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
            try {
                resolver.openOutputStream(uri)?.use { it.write(z.bytes) } ?: throw IOException("The phone would not open the new file.")
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            true
        } catch (e: Exception) {
            AppLog.w("Contribute", "Saving in Downloads failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /** Writes the zip to the file the person chose. */
    fun saveTo(uri: Uri) {
        val z = packed ?: return
        scope.launch(Dispatchers.IO) {
            try {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(z.bytes) }
                    ?: throw IOException("The phone would not open that file for writing.")
                saved = true
                saveNote = "Saved: ${LogFinder.sizeText(z.bytes.size.toLong())}."
                AppLog.i("Contribute", "Zip saved (${z.bytes.size} bytes)")
            } catch (e: Exception) {
                // a copy that did not work does not undo the zip that is already in Downloads
                if (!saved) saveNote = "Could not save: ${e.message ?: e.javaClass.simpleName}"
                AppLog.e("Contribute", "Saving the zip failed", e)
            }
        }
    }

    companion object {
        val NEW_ISSUE_URL = "https://github.com/${ReleaseLogic.OWNER}/${ReleaseLogic.REPO}/issues/new"
    }
}
