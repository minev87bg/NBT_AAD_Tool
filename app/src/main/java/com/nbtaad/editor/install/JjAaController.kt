package com.nbtaad.editor.install

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nbtaad.editor.log.AppLog
import com.nbtaad.editor.net.CarDetector
import com.nbtaad.editor.net.JjAa
import com.nbtaad.editor.net.Online
import com.nbtaad.editor.net.SshConfig
import com.nbtaad.editor.net.friendlyError
import com.nbtaad.editor.net.parseHostPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

enum class JjSourceKind { RELEASE, PHONE }

/** A payload the person can install, with where it came from - the same idea as [PackageView], for JayJay3071's
 * flat, single-zip package instead of an NBT-AAD build. */
class JjPackageView(
    val payload: JjAa.Payload,
    val source: JjSourceKind,
    val title: String,
    val prerelease: Boolean = false,
    val pageUrl: String? = null,
    /** Set when this came from the shelf (kept on the phone): marked as installed after a good install. */
    val shelfId: String? = null,
) {
    /** A file JayJay3071 published on the project's own Releases page. A phone file is the person's own responsibility. */
    val fromProject: Boolean get() = source == JjSourceKind.RELEASE
}

/**
 * The state behind the "NBT Android Auto (original NBT)" screen: JayJay3071's package for the OLDER, non-Evo BMW
 * NBT - a different head unit generation from the rest of this app, kept deliberately separate from
 * [HeadUnit]/[InstallController] (see net/JjAa.kt for why). Shaped to match [InstallController] - the releases /
 * choose / plan / run pattern from the main Install page - but adapted to this package's simpler, flat,
 * no-backup protocol: there is no daemon/plugin split, no settings file, and nothing to revert to after an
 * install, since JayJay3071's own installer keeps no previous version aside.
 *
 * This app never bundles or keeps a copy of the official payload itself beyond what the person already fetched or
 * picked; the shelf below only keeps bytes the person's own phone already downloaded or chose, the same as the
 * project's own installer would leave on their computer.
 */
