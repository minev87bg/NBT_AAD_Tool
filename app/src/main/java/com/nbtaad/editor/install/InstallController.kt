package com.nbtaad.editor.install

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nbtaad.editor.addon.ShelfItem
import com.nbtaad.editor.addon.ShelfStore
import com.nbtaad.editor.env.AppliedOption
import com.nbtaad.editor.env.AppliedOptions
import com.nbtaad.editor.env.Opt
import com.nbtaad.editor.log.AppLog
import com.nbtaad.editor.net.NetworkBinder
import com.nbtaad.editor.net.Reach
import com.nbtaad.editor.net.Online
import com.nbtaad.editor.net.friendlyError
import com.nbtaad.editor.net.runOnHeadUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

enum class SourceKind { RELEASE, PHONE, ISSUE }

/** A set of files the person can install, with where it came from. */
class PackageView(
    val pkg: BuildPackage,
    val source: SourceKind,
    val title: String,
    val prerelease: Boolean = false,
    val pageUrl: String? = null,
    /** For a file shared in an issue or pull request: which page and who shared it ("#12, shared by bob"). */
    val origin: String? = null,
    /** Set when the files came from the shelf (kept on the phone): the entry is marked as installed after a good install. */
    val shelfId: String? = null,
) {
    /** Files that Sq00ky published on the project's Releases page. Anything else is the person's own responsibility. */
    val fromProject: Boolean get() = source == SourceKind.RELEASE
}

enum class RunKind { INSTALL, REVERT, UNINSTALL }
enum class RunStatus { RUNNING, OK, FAIL }
class RunLine(val text: String, val level: Level)
/** The log of a run, and how it ended: [headline] is the one line for the summary, [notes] what deserves a second look. */
class RunState(
    val kind: RunKind,
    val lines: List<RunLine>,
    val status: RunStatus,
    val headline: String = "",
    val notes: List<String> = emptyList(),
    /** The plugin was replaced: the head unit loads a plugin only when it restarts. */
    val pluginChanged: Boolean = false,
)

/**
 * The state behind the "Install / update NBT Android Auto" screen: the project's releases, the files chosen, what is on the head unit,
 * and the install / go-back runs. The work happens on background threads; the screen reads these properties.
 */
