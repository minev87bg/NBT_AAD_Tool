package com.nbtaad.editor.install

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nbtaad.editor.addon.TileKit
import com.nbtaad.editor.addon.TileLogic
import com.nbtaad.editor.log.AppLog
import com.nbtaad.editor.net.NetworkBinder
import com.nbtaad.editor.net.friendlyError
import com.nbtaad.editor.net.runOnHeadUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The page of the Android Auto tile (deathwalker80's nbt-aa-tile): look at what the head unit has, TEST it from the
 * head unit's memory (nothing is installed), INSTALL it at boot, REMOVE it. The kit is a zip somebody shared; it came
 * from LOOK INSIDE, a download or the shelf, and its three files were checked by [TileKit] before this page took it.
 */
class TileController(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val host: () -> String?,
    private val rescan: () -> String?,
    private val problem: () -> String?,
    /** The nbt-aad version the install page found on the head unit, when it did. */
    private val daemonVersion: () -> String?,
) {
    /** The kit that is on the page, where it came from. */
    class Shown(val kit: TileKit, val fileName: String, val origin: String, val pageUrl: String?, val shelfId: String?)

    /** What the last step did. [lines] are what the head unit said, [log] the program's own log of a test. */
    class Result(
        val action: TileScripts.Action,
        val ok: Boolean,
        val headline: String,
        val lines: List<String> = emptyList(),
        val log: String = "",
        val hint: String? = null,
    )

    var shown by mutableStateOf<Shown?>(null); private set
    var mode by mutableStateOf(TileScripts.Mode.FOLLOW)
    var busy by mutableStateOf<TileScripts.Action?>(null); private set
    var step by mutableStateOf(""); private set

    /** What the head unit has now, from the last look (every step ends with one). */
    var report by mutableStateOf<TileScripts.Report?>(null); private set
    var result by mutableStateOf<Result?>(null); private set

    /** When the running test ends (uptime clock), for the countdown. */
    var testEndsAt by mutableStateOf(0L); private set

    /** Passed this session: the tile showed up, so an install is no leap in the dark. */
    var testedOk by mutableStateOf(false); private set

    var askInstall by mutableStateOf(false)
    var askRemove by mutableStateOf(false)
    var showLog by mutableStateOf(false)

    val working: Boolean get() = busy != null

    /** True when the nbt-aad on the head unit is known to be older than the tile needs. */
    val daemonTooOld: Boolean get() = TileLogic.daemonEnough(daemonVersion()) == false
    val daemonUnknown: Boolean get() = TileLogic.daemonEnough(daemonVersion()) == null

    fun show(s: Shown) {
        if (working) return
        AppLog.i("Tile", "Opened ${s.kit.title} from ${s.origin} (known build ${s.kit.known})")
        if (shown?.kit?.let { it.files.keys == s.kit.files.keys && it.program.contentEquals(s.kit.program) } != true) {
            result = null
            testedOk = false
            report = null
        }
        shown = s
    }

    fun look() = go(TileScripts.Action.STATUS)

    fun test() = go(TileScripts.Action.TEST)

    fun askToInstall() {
        if (!working) askInstall = true
    }

    fun install() {
        askInstall = false
        go(TileScripts.Action.INSTALL)
    }

    fun askToRemove() {
        if (!working) askRemove = true
    }

    fun remove() {
        askRemove = false
        go(TileScripts.Action.REMOVE)
    }

    private fun go(action: TileScripts.Action) {
        if (working) return
        val kit = shown?.kit
        if ((action == TileScripts.Action.TEST || action == TileScripts.Action.INSTALL) && kit == null) return
        busy = action
        step = "Starting…"
        if (action != TileScripts.Action.STATUS) result = null
        if (action == TileScripts.Action.TEST) testEndsAt = android.os.SystemClock.elapsedRealtime() + TileScripts.TEST_SECONDS * 1000L
        val sentMode = mode
        AppLog.i("Tile", "${action.word} started (mode ${sentMode.word})")
        scope.launch(Dispatchers.IO) {
            try {
                NetworkBinder.bindToLocalNetwork(ctx)
                val rep = Reporter(say = { t, _ -> step = t }, progress = { _, _, _ -> })
                val r = runOnHeadUnit(host, rescan, onRescan = { step = "Looking for the car…" }, whyNot = problem) {
                    TileHeadUnit.run(it, action, kit, sentMode, rep)
                }
                report = r
                result = interpret(action, r, sentMode)
                if (action == TileScripts.Action.TEST) testedOk = r.success && r.rc == 0
                if (action == TileScripts.Action.INSTALL && r.success) testedOk = true
            } catch (e: Exception) {
                AppLog.e("Tile", "${action.word} failed", e)
                if (action != TileScripts.Action.STATUS) {
                    result = Result(action, false, friendlyError(e), hint = "Nothing is known to be changed. Press LOOK AT THE HEAD UNIT to see what it has.")
                } else {
                    result = Result(action, false, friendlyError(e))
                }
            } finally {
                busy = null
                step = ""
                testEndsAt = 0L
            }
        }
    }

    private fun interpret(action: TileScripts.Action, r: TileScripts.Report, mode: TileScripts.Mode): Result {
        val lines = r.ok + r.info + r.warn
        val hintForError = when {
            r.remounted == false && (action == TileScripts.Action.INSTALL || action == TileScripts.Action.REMOVE) ->
                "The head unit's system partition could not be made writable first, which is probably why."
            else -> null
        }
        if (r.error != null) return Result(action, false, r.error, lines, r.log, hintForError)
        return when (action) {
            TileScripts.Action.STATUS -> Result(action, true, statusHeadline(r), lines)
            TileScripts.Action.TEST -> {
                val good = r.rc == 0
                Result(
                    action, good,
                    if (good) "Test passed" else "Test failed",
                    listOf(TileScripts.exitMeaning(r.rc)) + lines, r.log,
                    if (good) "Did the tile show in the iDrive main menu, and did pressing it bring Android Auto back? Then install it."
                    else if (r.rc == 1 && r.log.contains("cannot be found", ignoreCase = true))
                        "The program could not load the head unit's display library (libsys_dsi_colibry). The lines above say what this head unit has of it: keep the log and send it."
                    else if (r.rc == 2) "Start Android Auto on the phone first and run the test again."
                    else "Keep the log. Do not install it."
                )
            }
            TileScripts.Action.INSTALL -> Result(
                action, true, "Installed, mode: ${mode.label.lowercase()}", lines, "",
                "Restart the head unit (power button 30 s). The tile starts about 25 s after Android Auto is running.",
            )
            TileScripts.Action.REMOVE -> Result(action, true, "Removed", lines, "", "Restart the head unit so that it starts without the tile.")
        }
    }

    /** The last look found no CarLife entry on the head unit: the tile cannot work there. */
    val carlifeMissing: Boolean get() = report?.carlifeMissing == true

    fun statusHeadline(r: TileScripts.Report): String = when {
        r.casella -> "The older casella-aa tile is installed"
        r.hooked && r.programHere -> "Installed" + (r.mode?.let { " ($it)" } ?: "")
        r.hooked -> "A start line is there, but the program is not"
        r.programHere -> "The program is there, but not started at boot"
        else -> "Not installed"
    }

    fun clearResult() {
        result = null
        showLog = false
    }
}