class JjAaController(
    private val ctx: Context,
    private val scope: CoroutineScope,
    /** The address to install to: the head unit this app already found, when there is one. */
    private val host: () -> String?,
) {
    // files kept on the phone (see JjShelfStore)
    private val shelfStore = JjShelfStore(ctx)
    var shelf by mutableStateOf<List<JjShelfItem>>(emptyList()); private set

    // the project's releases
    var releases by mutableStateOf<List<ReleaseInfo>?>(null); private set
    var releasesBusy by mutableStateOf(false); private set
    var releasesMessage by mutableStateOf(""); private set

    // the payload chosen
    var view by mutableStateOf<JjPackageView?>(null); private set
    var prepBusy by mutableStateOf(false); private set
    var prepMessage by mutableStateOf(""); private set
    var prepError by mutableStateOf(false); private set
    var prepFraction by mutableStateOf<Float?>(null); private set

    /** Ticked before installing a file that did not come straight from the project's own Releases page. */
    var trusted by mutableStateOf(false)

    // what is on the head unit now (a plain read: the VERSION file and whether the two folders are there)
    var unit by mutableStateOf<JjAa.JjUnitStatus?>(null); private set
    var unitBusy by mutableStateOf(false); private set
    var unitMessage by mutableStateOf(""); private set
    var unitError by mutableStateOf(false); private set

    /** The original NBT's root password. Defaults to the same one this app already uses for NBT-AAD/EVO - the
     * same QNX lineage is likely to share it - but it is editable, since this is a different head unit and that
     * is not confirmed. Never stored. */
    var password by mutableStateOf(SshConfig.PASSWORD)
    var hostOverride by mutableStateOf("")
    val effectiveHost: String get() = hostOverride.trim().ifEmpty { host() ?: JjAa.DEFAULT_HOST }

    /** [effectiveHost] split into an address and a port. A plain address gets the usual port 22; typing
     * "address:port" (e.g. "10.76.88.65:2222") carries a custom one - for reaching the head unit through a
     * VPN/ENET-relay setup (ZeroTier, FlashXCode, Turbocode and the like) that remaps the port it's reachable on. */
    val effectiveHostPort: Pair<String, Int> get() = parseHostPort(effectiveHost)

    // dialogs and the job
    var askInstall by mutableStateOf(false)
    var askUninstall by mutableStateOf(false)
    var job by mutableStateOf<RunState?>(null); private set
    var liveLabel by mutableStateOf<String?>(null); private set
    var liveFraction by mutableStateOf<Float?>(null); private set
    var showCredits by mutableStateOf(false)

    private var visited = false

    val working: Boolean get() = prepBusy || unitBusy || job?.status == RunStatus.RUNNING
    val busyWithFile: Boolean get() = prepBusy || job?.status == RunStatus.RUNNING
    val noUnit: Boolean get() = unit == null && !unitBusy

    /** Called when the screen opens: loads what is cheap and read-only, once. */
    fun visit() {
        if (visited) return
        visited = true
        loadShelf()
        loadReleases()
        readUnit()
    }

    fun loadShelf() {
        scope.launch(Dispatchers.IO) { shelf = try { shelfStore.list() } catch (_: Exception) { emptyList() } }
    }

    // ---------------- the project's releases ----------------

    fun loadReleases() {
        if (releasesBusy) return
        releasesBusy = true
        releasesMessage = "Loading the releases from GitHub…"
        scope.launch(Dispatchers.IO) {
            try {
                val all = JjAa.releases(ctx).filter { JjAa.payloadAsset(it) != null }
                releases = all
                releasesMessage = if (all.isEmpty()) "No release with a file this app can read was found." else ""
                AppLog.i("JjAa", "${all.size} release(s) from ${JjAa.PROJECT_URL}")
            } catch (e: Exception) {
                AppLog.w("JjAa", "Loading the releases failed: ${e.javaClass.simpleName}: ${e.message}")
                releasesMessage = "Could not load the releases: ${Online.describe(e)}"
            } finally {
                releasesBusy = false
            }
        }
    }

    /** Downloads one release's payload zip, reads it and keeps it on the shelf. */
    fun useRelease(r: ReleaseInfo) {
        if (working) return
        val asset = JjAa.payloadAsset(r)
        if (asset == null) {
            prepError = true
            prepMessage = "${r.tag} has no cross-platform (\"...-Python-....zip\") file this app can read."
            return
        }
        prepBusy = true
        prepError = false
        prepFraction = 0f
        prepMessage = "Downloading ${asset.name}…"
        view = null
        trusted = false
        scope.launch(Dispatchers.IO) {
            try {
                val bytes = ReleasesApi.download(ctx, asset) { got ->
                    prepFraction = (got.toFloat() / maxOf(asset.size, 1L)).coerceIn(0f, 1f)
                }
                prepMessage = "Checking the file…"
                val payload = JjAa.parsePayloadZip(bytes)
                view = JjPackageView(payload, JjSourceKind.RELEASE, r.tag, r.prerelease, r.pageUrl.ifBlank { null })
                prepMessage = ""
                AppLog.i("JjAa", "Prepared ${r.tag}: version ${payload.manifest.version}, ${payload.manifest.entries.size} files")
                try {
                    shelfStore.save(bytes, asset.name, payload.manifest.version, fromRelease = true, pageUrl = r.pageUrl.ifBlank { null }, sourceUrl = asset.url)
                    shelf = shelfStore.list()
                } catch (e: Exception) {
                    AppLog.w("JjAa", "Could not keep ${asset.name} on the shelf: ${e.message}")
                }
            } catch (e: JjAa.PayloadError) {
                prepError = true
                prepMessage = e.message ?: "Not a package this app can read."
            } catch (e: Exception) {
                AppLog.w("JjAa", "Getting ${r.tag} failed: ${e.javaClass.simpleName}: ${e.message}")
                prepError = true
                prepMessage = "Could not get ${r.tag}: ${Online.describe(e)}"
            } finally {
                prepBusy = false
                prepFraction = null
            }
        }
    }

    // ---------------- a file from the phone ----------------

    fun usePhoneFile(uri: Uri) {
        if (busyWithFile) return
        prepBusy = true
        prepError = false
        prepFraction = null
        prepMessage = "Reading the file…"
        view = null
        trusted = false
        scope.launch(Dispatchers.IO) {
            try {
                val f = ReleasesApi.readPicked(ctx, listOf(uri)).first()
                prepMessage = "Checking the file…"
                val payload = JjAa.parsePayloadZip(f.bytes)
                view = JjPackageView(payload, JjSourceKind.PHONE, f.name)
                prepMessage = ""
                try {
                    shelfStore.save(f.bytes, f.name, payload.manifest.version, fromRelease = false, pageUrl = null, sourceUrl = null)
                    shelf = shelfStore.list()
                } catch (e: Exception) {
                    AppLog.w("JjAa", "Could not keep ${f.name} on the shelf: ${e.message}")
                }
            } catch (e: JjAa.PayloadError) {
                prepError = true
                prepMessage = e.message ?: "Not a package this app can read."
            } catch (e: Exception) {
                AppLog.e("JjAa", "Reading the picked file failed", e)
                prepError = true
                prepMessage = "Could not read the file: ${friendlyError(e)}"
            } finally {
                prepBusy = false
            }
        }
    }

    // ---------------- files kept on the phone for later ----------------

    /** Takes a kept file to the choice above (the same steps as a file just downloaded or picked). */
    fun useShelf(item: JjShelfItem) {
        if (busyWithFile) return
        prepBusy = true
        prepError = false
        prepMessage = "Reading ${item.name}…"
        view = null
        trusted = false
        scope.launch(Dispatchers.IO) {
            try {
                val bytes = shelfStore.load(item)
                if (bytes == null) {
                    prepError = true
                    prepMessage = "${item.name} is not on the phone any more."
                    shelf = shelfStore.list()
                } else {
                    val payload = JjAa.parsePayloadZip(bytes)
                    view = JjPackageView(
                        payload,
                        if (item.fromRelease) JjSourceKind.RELEASE else JjSourceKind.PHONE,
                        item.name,
                        pageUrl = item.pageUrl,
                        shelfId = item.id,
                    )
                    prepMessage = ""
                }
            } catch (e: Exception) {
                AppLog.e("JjAa", "Reading the kept file failed", e)
                prepError = true
                prepMessage = "Could not read ${item.name}: ${friendlyError(e)}"
            } finally {
                prepBusy = false
            }
        }
    }

    fun removeFromShelf(item: JjShelfItem) {
        scope.launch(Dispatchers.IO) {
            shelfStore.remove(item.id)
            shelf = shelfStore.list()
        }
    }

    fun clearChoice() {
        view = null
        prepMessage = ""
        prepError = false
        trusted = false
    }

    // ---------------- the head unit ----------------

    /** Reads what is on the head unit now: the VERSION file and whether the two folders this package uses are
     * there. A plain read - safe at any time, no preflight, nothing written. */
    fun readUnit() {
        if (unitBusy) return
        unitBusy = true
        unitError = false
        unit = null
        unitMessage = "Looking at the head unit…"
        val (h, port) = effectiveHostPort
        val pw = password
        scope.launch(Dispatchers.IO) {
            try {
                unit = JjAa.status(h, pw, port)
                unitMessage = ""
                AppLog.i("JjAa", "Status: installed=${unit?.looksInstalled}, version=${unit?.version}")
            } catch (e: Exception) {
                AppLog.w("JjAa", "Reading the head unit failed: ${e.javaClass.simpleName}: ${e.message}")
                unitError = true
                unitMessage = friendlyError(e)
            } finally {
                unitBusy = false
            }
        }
    }

    private fun findGateway(): String? = try {
        CarDetector.discoverZgw(CarDetector.localNetworks())?.ip
    } catch (e: Exception) {
        AppLog.w("JjAa", "Gateway search failed: ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    // ---------------- install and uninstall ----------------

    private fun reporter() = Reporter(
        say = { text, level -> addLine(text, level) },
        progress = { label, done, total ->
            liveLabel = label
            liveFraction = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
        },
    )

    private fun addLine(text: String, level: Level) {
        when (level) {
            Level.ERROR -> AppLog.w("JjAa", "ERROR: $text")
            Level.WARN -> AppLog.w("JjAa", text)
            else -> AppLog.i("JjAa", text)
        }
        synchronized(this) { job = job?.let { RunState(it.kind, it.lines + RunLine(text, level), it.status, it.headline, it.notes) } }
    }

    private fun finish(status: RunStatus, headline: String, notes: List<String> = emptyList()) {
        synchronized(this) { job = job?.let { RunState(it.kind, it.lines, status, headline, notes) } }
        liveLabel = null
        liveFraction = null
    }

    fun install() {
        val v = view ?: return
        if (working) return
        if (!v.fromProject && !trusted) return
        askInstall = false
        job = RunState(RunKind.INSTALL, emptyList(), RunStatus.RUNNING)
        val (h, port) = effectiveHostPort
        val pw = password
        AppLog.i("JjAa", "Install started: ${v.title} (${v.payload.manifest.version}), from ${v.source}")
        scope.launch(Dispatchers.IO) {
            try {
                val out = JjAa.install(h, pw, v.payload, reporter(), port = port, findGateway = ::findGateway)
                val headline = "Installed version ${out.version} (${out.filesWritten} files)."
                finish(RunStatus.OK, headline, listOfNotNull(out.gatewayNote))
                v.shelfId?.let {
                    try {
                        shelfStore.markInstalled(it)
                        shelf = shelfStore.list()
                    } catch (e: Exception) {
                        AppLog.w("JjAa", "Could not mark the entry as installed: ${e.message}")
                    }
                }
                unit = null
            } catch (e: Exception) {
                AppLog.e("JjAa", "Install failed", e)
                addLine(friendlyError(e), Level.ERROR)
                finish(RunStatus.FAIL, friendlyError(e))
            }
        }
    }

    /** Opens the uninstall question: the two folders this package writes are fixed and already known, so (unlike
     * NBT-AAD's uninstall) there is nothing to read from the head unit first - the dialog can show the plan at once. */
    fun openUninstall() {
        if (working) return
        askUninstall = true
    }

    fun uninstall() {
        if (working) return
        askUninstall = false
        job = RunState(RunKind.UNINSTALL, emptyList(), RunStatus.RUNNING)
        val (h, port) = effectiveHostPort
        val pw = password
        AppLog.i("JjAa", "Uninstall started")
        scope.launch(Dispatchers.IO) {
            try {
                val notRemoved = JjAa.uninstall(h, pw, reporter(), port)
                val headline = if (notRemoved.isEmpty()) "Android Auto was removed from the head unit." else "Removed, but could not remove: ${notRemoved.joinToString(", ")}"
                finish(if (notRemoved.isEmpty()) RunStatus.OK else RunStatus.FAIL, headline)
                unit = null
            } catch (e: Exception) {
                AppLog.e("JjAa", "Uninstall failed", e)
                addLine(friendlyError(e), Level.ERROR)
                finish(RunStatus.FAIL, friendlyError(e))
            }
        }
    }

    /** Closes the result and looks at the head unit again (the new CHECK). */
    fun closeRun() {
        if (job?.status == RunStatus.RUNNING) return
        job = null
        readUnit()
    }
}