class InstallController(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val host: () -> String?,
    private val rescan: () -> String?,
    /** Why the last look for the car found no head unit, for the failure message. */
    private val problem: () -> String? = { null },
    /** The list of settings (from the README), which says what the values in nbt-aad.env mean. */
    private val catalog: () -> List<Opt> = { emptyList() },
) {
    // the files kept on the phone for later (see ShelfStore)
    private val shelfStore = ShelfStore(ctx)
    var shelf by mutableStateOf<List<ShelfItem>>(emptyList()); private set

    init {
        // known from the start, so the hub can say that a file is waiting
        scope.launch(Dispatchers.IO) { shelf = try { shelfStore.list() } catch (_: Exception) { emptyList() } }
    }

    // the project's releases
    var releases by mutableStateOf<List<ReleaseInfo>?>(null)
    var releasesBusy by mutableStateOf(false)
    var releasesMessage by mutableStateOf("")

    // the files that were chosen
    var view by mutableStateOf<PackageView?>(null)
    var prepBusy by mutableStateOf(false)
    var prepMessage by mutableStateOf("")
    var prepError by mutableStateOf(false)
    var prepFraction by mutableStateOf<Float?>(null)

    // what is on the head unit
    var unit by mutableStateOf<UnitStatus?>(null)
    var unitBusy by mutableStateOf(false)
    var unitMessage by mutableStateOf("")
    var unitError by mutableStateOf(false)

    // the installed nbt-aad read back whole (on request: it takes a while)
    var fileBusy by mutableStateOf(false)
    var fileMessage by mutableStateOf("")
    var fileError by mutableStateOf(false)

    // dialogs and the job
    var askInstall by mutableStateOf(false)
    var askRevert by mutableStateOf(false)

    // the uninstall question: the dialog is open ([askUninstall]); first it looks at the head unit ([uninstallBusy]), then
    // it shows what would go ([uninstallPlan]) or why nothing can be done ([uninstallProblem])
    var askUninstall by mutableStateOf(false)
    var uninstallBusy by mutableStateOf(false)
    var uninstallPlan by mutableStateOf<RemoteScripts.UninstallPlan?>(null)
    var uninstallProblem by mutableStateOf<String?>(null)
    var uninstallKeepEnv by mutableStateOf(false)
    /** What was typed for an install of an unfamiliar layout: it is removed only after "I am sure". */
    var uninstallSure by mutableStateOf("")
    var revertDaemon by mutableStateOf(true)
    var revertPlugin by mutableStateOf(false)
    var diagText by mutableStateOf<String?>(null)
    var diagBusy by mutableStateOf(false)
    var job by mutableStateOf<RunState?>(null)
    var liveLabel by mutableStateOf<String?>(null)
    var liveFraction by mutableStateOf<Float?>(null)

    val working: Boolean get() = prepBusy || unitBusy || fileBusy || job?.status == RunStatus.RUNNING

    /** Is there a head unit to work on? Not when the first screen found none or was skipped. */
    val hasHost: Boolean get() = host() != null

    /** Called when the screen opens: loads what is cheap and read-only. */
    fun open() {
        scope.launch(Dispatchers.IO) { shelf = shelfStore.list() }
        if (releases == null && !releasesBusy) loadReleases()
        if (unit == null && !unitBusy) readUnit()
    }

    // ---------------- the project's releases ----------------

    fun loadReleases() {
        if (releasesBusy) return
        releasesBusy = true
        releasesMessage = "Loading the releases from GitHub…"
        scope.launch(Dispatchers.IO) {
            try {
                val all = ReleasesApi.list(ctx).filter { ReleaseLogic.installable(it) }
                releases = all
                releasesMessage = if (all.isEmpty()) "No release with installable files was found." else ""
            } catch (e: Exception) {
                AppLog.w("Releases", "Loading the releases failed: ${e.javaClass.simpleName}: ${e.message}")
                val offline = e is java.net.UnknownHostException || e is java.net.ConnectException ||
                    e is java.net.SocketTimeoutException || e.message == "No network with internet"
                releasesMessage = if (offline) {
                    "No internet. The releases list and downloads need it (mobile data, or a WiFi with internet). " +
                        "A file already on the phone can be used without."
                } else {
                    "Could not load the releases: ${Online.describe(e)}"
                }
            } finally {
                releasesBusy = false
            }
        }
    }

    /** Downloads the build's files of a release and checks them. */
    fun useRelease(r: ReleaseInfo) {
        if (working) return
        val wanted = ReleaseLogic.wanted(r)
        prepBusy = true
        prepError = false
        prepFraction = 0f
        prepMessage = "Downloading ${r.tag}…"
        view = null
        scope.launch(Dispatchers.IO) {
            try {
                val total = wanted.sumOf { maxOf(it.size, 0L) }.coerceAtLeast(1L)
                var before = 0L
                val files = ArrayList<SourceFile>()
                for (a in wanted) {
                    prepMessage = "Downloading ${a.name} of ${r.tag}…"
                    files += SourceFile(a.name, ReleasesApi.download(ctx, a) { got ->
                        prepFraction = ((before + got).toFloat() / total).coerceIn(0f, 1f)
                    })
                    before += maxOf(a.size, 0L)
                }
                prepMessage = "Checking the files…"
                val pkg = BuildPackage.of(files)
                finishPrep(PackageView(pkg, SourceKind.RELEASE, r.tag, r.prerelease, r.pageUrl.ifBlank { null }), "release ${r.tag}")
            } catch (e: Exception) {
                AppLog.e("Releases", "Getting ${r.tag} failed", e)
                prepError = true
                prepMessage = "Could not get ${r.tag}: ${Online.describe(e)}"
            } finally {
                prepBusy = false
                prepFraction = null
            }
        }
    }

    // ---------------- files from the phone ----------------

    fun usePhoneFiles(uris: List<Uri>) {
        if (uris.isEmpty() || working) return
        prepBusy = true
        prepError = false
        prepFraction = null
        prepMessage = "Reading ${uris.size} file${if (uris.size == 1) "" else "s"}…"
        view = null
        scope.launch(Dispatchers.IO) {
            try {
                val files = ReleasesApi.readPicked(ctx, uris)
                prepMessage = "Checking the files…"
                val pkg = BuildPackage.of(files)
                val title = if (files.size == 1) files[0].name else "${files.size} files"
                finishPrep(PackageView(pkg, SourceKind.PHONE, title), title)
            } catch (e: Exception) {
                AppLog.e("Releases", "Reading the picked file failed", e)
                prepError = true
                prepMessage = "Could not read the file: ${friendlyError(e)}"
            } finally {
                prepBusy = false
            }
        }
    }

    // ---------------- a file that someone shared in an issue or pull request ----------------

    fun useDownloaded(file: SourceFile, origin: String, pageUrl: String?, shelfId: String? = null) {
        if (working) return
        prepBusy = true
        prepError = false
        prepFraction = null
        prepMessage = "Checking the files…"
        view = null
        scope.launch(Dispatchers.IO) {
            try {
                val pkg = BuildPackage.of(listOf(file))
                finishPrep(PackageView(pkg, SourceKind.ISSUE, file.name, pageUrl = pageUrl, origin = origin, shelfId = shelfId), file.name)
            } catch (e: Exception) {
                AppLog.e("Releases", "Looking into ${file.name} failed", e)
                prepError = true
                prepMessage = "Could not read the file: ${friendlyError(e)}"
            } finally {
                prepBusy = false
            }
        }
    }

    private fun finishPrep(v: PackageView, what: String) {
        val p = v.pkg
        AppLog.i("Releases", "Prepared $what: kind ${p.kind}, installable ${p.installable}, version ${p.version}, refused ${p.inspection.refused}")
        view = v
        prepError = p.kind == Kind.NOTHING || !p.installable
        prepMessage = when {
            p.inspection.refused != null -> p.inspection.refused!!
            p.kind == Kind.NOTHING -> "No file of an NBT-AAD build was found in $what."
            !p.installable -> "$what has files of a build, but they cannot be installed (see below)."
            else -> ""
        }
    }

    fun clearChoice() {
        view = null
        prepMessage = ""
        prepError = false
    }

    // ---------------- files kept on the phone for later ----------------

    /**
     * Keeps a file that someone shared on the phone, when it is an installable build, so that it is still there after the
     * app was closed and when no head unit is connected yet. Returns its entry, or null when it was not kept.
     */
    fun keepForLater(file: SourceFile, origin: String, pageUrl: String?): ShelfItem? {
        val item = try { shelfStore.save(file, origin, pageUrl) } catch (e: Exception) {
            AppLog.e("Shelf", "Could not keep ${file.name}", e)
            null
        }
        shelf = shelfStore.list()
        return item
    }

    /** Takes a kept file to the choice above (the same steps as a file just downloaded). */
    fun useShelf(item: ShelfItem) {
        if (working) return
        scope.launch(Dispatchers.IO) {
            val f = shelfStore.load(item)
            shelf = shelfStore.list()
            if (f == null) {
                prepError = true
                prepMessage = "${item.name} is not on the phone any more."
            } else {
                useDownloaded(f, item.origin.ifBlank { "kept on this phone" }, item.pageUrl, shelfId = item.id)
            }
        }
    }

    fun removeFromShelf(item: ShelfItem) {
        scope.launch(Dispatchers.IO) {
            shelfStore.remove(item.id)
            shelf = shelfStore.list()
        }
    }

    // ---------------- the head unit ----------------

    /** What is said while the car is looked for: the first look when no car is known, else a new look. */
    private fun lookingNote(): String =
        if (host() == null) "No car known yet. Looking for the car…" else "No connection. Looking for the car again…"

    /** Looks at the head unit: the folders, the version where the head unit can say it, and nbt-aad.env. Quick. */
    fun readUnit() {
        if (unitBusy) return
        unitBusy = true
        unitError = false
        unit = null
        fileError = false
        unitMessage = if (host() == null) "Looking for the car…" else "Looking at the head unit…"
        scope.launch(Dispatchers.IO) {
            try {
                NetworkBinder.bindToLocalNetwork(ctx)
                unit = runOnHeadUnit(host, rescan, onRescan = { unitMessage = lookingNote() }, whyNot = problem) {
                    unitMessage = "Looking at the head unit…"
                    HeadUnit.status(it)
                }
                unitMessage = ""
                unit?.let { AppLog.i("Unit", "Status: ${it.describe()}") }
            } catch (e: Exception) {
                AppLog.e("Unit", "Reading the head unit failed", e)
                unitError = true
                unitMessage = friendlyError(e)
            } finally {
                unitBusy = false
            }
        }
    }

    /**
     * Reads the installed nbt-aad back whole, for its version and the comparison with the official releases. Only on
     * request (CHECK VERSION): it can take a minute, and the rest of the card does not need it.
     */
    fun checkInstalled() {
        val u = unit ?: return
        if (!u.hasDaemon || working) return
        fileBusy = true
        fileError = false
        fileMessage = READING_FILE
        if (releases == null && !releasesBusy) loadReleases()
        scope.launch(Dispatchers.IO) {
            try {
                NetworkBinder.bindToLocalNetwork(ctx)
                val file = runOnHeadUnit(host, rescan, onRescan = { fileMessage = lookingNote() }, whyNot = problem) {
                    fileMessage = READING_FILE
                    HeadUnit.readInstalledFile(it)
                }
                // put the result on the status that is shown now (null: the file could not be read, which the card says)
                unit?.let { unit = it.withDaemonFile(file) }
                unit?.let { AppLog.i("Unit", "Installed file: ${it.describe()}") }
            } catch (e: Exception) {
                AppLog.e("Unit", "Reading the installed file failed", e)
                fileError = true
                fileMessage = friendlyError(e)
            } finally {
                fileBusy = false
            }
        }
    }

    private companion object {
        const val READING_FILE = "Reading nbt-aad from the head unit. This can take a minute…"
    }

    /** The settings that nbt-aad.env switches on, as read with the status (empty without a status or a file). */
    fun appliedOptions(): List<AppliedOption> = unit?.envText?.let { AppliedOptions.of(it, catalog()) } ?: emptyList()

    /**
     * Is the installed nbt-aad an official release? Judged from what was read back and the project's releases, so it
     * follows both: null when there is no daemon, or the file has not been read yet ([checkInstalled]), otherwise a
     * verdict (which says when the file could not be read or the releases are not loaded).
     */
    fun verdict(): InstalledVerdict? {
        val u = unit ?: return null
        if (!u.hasDaemon) return null
        val f = u.daemonFile
        if (f == null && !u.daemonUnreadable) return null
        return InstalledCheck.judge(u.fileVersion, f?.sha256, f?.size, releases)
    }

    // ---------------- install and go back ----------------

    private fun reporter(recording: Boolean) = Reporter(
        say = { text, level -> addLine(text, level) },
        progress = { label, done, total ->
            liveLabel = label
            liveFraction = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
        },
        swapped = { what -> if (recording) record(what) },
    )

    // What the last install changed, so "go back" offers the right parts: it is written when a part is really replaced.
    private val prefs = ctx.getSharedPreferences("install_state", Context.MODE_PRIVATE)
    private var recStarted = false

    private fun record(what: String) {
        val e = prefs.edit()
        if (!recStarted) {
            recStarted = true
            e.putBoolean("daemon", false).putBoolean("plugin", false)
        }
        e.putBoolean(what, true).apply()
    }

    /** (daemon, plugin) as the last install changed them, or null when none is recorded. */
    private fun lastInstall(): Pair<Boolean, Boolean>? =
        if (prefs.contains("daemon") || prefs.contains("plugin")) prefs.getBoolean("daemon", false) to prefs.getBoolean("plugin", false)
        else null

    /** Opens the "go back" question with the parts that the last install changed ticked. */
    fun openRevert() {
        val u = unit
        val canD = u?.hasPreviousDaemon ?: true
        val canP = u?.hasOldPlugin ?: true
        val rec = lastInstall()
        if (rec != null) {
            revertDaemon = rec.first && canD
            revertPlugin = rec.second && canP
        } else {
            // nothing recorded (an install of an older version of this app): the daemon only, the plugin is chosen by hand
            revertDaemon = canD
            revertPlugin = false
        }
        if (!revertDaemon && !revertPlugin) {
            revertDaemon = canD
            revertPlugin = !canD && canP
        }
        askRevert = true
    }

    fun diagnose() {
        if (diagBusy) return
        diagBusy = true
        diagText = if (host() == null) "Looking for the car…" else "Asking the head unit…"
        scope.launch(Dispatchers.IO) {
            try {
                NetworkBinder.bindToLocalNetwork(ctx)
                diagText = runOnHeadUnit(host, rescan, onRescan = { diagText = lookingNote() }, whyNot = problem) {
                    diagText = "Asking the head unit…"
                    HeadUnit.diagnose(it)
                }
                AppLog.i("Unit", "TOOLS CHECK:\n" + diagText)
            } catch (e: Exception) {
                AppLog.e("Unit", "TOOLS CHECK failed", e)
                diagText = "Could not ask the head unit: ${friendlyError(e)}"
            } finally {
                diagBusy = false
            }
        }
    }

    private fun addLine(text: String, level: Level) {
        val tag = job?.kind?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Run"
        when (level) {
            Level.ERROR -> AppLog.w(tag, "ERROR: $text")
            Level.WARN -> AppLog.w(tag, text)
            else -> AppLog.i(tag, text)
        }
        synchronized(this) { job = job?.let { it.copy2(it.lines + RunLine(text, level)) } }
    }

    private fun RunState.copy2(lines: List<RunLine>) = RunState(kind, lines, status, headline, notes, pluginChanged)

    private fun finish(status: RunStatus, headline: String, notes: List<String> = emptyList(), pluginChanged: Boolean = false) {
        synchronized(this) { job = job?.let { RunState(it.kind, it.lines, status, headline, notes, pluginChanged) } }
        liveLabel = null
        liveFraction = null
    }

    fun install() {
        val v = view ?: return
        if (working) return
        askInstall = false
        recStarted = false
        job = RunState(RunKind.INSTALL, emptyList(), RunStatus.RUNNING)
        AppLog.i(
            "Install",
            "Install started: ${v.title}${v.pkg.version?.let { " ($it)" } ?: ""}, from ${v.source}, daemon " +
                "${Names.DAEMON in v.pkg.payloads}, ${v.pkg.pluginFiles.size} plugin files (full plugin: ${v.pkg.fullPlugin})"
        )
        scope.launch(Dispatchers.IO) {
            try {
                NetworkBinder.bindToLocalNetwork(ctx)
                val rep = reporter(recording = true)
                val out = runOnHeadUnit(
                    host, rescan,
                    onRescan = { addLine(lookingNote(), Level.WARN) },
                    whyNot = problem,
                ) { HeadUnit.install(it, v.pkg, rep) }
                val what = listOfNotNull(
                    if (out.daemon) "nbt-aad" else null,
                    if (out.plugin) "the plugin" else null,
                ).joinToString(" and ")
                val headline = "Installed $what" + (out.daemonVersion?.let { " ($it)" } ?: "") + "."
                addLine(headline, if (out.problems.isEmpty()) Level.OK else Level.WARN)
                finish(RunStatus.OK, headline, out.problems, pluginChanged = out.plugin)
                v.shelfId?.let {
                    // a note on the shelf must never turn a good install into a failed one
                    try {
                        shelfStore.markInstalled(it)
                        shelf = shelfStore.list()
                    } catch (e: Exception) {
                        AppLog.w("Shelf", "Could not mark the entry as installed: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                AppLog.e("Install", "Install failed", e)
                addLine(friendlyError(e), Level.ERROR)
                finish(RunStatus.FAIL, friendlyError(e))
            }
        }
    }

    fun revert() {
        if (working) return
        val wantDaemon = revertDaemon
        val wantPlugin = revertPlugin
        askRevert = false
        AppLog.i("Revert", "Go back started: daemon $wantDaemon, plugin $wantPlugin")
        job = RunState(RunKind.REVERT, emptyList(), RunStatus.RUNNING)
        scope.launch(Dispatchers.IO) {
            try {
                NetworkBinder.bindToLocalNetwork(ctx)
                val rep = reporter(recording = false)
                val out = runOnHeadUnit(
                    host, rescan,
                    onRescan = { addLine(lookingNote(), Level.WARN) },
                    whyNot = problem,
                ) { HeadUnit.revert(it, rep, wantDaemon, wantPlugin) }
                val what = listOfNotNull(
                    if (out.daemon) "nbt-aad" else null,
                    if (out.plugin) "the plugin" else null,
                ).joinToString(" and ")
                val headline = "Back to the previous $what" + (out.daemonVersion?.let { " ($it)" } ?: "") + "."
                addLine(headline, Level.OK)
                finish(RunStatus.OK, headline, pluginChanged = out.plugin)
            } catch (e: Exception) {
                AppLog.e("Revert", "Go back failed", e)
                addLine(friendlyError(e), Level.ERROR)
                finish(RunStatus.FAIL, friendlyError(e))
            }
        }
    }

    /** Opens the uninstall question and looks at what is on the head unit (read only; nothing changes yet). */
    fun openUninstall() {
        if (working) return
        askUninstall = true
        uninstallBusy = true
        uninstallPlan = null
        uninstallProblem = null
        uninstallKeepEnv = false
        uninstallSure = ""
        scope.launch(Dispatchers.IO) {
            try {
                NetworkBinder.bindToLocalNetwork(ctx)
                val plan = runOnHeadUnit(host, rescan, onRescan = { }, whyNot = problem) { HeadUnit.uninstallPlan(it) }
                AppLog.i("Uninstall", "Plan: ${plan.moves.size} moves, ${plan.removes.size} removed, ${plan.envFiles.size} settings files, ${plan.left.size} left alone" + if (plan.foreign != null) " (unfamiliar layout: ${plan.foreign.file})" else "")
                uninstallPlan = plan
            } catch (e: Exception) {
                AppLog.e("Uninstall", "Looking at the head unit failed", e)
                uninstallProblem = friendlyError(e)
            } finally {
                uninstallBusy = false
            }
        }
    }

    fun uninstall() {
        if (working) return
        val keep = uninstallKeepEnv
        val foreign = uninstallPlan?.foreign
        // a layout this app does not know is removed only when the person has typed the words
        if (foreign != null && !HeadUnit.isSure(uninstallSure)) return
        askUninstall = false
        AppLog.i("Uninstall", "Uninstall started, keeping nbt-aad.env: $keep" + if (foreign != null) ", unfamiliar layout confirmed" else "")
        job = RunState(RunKind.UNINSTALL, emptyList(), RunStatus.RUNNING)
        scope.launch(Dispatchers.IO) {
            try {
                NetworkBinder.bindToLocalNetwork(ctx)
                val rep = reporter(recording = false)
                val out = runOnHeadUnit(
                    host, rescan,
                    onRescan = { addLine(lookingNote(), Level.WARN) },
                    whyNot = problem,
                ) { HeadUnit.uninstall(it, rep, keep, foreign) }
                val headline = "Android Auto was removed from the head unit."
                addLine(headline, if (out.problems.isEmpty()) Level.OK else Level.WARN)
                finish(RunStatus.OK, headline, out.problems, pluginChanged = out.plan.moves.isNotEmpty())
            } catch (e: Exception) {
                AppLog.e("Uninstall", "Uninstall failed", e)
                addLine(friendlyError(e), Level.ERROR)
                finish(RunStatus.FAIL, friendlyError(e))
            }
        }
    }

    /** Closes the result and looks at the head unit again (not while it restarts: then the card waits for CHECK AGAIN). */
    fun closeRun(readAgain: Boolean = true) {
        if (job?.status == RunStatus.RUNNING) return
        job = null
        unit = null
        if (readAgain) readUnit()
    }
}
