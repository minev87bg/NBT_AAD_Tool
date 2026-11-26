package com.nbtaad.editor

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nbtaad.editor.contribute.ContributeController
import com.nbtaad.editor.contribute.WizardController
import com.nbtaad.editor.contribute.WizardStep
import com.nbtaad.editor.data.CatalogStore
import com.nbtaad.editor.data.CustomOptionStore
import com.nbtaad.editor.data.Storage
import com.nbtaad.editor.data.ThemeMode
import com.nbtaad.editor.data.ThemePrefs
import com.nbtaad.editor.env.ConfigParser
import com.nbtaad.editor.env.CustomOption
import com.nbtaad.editor.env.EnvCheck
import com.nbtaad.editor.env.EnvDoc
import com.nbtaad.editor.env.Opt
import com.nbtaad.editor.addon.AddOnCatalog
import com.nbtaad.editor.addon.AddOns
import com.nbtaad.editor.addon.CatalogEntry
import com.nbtaad.editor.install.InstallController
import com.nbtaad.editor.install.MenuLookController
import com.nbtaad.editor.install.SourceFile
import com.nbtaad.editor.install.TileController
import com.nbtaad.editor.experimental.ExperimentalController
import com.nbtaad.editor.experimental.Topic
import com.nbtaad.editor.log.AppLog
import com.nbtaad.editor.log.exportName
import com.nbtaad.editor.log.saveTo
import com.nbtaad.editor.log.start
import com.nbtaad.editor.net.CarDetector
import com.nbtaad.editor.net.HeadUnitLight
import com.nbtaad.editor.net.HeadUnitWatch
import com.nbtaad.editor.net.CarNet
import com.nbtaad.editor.net.CarNetLogic
import com.nbtaad.editor.net.CarWifi
import com.nbtaad.editor.net.DongleWifi
import com.nbtaad.editor.net.WifiNames
import com.nbtaad.editor.net.CatalogSync
import com.nbtaad.editor.net.DetectResult
import com.nbtaad.editor.net.EnvRemote
import com.nbtaad.editor.net.Hsfz
import com.nbtaad.editor.net.Internet
import com.nbtaad.editor.net.NetworkBinder
import com.nbtaad.editor.net.PortProbe
import com.nbtaad.editor.net.Reach
import com.nbtaad.editor.net.Online
import com.nbtaad.editor.net.WriteStep
import com.nbtaad.editor.net.friendlyError
import com.nbtaad.editor.net.runOnHeadUnit
import com.nbtaad.editor.update.Check
import com.nbtaad.editor.update.InstallEvents
import com.nbtaad.editor.update.UpdateOffer
import com.nbtaad.editor.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

enum class Screen { HUB, OPTIONS, INSTALL, EXPERIMENTAL, CONTRIBUTE, WIZARD, TILE, MENULOOK, ADDONS, PREPARE, SIGNING_TOOLS }
enum class UpdatePhase { IDLE, CHECKING, CURRENT, AVAILABLE, DOWNLOADING, FAILED }
enum class SaveStatus { RUNNING, OK, FAIL }
enum class LineKind { INFO, OK, ERROR }

/** How often [MainViewModel.checkUpdate] repeats itself in the background while the app is open. */
private const val UPDATE_CHECK_INTERVAL_MS = 10 * 60 * 1000L

data class DetectState(
    val carFound: Boolean = false,
    val zgw: String? = null,
    val hu: String? = null,
    val vin: String? = null,
    val ssh: Boolean = false,
    /** The head unit was reached over the car's own Android Auto WiFi: there is no car gateway on that network. */
    val viaCarWifi: Boolean = false,
    /** Android Auto answers on the car's own WiFi but the head unit's packet filter blocks SSH: the PF has to be removed. */
    val pfBlocked: Boolean = false,
)

data class DialogLine(val text: String, val kind: LineKind = LineKind.INFO)

/** One option whose value differs from what was read from the car. null = not set. */
data class Change(val key: String, val old: String?, val new: String?)

/** What the SAVE preview shows: the changes and the problems the file would have on the head unit. */
data class ReviewState(
    val changes: List<Change>,
    val errors: List<String>,
    val warnings: List<String>,
    val settingCount: Int,
)

data class SaveDialogState(
    val lines: List<DialogLine>,
    val status: SaveStatus,
    val connectionOk: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx = app.applicationContext

    init {
        AppLog.start(ctx)
    }

    private val storage = Storage(ctx)
    private val themePrefs = ThemePrefs(ctx)

    private var screenState by mutableStateOf(Screen.HUB)
    var screen: Screen
        get() = screenState
        set(v) {
            if (v != screenState) AppLog.i("Screen", "$screenState -> $v")
            screenState = v
        }

    /** The "Install / update NBT Android Auto" screen: releases, files, what is on the head unit, install and go back. */
    val inst = InstallController(
        ctx, viewModelScope, host = { detect.hu }, rescan = { rescanForHost() }, problem = { scanProblem },
        catalog = { catalog },
    ).apply {
        // every install, go-back and uninstall changes a file the head unit only reads at startup, so send the
        // reset itself instead of leaving the person to notice the recommendation and find a button for it
        onFinishedOk = { resetHeadUnit(fromWindow = true) }
    }

    /** The page of the Android Auto tile add-on: test, install and remove it. */
    val tile = TileController(
        ctx, viewModelScope, host = { detect.hu }, rescan = { rescanForHost() }, problem = { scanProblem },
        daemonVersion = { inst.unit?.installedVersion },
    )

    /**
     * The live look at the head unit's SSH for the pages that need it (the tile, the menu look): it says what is wrong when
     * the packet filter blocks the phone, and keeps the page's buttons off until SSH answers.
     */
    val watch = HeadUnitWatch(ctx) { detect.hu }

    /** The live look turned green: read the head unit if that is not done yet, so that the page can act on it. */
    fun onSshGreen() {
        if (inst.unit == null && !inst.unitBusy) inst.readUnit()
    }

    /** The page the tile page was opened from, for its back button. */
    private var tileFrom = Screen.EXPERIMENTAL

    /** The page of the menu look add-on (the label and pictures of the CarLife entry). */
    val menuLook = MenuLookController(
        ctx, viewModelScope, host = { detect.hu }, rescan = { rescanForHost() }, problem = { scanProblem },
    )

    /**
     * Takes [file] to the page of the add-on it is (the tile, or the menu look) when the app can read its files; false
     * for anything else, which goes to the installer.
     */
    private fun openTileWith(file: SourceFile, origin: String, pageUrl: String?, shelfId: String?): Boolean {
        val kit = AddOns.tileKitOf(file.name, file.bytes)
        if (kit != null) {
            tile.show(TileController.Shown(kit, file.name, origin, pageUrl, shelfId))
            if (screen != Screen.TILE && screen != Screen.MENULOOK) tileFrom = screen
            inst.open()
            screen = Screen.TILE
            return true
        }
        val look = AddOns.menuLookKitOf(file.name, file.bytes) ?: return false
        menuLook.show(MenuLookController.Shown(look, file.name, origin, pageUrl, shelfId))
        if (screen != Screen.TILE && screen != Screen.MENULOOK) tileFrom = screen
        inst.open()
        screen = Screen.MENULOOK
        return true
    }

    /** The Add-ons page: the approved add-ons, followed on GitHub. */
    val addOns = com.nbtaad.editor.install.AddOnsController(ctx, viewModelScope, inst) { file, origin, pageUrl, shelfId ->
        openTileWith(file, origin, pageUrl, shelfId)
    }

    /** The add-on whose page is open on the Add-ons screen (its catalog id); null: the list. */
    var addOnOpen by mutableStateOf<String?>(null)

    /**
     * Keeps [file] on the phone (so it is there without a car and after the app was closed) and opens the page of the
     * add-on it is; false when it is neither the tile nor the menu look.
     */
    private fun openAddOnFile(file: SourceFile, origin: String): Boolean {
        if (AddOns.tileKitOf(file.name, file.bytes) == null && AddOns.menuLookKitOf(file.name, file.bytes) == null) return false
        val kept = inst.keepForLater(file, origin, null, null)
        return openTileWith(file, origin, null, kept?.id)
    }

    fun chooseAddOns() {
        // the list needs the files kept on the phone and the releases of the add-ons, not a look for the car: that is done
        // when an add-on's own page opens (and a tap on an add-on must not wait for it)
        inst.loadShelf()
        addOnOpen = null
        addOns.visit()
        screen = Screen.ADDONS
    }

    /**
     * A tap on an add-on's row in the list: when there is nothing new to fetch or decide (the newest copy is already
     * on the phone), this goes straight to the add-on's own page instead of by way of its GitHub page, since there is
     * nothing on that page a person needs to see again. The GitHub page is still one tap away (the row's own ⓘ) for the
     * times it is wanted: a new release to read about first, or the first time this add-on is got at all.
     */
    fun openAddOnRow(entry: CatalogEntry) {
        if (addOns.plan(entry).doing == AddOnCatalog.Do.OPEN_SAVED) addOns.act(entry) else addOnOpen = entry.id
    }

    fun closeTile() {
        screen = if (tileFrom == Screen.TILE || tileFrom == Screen.MENULOOK) Screen.HUB else tileFrom
    }

    fun closeMenuLook() = closeTile()

    init {
        inst.onAddOnFile = { file -> openAddOnFile(file, "a file from this phone") }
        inst.onTile = { file, item -> openTileWith(file, item.origin.ifBlank { "kept on this phone" }, item.pageUrl, item.id) }
    }

    /** The Experimental page: issues and pull requests of the project, and the files people share in them. */
    val exp = ExperimentalController(
        ctx, viewModelScope,
        isKnownSetting = { k -> catalog.any { it.key == k } || k in customKeys },
        onFile = { file, topic, link ->
            val kind = if (topic.isPr) "pull request" else "issue"
            AppLog.i("Experimental", "Taking ${link.name} from $kind #${topic.number} to the installer")
            val origin = "$kind #${topic.number}, shared by ${link.by.ifBlank { "someone" }}"
            // kept on the phone first: it is still there when no car is connected yet, or the app is closed
            val kept = inst.keepForLater(file, origin, topic.url, link.url)
            // the tile add-on has a page of its own; everything else goes to the installer
            if (!openTileWith(file, origin, topic.url, kept?.id)) {
                inst.open()
                inst.useDownloaded(file, origin, topic.url, shelfId = kept?.id)
                screen = Screen.INSTALL
            }
        },
        onKeep = { file, topic, link ->
            val kind = if (topic.isPr) "pull request" else "issue"
            inst.keepForLater(file, "$kind #${topic.number}, shared by ${link.by.ifBlank { "someone" }}", topic.url, link.url) != null
        },
        isKept = { file -> inst.isKept(file) },
        keptCopy = { link, topic -> inst.keptCopy(link.url, link.name, topic.url) },
    )

    /** The manual way of POST WITH LOGS: the daemon's logs from the head unit, packed into a zip for an issue. */
    val contribute = ContributeController(
        ctx, viewModelScope, host = { detect.hu }, rescan = { rescanForHost() }, problem = { scanProblem },
        headUnitVersion = { inst.unit?.installedVersion },
    )

    /** The log wizard: from "I want to test this" to the logs on the page, with the app's own pages as steps on the way. */
    val wizard = WizardController(
        ctx, viewModelScope, host = { detect.hu }, zgw = { detect.zgw },
        onHost = { hu, open -> detect = detect.copy(hu = hu, ssh = open) },
        contribute = contribute,
    )

    /** True while the settings page or the install page was opened from the wizard: they then offer the way back. */
    var fromWizard by mutableStateOf(false); private set

    var themeMode by mutableStateOf(themePrefs.read())

    fun cycleTheme() {
        themeMode = when (themeMode) {
            ThemeMode.SYSTEM -> ThemeMode.LIGHT
            ThemeMode.LIGHT -> ThemeMode.DARK
            ThemeMode.DARK -> ThemeMode.SYSTEM
        }
        themePrefs.write(themeMode)
    }

    // ---------------- detecting the car ----------------
    var busy by mutableStateOf(false)
    var resetting by mutableStateOf(false)
    private var feedbackState by mutableStateOf("")

    /** The line a page shows while it looks for the car or resets it; whatever it says is also written to the log. */
    var feedback: String
        get() = feedbackState
        set(v) {
            feedbackState = v
            AppLog.i("Detect", v)
        }
    var feedbackError by mutableStateOf(false)
    var detect by mutableStateOf(DetectState())

    /**
     * The progress of a reset that was started from a result window (install, go back, save): null while none is shown.
     * The windows clear it when they close, and the reset stops writing to it then.
     */
    var resetNote by mutableStateOf<String?>(null)

    var askDraft by mutableStateOf(false)
    var manualDialog by mutableStateOf(false)
    private var pollJob: Job? = null

    // ---------------- the car's network and the dongle (this session only) ----------------

    /** The WiFi the car answered on at the last good look for it; null before one, and while it looks again. */
    var carNet by mutableStateOf<CarNet?>(null); private set

    /** The dongle's WiFi name and password as the person gave them. In memory only: gone when the app closes. */
    var dongle by mutableStateOf<DongleWifi?>(null); private set
    private var dongleDeclined = false

    /** The question "is this your WiFi dongle?" (the name and the password). */
    var askDongle by mutableStateOf(false)

    /** The window of CONNECT AGAIN: Bluetooth off first, then the dongle's WiFi. */
    var connectDialog by mutableStateOf(false)

    /** Brings the phone back to the dongle's WiFi with the name and password the person gave. */
    val carWifi = CarWifi(ctx)

    override fun onCleared() {
        carWifi.release()
        super.onCleared()
    }

    // ---------------- screen 2: options ----------------
    var optBusy by mutableStateOf(false)
    private var optFeedbackState by mutableStateOf("")
    var optFeedback: String
        get() = optFeedbackState
        set(v) {
            optFeedbackState = v
            AppLog.i("Options", v)
        }
    var optError by mutableStateOf(false)
    var rows by mutableStateOf<List<Opt>>(emptyList())
    val values = mutableStateMapOf<String, String?>()
    var saveDialog by mutableStateOf<SaveDialogState?>(null)
    private var baseDoc: EnvDoc? = null

    /** The values as they were on the car when last read (or last saved); changes are measured against this. */
    var orig by mutableStateOf<Map<String, String?>>(emptyMap())
    var confirmRead by mutableStateOf(false)
    var review by mutableStateOf<ReviewState?>(null)

    /** Narrowing the long list down. */
    var search by mutableStateOf("")
    var onlyActive by mutableStateOf(false)

    /** True once the file on the head unit was read (or a draft loaded): only then is SAVE safe. */
    val fileKnown: Boolean get() = baseDoc != null

    // ---------------- the list of settings (from the project's README) ----------------
    private val catalogStore = CatalogStore(ctx)
    private var catalog: List<Opt> = emptyList()
    private var catalogSource = CatalogStore.Source.BUNDLED
    private var catalogSavedAt: Long? = null
    var syncBusy by mutableStateOf(false)
    var syncMessage by mutableStateOf("")
    var syncOk by mutableStateOf(false)

    // options the person added by hand (settings of newer builds that the README does not list yet)
    private val customStore = CustomOptionStore(ctx)
    private var customKeys: List<String> = customStore.load()

    /** The "+ Add an option" dialog: the name it starts with, or null while it is closed. */
    var addDialog by mutableStateOf<String?>(null)

    init {
        val loaded = catalogStore.load()
        catalog = loaded.options
        catalogSource = loaded.source
        catalogSavedAt = loaded.savedAt
        // synchronized again every time the settings page opens (openOptions), not here: that is where it is shown
    }

    // ---------------- updates of this app (from the project's GitHub releases) ----------------
    var updatePhase by mutableStateOf(UpdatePhase.IDLE)
    var updateMessage by mutableStateOf("")
    var updateOffer by mutableStateOf<UpdateOffer?>(null)
    var updateProgress by mutableStateOf(0f)

    /** The tag of the last offer the person closed the popup on, so the same version does not keep popping back up. */
    private var updateDialogDismissedFor by mutableStateOf<String?>(null)

    /**
     * A new version is ready and the person has not already closed the popup for this exact one: show it. Covers the
     * download and a failed attempt too, so the popup stays up through the whole thing instead of vanishing the moment
     * something goes wrong; a failure of the background check itself (no [updateOffer]) does not pop anything up.
     */
    val showUpdateDialog: Boolean
        get() = updateOffer != null &&
            updatePhase in setOf(UpdatePhase.AVAILABLE, UpdatePhase.DOWNLOADING, UpdatePhase.FAILED) &&
            updateOffer?.tag != updateDialogDismissedFor

    fun dismissUpdateDialog() {
        updateDialogDismissedFor = updateOffer?.tag
    }

    // after the properties above: initializers and init blocks run in the order they are written
    init {
        checkUpdate()
        viewModelScope.launch {
            while (isActive) {
                delay(UPDATE_CHECK_INTERVAL_MS)
                checkUpdate()
            }
        }
    }

    /** "Updated to version X." for 15 minutes after Android replaced this app with a newer version, else empty. */
    val updatedNote: String = try {
        val me = Updater.installed(ctx)
        if (me.justUpdated) "Updated to version ${me.name}." else ""
    } catch (e: Exception) {
        ""
    }

    /** The version this app was built as (1.0.N from a GitHub build, "dev" from a local one). */
    val appVersion: String by lazy { try { Updater.installed(ctx).name } catch (e: Exception) { "?" } }

    /** What the system installer answered after the file was handed over (cancelled, refused, done). */
    val installMessage: String? get() = InstallEvents.message

    /**
     * Looks for a newer release on GitHub, including the repeat check every [UPDATE_CHECK_INTERVAL_MS]. Needs
     * internet; with none it stays quiet and does not even start (no "Checking…" flash every ten minutes while the
     * phone is plainly offline) - the settings sync line already says so elsewhere. While the popup for an offer
     * already found is up, a repeat check is skipped too: there is nothing new to learn and no reason to disturb it.
     */
    fun checkUpdate() {
        if (updatePhase == UpdatePhase.CHECKING || updatePhase == UpdatePhase.DOWNLOADING) return
        if (showUpdateDialog) return
        if (!Internet.hasConnectivity(ctx)) return
        updatePhase = UpdatePhase.CHECKING
        updateMessage = "Checking for a new version…"
        viewModelScope.launch(Dispatchers.IO) {
            try {
                when (val r = Updater.check(ctx)) {
                    is Check.Newer -> {
                        AppLog.i("Update", "New version ${r.offer.tag} is available")
                        updateOffer = r.offer
                        updatePhase = UpdatePhase.AVAILABLE
                        updateMessage = "New version available: ${r.offer.tag} (installed: ${Updater.installed(ctx).name})"
                    }
                    is Check.Current -> {
                        AppLog.i("Update", "The app is up to date (${r.installed.name})")
                        updateOffer = null
                        updatePhase = UpdatePhase.CURRENT
                        updateMessage = "The app is up to date (version ${r.installed.name})."
                    }
                }
            } catch (e: Exception) {
                AppLog.w("Update", "Update check failed: ${e.javaClass.simpleName}: ${e.message}")
                updatePhase = UpdatePhase.FAILED
                // no internet: the settings sync line already says so; anything else is worded for the internet, not the car
                if (Online.offline(e)) updatePhase = UpdatePhase.IDLE
                updateMessage = if (Online.offline(e)) "" else "Update check failed: ${Online.describe(e)}"
            }
        }
    }

    /** Downloads the new version, checks it is signed like this one, and hands it to Android's installer. */
    fun startUpdate() {
        val offer = updateOffer ?: return
        if (updatePhase == UpdatePhase.DOWNLOADING) return
        if (!Updater.mayInstall(ctx)) {
            updateMessage = "Android first needs your permission: in the settings page that just opened switch on " +
                "\"Allow from this source\" for this app, go back and press UPDATE again."
            return
        }
        updatePhase = UpdatePhase.DOWNLOADING
        updateProgress = 0f
        updateMessage = "Downloading ${offer.tag}…"
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apk = Updater.download(ctx, offer) { done, total ->
                    updateProgress = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
                }
                if (Updater.sameSigner(ctx, apk) == false) {
                    updatePhase = UpdatePhase.FAILED
                    updateMessage = "This version is signed with a different key than the installed app, so Android " +
                        "would refuse to replace it. Uninstall the app once and install the new version; later " +
                        "updates then work in place."
                    return@launch
                }
                updateMessage = "Confirm the installation on the next screen. The app closes while it updates: " +
                    "open it again afterwards."
                Updater.install(ctx, apk)
                updatePhase = UpdatePhase.AVAILABLE
            } catch (e: Exception) {
                AppLog.e("Update", "The update failed", e)
                updatePhase = UpdatePhase.FAILED
                updateMessage = "The update failed: ${Online.describe(e)}"
            }
        }
    }

    private fun catalogDescription(): String = when (catalogSource) {
        CatalogStore.Source.BUNDLED -> "built into the app"
        CatalogStore.Source.DOWNLOADED -> {
            val t = catalogSavedAt
            if (t == null) "saved earlier"
            else "saved on " + java.text.DateFormat.getDateInstance().format(java.util.Date(t))
        }
    }

    /** Looks for new settings on GitHub. Needs internet once; afterwards the list is kept on the phone. */
    fun syncCatalog() {
        if (syncBusy) return
        syncBusy = true
        syncOk = false
        syncMessage = "Checking for new settings…"
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val readme = CatalogSync.fetchReadme(ctx)
                val section = ConfigParser.extractSection(readme)
                val parsed = section?.let { ConfigParser.parse(it) }.orEmpty()
                if (section == null || parsed.size < CatalogStore.MIN_OPTIONS) {
                    syncMessage = "The settings list on GitHub could not be read (its layout may have changed). " +
                        "Using the list ${catalogDescription()}: ${catalog.size} settings."
                } else {
                    val before = catalog.map { it.key }.toSet()
                    catalogStore.saveDownloaded(section)
                    val fresh = parsed.count { it.key !in before }
                    catalog = parsed
                    catalogSource = CatalogStore.Source.DOWNLOADED
                    catalogSavedAt = System.currentTimeMillis()
                    syncOk = true
                    syncMessage = "Available settings are synchronized (${parsed.size} settings" +
                        (if (fresh > 0) ", $fresh new" else "") + ")."
                    AppLog.i("Catalog", syncMessage)
                }
            } catch (e: Exception) {
                AppLog.w("Catalog", "Settings sync failed: ${e.javaClass.simpleName}: ${e.message}")
                syncMessage = "No internet, so no check for new settings. Using the list ${catalogDescription()} " +
                    "(${catalog.size} settings). Connect to the internet once to update it."
            } finally {
                syncBusy = false
            }
        }
    }

    /** Folder name for this car's files: the VIN when the ZGW told us, otherwise a fallback. */
    private val carId: String
        get() = detect.vin ?: ("unknown-" + (detect.hu ?: detect.zgw ?: "car"))

    // =====================================================================
    // Detect
    // =====================================================================

    /** The hints (routers, DHCP and DNS servers the phone was given) both detections pass along to [CarDetector]. */
    private fun netHints(): Collection<String> = try {
        PortProbe.netFacts(ctx).let { it.gateways + it.dhcp + it.dns }
    } catch (_: Exception) {
        emptyList()
    }

    /** The full detection (gateway and head unit): only Prepare headunit needs this. See [detectCarSsh] for everything else. */
    private fun detectCar(progress: (String) -> Unit): DetectResult = CarDetector.detect(netHints(), progress)

    /** SSH only, no gateway: what every page besides Prepare headunit looks for (see [rescanForHost]). */
    private fun detectCarSsh(progress: (String) -> Unit): DetectResult = CarDetector.detectSsh(netHints(), progress)

    /**
     * The detection every page does for itself when it needs the head unit (see [rescanForHost], SSH only), or
     * [checkCodings], [setManual] and [resetHeadUnit] redo it to refresh what is known (the full detection, gateway
     * included). Written so either kind of result reads correctly: a page that only looked for SSH never has a
     * gateway to show, and that is not the same as nothing being found.
     */
    private fun applyResult(r: DetectResult) {
        AppLog.i("Detect", "Applied: gateway ${r.zgwIp}, VIN ${r.vin}, head unit ${r.huIp}, SSH ${r.sshOpen}, problem ${r.problem}")
        detect = DetectState(r.zgwIp != null || r.viaCarWifi, r.zgwIp, r.huIp, r.vin, r.sshOpen, r.viaCarWifi, r.pfBlocked)
        feedbackError = false
        feedback = when {
            r.pfBlocked -> {
                feedbackError = true
                r.problem ?: HeadUnitLight.PF_PROBLEM
            }
            r.viaCarWifi && r.sshOpen -> "Connected through the vehicle's own WiFi (no gateway needed). SSH is open."
            r.viaCarWifi -> {
                feedbackError = true
                r.problem ?: "The head unit answers over the vehicle's WiFi, but SSH did not work. Try again."
            }
            r.huIp != null && r.sshOpen -> "Head unit found. SSH is open."
            r.huIp != null -> "Head unit found, but SSH (port 22) is closed. Use the reset button next to the red dot."
            r.zgwIp != null -> {
                feedbackError = true
                r.problem ?: "Gateway found, but no head unit with SSH was found. Use the reset button or set the IPs manually."
            }
            else -> {
                feedbackError = true
                r.problem ?: "No head unit found."
            }
        }
        if (r.huIp != null && r.sshOpen) markCarNetwork(r.huIp)
    }

    /**
     * The car answered on the phone's WiFi right now: that is the network that works with the car. Remembered for the
     * session, so CONNECT AGAIN can bring the phone back to it when Android Auto has pulled it away.
     */
    private fun markCarNetwork(huIp: String) {
        val subnet = CarNetLogic.subnetOf(huIp)
        val nets = CarDetector.localNetworks()
        val net = nets.firstOrNull { CarNetLogic.onSubnet(it.ip, subnet) } ?: return
        if (!net.name.lowercase().startsWith("wlan")) {
            carNet = null // a cable (Ethernet): there is no WiFi to come back to
            return
        }
        val ssid = WifiNames.currentSsid(ctx)
        carNet = CarNet(net.name, net.ip, CarNetLogic.subnetOf(net.ip), ssid)
        AppLog.i("Wifi", "The vehicle answers on ${net.name} ${net.ip}" + (ssid?.let { " (WiFi \"$it\")" } ?: " (the WiFi name is not known)"))
        // another WiFi than the one the person described: the earlier answer is not about this one
        val known = dongle
        if (known != null && ssid != null && !ssid.equals(known.ssid, ignoreCase = true)) {
            dongle = null
            dongleDeclined = false
        }
    }

    /** On entering the pages that read logs: asks once whether the WiFi the car answered on is the dongle. */
    private fun maybeAskDongle() {
        if (carNet == null || dongle != null || dongleDeclined) return
        askDongle = true
    }

    /** The name of the WiFi the phone is on, but only while it is still on the car's network (else it would be the wrong one). */
    fun readCarSsid(): String? {
        val n = carNet ?: return null
        if (CarDetector.localNetworks().none { CarNetLogic.onSubnet(it.ip, n.subnet) }) return null
        return WifiNames.currentSsid(ctx)
    }

    fun saveDongle(ssid: String, password: String) {
        val name = ssid.trim()
        if (name.isEmpty()) return
        dongle = DongleWifi(name, password)
        dongleDeclined = false
        askDongle = false
        AppLog.i("Wifi", "The dongle's WiFi is \"$name\" (password ${if (password.isEmpty()) "none" else "given"})")
    }

    fun declineDongle() {
        dongleDeclined = true
        askDongle = false
        AppLog.i("Wifi", "The person said this is not the dongle's WiFi, or not now")
    }

    /** CONNECT AGAIN: the window that asks for Bluetooth off and then joins the dongle's WiFi. */
    fun openConnectAgain() {
        if (carWifi.join != com.nbtaad.editor.net.WifiJoin.CONNECTED) carWifi.release()
        connectDialog = true
    }

    fun connectAgain() {
        dongle?.let { carWifi.connect(it) }
    }

    fun closeConnectAgain() {
        connectDialog = false
    }

    // ---------------- the port probe (what answers on the car's WiFi while Android Auto runs) ----------------

    /** The window of the port probe. */
    var probeDialog by mutableStateOf(false)
    var probeBusy by mutableStateOf(false); private set

    /** The progress while it runs, the result when it is done. */
    var probeText by mutableStateOf(""); private set
    private var probeJob: Job? = null

    fun openProbe() {
        probeDialog = true
    }

    /** Closes the window; a probe that is still running is stopped. */
    fun closeProbe() {
        probeJob?.cancel()
        probeDialog = false
    }

    fun stopProbe() {
        probeJob?.cancel()
    }

    /** [full]: every port (1-65535), else the ports up to 10000. Only looks; see [PortProbe]. */
    fun startProbe(full: Boolean) {
        if (probeBusy) return
        probeBusy = true
        probeText = "Starting…"
        AppLog.i("Probe", "Port probe started (${if (full) "every port" else "ports up to 10000"})")
        probeJob = viewModelScope.launch(Dispatchers.IO) {
            val me = coroutineContext[Job]
            try {
                NetworkBinder.bindToLocalNetwork(ctx)
                val text = PortProbe.run(ctx, CarDetector.localNetworks(), full, say = { probeText = it }, cancelled = { me?.isActive == false })
                probeText = text
            } catch (e: Exception) {
                AppLog.e("Probe", "The port probe failed", e)
                probeText = "The probe failed: ${friendlyError(e)}"
            } finally {
                probeBusy = false
                AppLog.i("Probe", "Port probe ended")
            }
        }
    }

    fun setManual(zgwText: String, huText: String) {
        manualDialog = false
        pollJob?.cancel()
        resetting = false
        busy = true
        feedbackError = false
        feedback = "Checking the entered addresses…"
        viewModelScope.launch(Dispatchers.IO) {
            try {
                NetworkBinder.bindToLocalNetwork(ctx)
                val z = zgwText.trim().ifEmpty { null }
                val h = huText.trim().ifEmpty { null }
                AppLog.i("Detect", "Manual addresses: gateway $z, head unit $h")
                val vin = z?.let { Hsfz.readVin(it) }
                val ssh = h?.let { CarDetector.sshOpen(it) } ?: false
                detect = DetectState(z != null || h != null, z, h, vin, ssh)
                feedback = when {
                    h != null && ssh -> "SSH is open on $h."
                    h != null -> "SSH is closed on $h."
                    else -> "Gateway set. Enter the head unit address too."
                }
            } catch (e: Exception) {
                AppLog.e("Detect", "Checking the manual addresses failed", e)
                feedbackError = true
                feedback = "Check failed: ${friendlyError(e)}"
            } finally {
                busy = false
            }
        }
    }

    /**
     * Reset the head unit through the ZGW - the only way this app resets a head unit now. Asking the head unit's own
     * shell to restart over SSH has been retired: on a real head unit it has hung the whole system twice, with the
     * power button the only way back, so it is never tried, not even when the gateway is unreachable but SSH is open
     * (the car's own Android Auto WiFi, no gateway on it). In that situation this simply reports that there is no
     * gateway to reset through - see [canResetHeadUnit]. The car is scanned again first, so the reset goes to the
     * gateway as it is now (the addresses of an earlier scan may be old); only when the scan finds nothing, the
     * address that was known is tried. [fromWindow]: it was started from a result window, which shows the progress
     * as [resetNote] instead of the shared feedback line.
     */
    fun resetHeadUnit(fromWindow: Boolean = false) {
        pollJob?.cancel()
        resetting = true
        feedbackError = false
        resetNote = if (fromWindow) "Looking for the vehicle…" else null
        fun note(text: String) { if (resetNote != null) resetNote = text }
        feedback = "Looking for the vehicle…"
        pollJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                NetworkBinder.bindToLocalNetwork(ctx)
                AppLog.i("Reset", "Reset requested (from a result window: $fromWindow). Known: gateway ${detect.zgw}, head unit ${detect.hu}")
                val scan = detectCar { if (!fromWindow) feedback = it else AppLog.i("Detect", it) }
                var gw = detect.zgw
                if (scan.zgwIp != null) {
                    gw = scan.zgwIp
                    detect = detect.copy(
                        carFound = true, zgw = scan.zgwIp, vin = scan.vin ?: detect.vin,
                        hu = scan.huIp ?: detect.hu, ssh = scan.sshOpen,
                    )
                }
                AppLog.i("Reset", "Scan before the reset: gateway ${scan.zgwIp}, head unit ${scan.huIp}, SSH ${scan.sshOpen}; sending to $gw")
                if (gw == null) {
                    val text = scan.problem
                        ?: "No gateway (ZGW) found to reset through. Connect over the vehicle's Ethernet port (an " +
                            "ENET/OBD cable), or hold the power button for 30 seconds instead."
                    feedbackError = true
                    feedback = text
                    note(text)
                    return@launch
                }
                feedback = "Sending the reset command to the head unit…"
                note("Sending the reset command…")
                var res = Hsfz.resetHeadUnit(gw)
                if (res.unreachable) {
                    note("The gateway did not answer. Trying again…")
                    delay(3000)
                    res = Hsfz.resetHeadUnit(gw)
                }
                val msg = res.message
                if (!res.ok) {
                    val text = if (res.unreachable) "$msg Is the ignition on? Then try again." else msg
                    feedbackError = true
                    feedback = text
                    note(text)
                    return@launch
                }
                detect = detect.copy(ssh = false)
                val end = System.currentTimeMillis() + 240_000
                var waited = 0
                while (isActive && System.currentTimeMillis() < end) {
                    feedback = "$msg Waiting for the head unit to restart… (${waited}s)"
                    note("The head unit is restarting… (${waited}s)")
                    delay(5000)
                    waited += 5
                    val (hu, open) = CarDetector.findHeadUnit(CarDetector.localNetworks(), gw)
                    if (hu != null) detect = detect.copy(hu = hu, ssh = open)
                    if (open) {
                        feedback = "Head unit is back and SSH is open."
                        note("The head unit is back.")
                        return@launch
                    }
                }
                feedbackError = true
                feedback = "SSH did not open within 4 minutes. Check the head unit, then try again."
                note("The head unit did not come back within 4 minutes.")
            } catch (e: Exception) {
                AppLog.e("Reset", "Reset failed", e)
                feedbackError = true
                feedback = "Reset failed: ${friendlyError(e)}"
                note("Reset failed: ${friendlyError(e)}")
            } finally {
                resetting = false
            }
        }
    }

    /** A reset can be sent only through the vehicle's gateway (ZGW) - asking the head unit's own shell to restart has been retired as too risky. */
    val canResetHeadUnit: Boolean get() = detect.zgw != null && detect.vin != null

    // ---------------- the Reset window (3-dot menu): the same gateway-only reset, with each step listed ----------------

    var resetWindowOpen by mutableStateOf(false)
    var resetWindowBusy by mutableStateOf(false); private set
    var resetWindowLog by mutableStateOf<List<WriteStep>>(emptyList()); private set

    fun openResetWindow() {
        resetWindowOpen = true
    }

    fun closeResetWindow() {
        if (resetWindowBusy) return
        resetWindowOpen = false
    }

    /**
     * RESET HU in the window opened from the 3-dot menu: the same single reset as [resetHeadUnit] (through the
     * gateway only - see its doc comment for why that is the only path left), but reports each step as its own line
     * in [resetWindowLog], red dot when it did not work, so the person can see exactly what was found and sent
     * instead of only the last status line. The vehicle is scanned fresh first, so the reset is never sent to a
     * stale address.
     */
    fun resetHeadUnitWindow() {
        if (resetWindowBusy) return
        resetWindowBusy = true
        resetWindowLog = emptyList()
        fun log(name: String, ok: Boolean, detail: String) {
            resetWindowLog = resetWindowLog + WriteStep(name, ok, detail)
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                NetworkBinder.bindToLocalNetwork(ctx)
                val scan = detectCar { }
                log(
                    "Look for the vehicle",
                    scan.zgwIp != null || scan.huIp != null,
                    "Gateway " + (scan.zgwIp ?: "not found") + ", head unit " + (scan.huIp ?: "not found") +
                        (scan.huIp?.let { if (scan.sshOpen) " (SSH open)" else " (SSH closed)" } ?: ""),
                )
                val gw = scan.zgwIp
                var zgwOk = false
                if (gw != null) {
                    var res = Hsfz.resetHeadUnit(gw)
                    if (res.unreachable) {
                        delay(3000)
                        res = Hsfz.resetHeadUnit(gw)
                    }
                    log("Reset by the gateway (ZGW)", res.ok, res.message)
                    zgwOk = res.ok
                } else {
                    log(
                        "Reset by the gateway (ZGW)", false,
                        "No gateway found: nothing to send the reset to. Connect over the vehicle's Ethernet port " +
                            "(an ENET/OBD cable), or hold the power button for 30 seconds instead.",
                    )
                }
                if (zgwOk) {
                    detect = detect.copy(ssh = false)
                    log("Wait for the head unit", true, "Waiting for SSH to open again…")
                    val end = System.currentTimeMillis() + 240_000
                    var found: String? = null
                    while (isActive && System.currentTimeMillis() < end && found == null) {
                        delay(5000)
                        val (hu, open) = CarDetector.findHeadUnit(CarDetector.localNetworks(), gw)
                        if (hu != null && open) found = hu
                    }
                    resetWindowLog = resetWindowLog.dropLast(1) + if (found != null) {
                        detect = detect.copy(hu = found, ssh = true)
                        WriteStep("Wait for the head unit", true, "Back: SSH is open on $found.")
                    } else {
                        WriteStep("Wait for the head unit", false, "SSH did not open within 4 minutes.")
                    }
                }
            } catch (e: Exception) {
                AppLog.e("Reset", "Reset failed", e)
                log("Reset", false, friendlyError(e))
            } finally {
                resetWindowBusy = false
            }
        }
    }

    /** What "Check codings" on the Prepare headunit page last found; null before it has run once. */
    var codingResults by mutableStateOf<List<com.nbtaad.editor.net.CodingResult>?>(null)
    var codingChecking by mutableStateOf(false)
    /** Set when the (i) next to the CODING PASSED / CODINGS NOT SET line is tapped. */
    var showCodingDetail by mutableStateOf(false)
    /** Set when SHOW LOG (the last write/revert) is tapped. */
    var showCodingWriteLog by mutableStateOf(false)

    /** The Prepare page's big PASSED/NOT SET/STATUS UNKNOWN banner. */
    enum class CodingCheckState { UNKNOWN, PASSED, NOT_SET }

    /**
     * UNKNOWN covers both "never checked" and "the last check couldn't actually read every bit" (the
     * head unit answering with a UDS rejection, usually because it's still booting after a reset) - a
     * failed read is not evidence a coding is unset, so it must never render as a NOT SET verdict, and
     * both cases get the same visual weight to invite a CHECK CODINGS tap.
     */
    val codingCheckState: CodingCheckState
        get() {
            val r = codingResults
            return when {
                r.isNullOrEmpty() -> CodingCheckState.UNKNOWN
                r.any { it.status == com.nbtaad.editor.net.CodingStatus.UNREADABLE } -> CodingCheckState.UNKNOWN
                r.all { it.status == com.nbtaad.editor.net.CodingStatus.SET } -> CodingCheckState.PASSED
                else -> CodingCheckState.NOT_SET
            }
        }

    /**
     * "Check codings": the page is reachable with no car known yet, so this detects the car first (refreshing
     * SSH/ZGW/VIN, same as any other page's own look for it), then reads the handful of individual coding bits
     * Android Auto needs straight from the head unit over that gateway. A plain read either way - no
     * SecurityAccess, no key, nothing written to the car or its Vehicle Order.
     */
    fun checkCodings() {
        if (codingChecking) return
        codingChecking = true
        forceWriteArmed = false
        viewModelScope.launch(Dispatchers.IO) {
            try {
                NetworkBinder.bindToLocalNetwork(ctx)
                val r = detectCar { feedback = it }
                applyResult(r)
                val ip = detect.zgw
                codingResults = if (ip != null) com.nbtaad.editor.net.CodingCheck.checkAll(ip) else null
            } catch (e: Exception) {
                AppLog.e("Coding", "Checking the coding bits failed", e)
            } finally {
                codingChecking = false
            }
        }
    }

    // =====================================================================
    // Writing coding: the owner's own recovered keys, a mandatory backup, then a signed write
    // =====================================================================

    /** The only bootloader this app targets (the NBT EVO head unit) - same constant L3.kt defaults to. */
    private val CODING_BTLD = "00001FFD"

    private val codingKeyStore by lazy { com.nbtaad.editor.net.CodingKeyStore(ctx) }

    var hasNcdKey by mutableStateOf(codingKeyStore.hasNcdKey())
    var hasL3Key by mutableStateOf(codingKeyStore.hasL3Key())

    /** The last backup read (datagroups 0x3000-0x3010): the mandatory first step before any write, and the revert target. */
    var codingBackup by mutableStateOf<com.nbtaad.editor.net.BackupResult?>(null)
    var codingBusy by mutableStateOf(false)
    var codingWriteLog by mutableStateOf<List<com.nbtaad.editor.net.WriteStep>?>(null)
    /** The head unit's own DTC count at the moment the backup was read - the "before" half of the
     *  before/after noted in [applyAaCoding]/[revertCoding]'s feedback. Null if it couldn't be read. */
    var dtcCountBeforeWrite by mutableStateOf<Int?>(null)
    /** Set when WRITE CODING is tapped, to show the confirmation dialog; cleared on either answer. */
    var confirmWriteCoding by mutableStateOf(false)

    /** The write dialog's "Clear DTC?" choice: what [applyAaCoding] clears once the write is confirmed back. */
    enum class ClearDtcChoice { DONT, HEAD_UNIT_ONLY, WHOLE_VEHICLE }
    /** Remembers the last choice across writes, since a test session usually repeats the same one. */
    var clearDtcChoice by mutableStateOf(ClearDtcChoice.WHOLE_VEHICLE)
    /** Read-only SecurityAccess level probe (PROBE SECURITYACCESS) - never sends a key, nothing is unlocked or written. */
    var probingSecurityAccess by mutableStateOf(false)
    var securityAccessProbeLog by mutableStateOf<List<com.nbtaad.editor.net.WriteStep>?>(null)

    /** Armed by 5 taps within 1.7s on WRITE CODING while it has nothing pending (see [onWriteButtonMultiTap]):
     *  turns it into "WRITE AGAIN", re-sending every bit CHECK CODINGS already confirms, purely to test the
     *  write/clear-faults path without first having to change a real coding just to light the button up.
     *  Cleared once a write is actually kicked off, so each test needs its own fresh burst of taps. */
    var forceWriteArmed by mutableStateOf(false)
    private val writeTapTimes = mutableListOf<Long>()

    /** Call on every tap of a disabled WRITE CODING button (an invisible overlay in PrepareUi catches these,
     *  since a disabled Button normally swallows touches): counts a 1.7s burst and arms [forceWriteArmed]
     *  on the 5th tap. */
    fun onWriteButtonMultiTap() {
        val now = System.currentTimeMillis()
        writeTapTimes.add(now)
        writeTapTimes.removeAll { now - it > 1700 }
        if (writeTapTimes.size >= 5) {
            writeTapTimes.clear()
            forceWriteArmed = true
            feedback = "Test mode armed: WRITE AGAIN will re-send every coding CHECK CODINGS already confirms, even with nothing pending."
            feedbackError = false
        }
    }

    /** Imports a key file the person picked (ncd_key.json or l3_key.json, from the PC-side recovery tool). */
    fun importCodingKey(uri: android.net.Uri, ncd: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?.toString(Charsets.UTF_8) ?: throw java.io.IOException("Could not open that file.")
                if (ncd) codingKeyStore.importNcdKey(text) else codingKeyStore.importL3Key(text)
                hasNcdKey = codingKeyStore.hasNcdKey()
                hasL3Key = codingKeyStore.hasL3Key()
                feedback = "Key imported."
                feedbackError = false
            } catch (e: Exception) {
                AppLog.e("Coding", "Importing a coding key failed", e)
                feedback = "Could not import that file: ${friendlyError(e)}"
                feedbackError = true
            }
        }
    }

    var recoveringKeys by mutableStateOf(false)

    /** Set when LOAD SIGNING TOOLS is tapped, to show the "these have to be yours" explainer first. */
    var showRecoveryInfo by mutableStateOf(false)

    /** Result of the last on-device recovery attempt - shown right by the Coding keys button, not the shared [feedback] (that one renders up by CHECK AGAIN, far off-screen from here). */
    var recoveryFeedback by mutableStateOf("")
    var recoveryFeedbackError by mutableStateOf(false)

    /**
     * Recovers both coding keys on the phone itself, from a folder the owner picked (their own
     * PSdZData `security/` folder, or anything containing it) - no PC, no Python. Walks the
     * folder for `sec_security.lic`/`sec_keyreference.xml`/`sec_ncdkeys.xml`/`sec_auth_l3.xml`
     * and the BMW logo resources (`resource.jar`, or the two PNGs loose), then runs the exact
     * same recovery `nbtevo.keys` does on a PC (see net/PsdzRecovery.kt).
     */
    fun recoverCodingKeysFromFolder(treeUri: android.net.Uri) {
        if (recoveringKeys) return
        recoveringKeys = true
        recoveryFeedback = ""
        recoveryFeedbackError = false
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val found = com.nbtaad.editor.net.PsdzFolderScan.scan(ctx, treeUri)
                var gotNcd = false
                var gotL3 = false
                found.ncdKeysXml?.let { ncdXml ->
                    val key = com.nbtaad.editor.net.PsdzKeyRecovery.recover(
                        found.securityLic, found.keyreferenceXml, ncdXml,
                        CODING_BTLD, "ncd", found.logoPng, found.miniLogoPng,
                    )
                    codingKeyStore.importNcdKey(key.toJson())
                    gotNcd = true
                }
                found.authL3Xml?.let { l3Xml ->
                    val key = com.nbtaad.editor.net.PsdzKeyRecovery.recover(
                        found.securityLic, found.keyreferenceXml, l3Xml,
                        CODING_BTLD, "l3", found.logoPng, found.miniLogoPng,
                    )
                    codingKeyStore.importL3Key(key.toJson())
                    gotL3 = true
                }
                hasNcdKey = codingKeyStore.hasNcdKey()
                hasL3Key = codingKeyStore.hasL3Key()
                recoveryFeedback = when {
                    gotNcd && gotL3 -> "Recovered both keys from that folder."
                    gotNcd -> "Recovered the NCD key. No sec_auth_l3.xml was found in that folder for the L3 key."
                    gotL3 -> "Recovered the L3 key. No sec_ncdkeys.xml was found in that folder for the NCD key."
                    else -> "Nothing recovered."
                }
                recoveryFeedbackError = !(gotNcd || gotL3)
            } catch (e: Exception) {
                AppLog.e("Coding", "On-device key recovery failed", e)
                recoveryFeedback = "Couldn't recover a key from that folder: ${friendlyError(e)}"
                recoveryFeedbackError = true
            } finally {
                recoveringKeys = false
            }
        }
    }

    /** "Forget these keys": the PC-side tool can always produce them again from the owner's own PSdZData. */
    fun forgetCodingKeys() {
        codingKeyStore.clearKeys()
        hasNcdKey = false
        hasL3Key = false
        codingBackup = null
        codingWriteLog = null
    }

    /** Back from the Signing tools page to Prepare headunit. */
    fun closeSigningTools() {
        screen = Screen.PREPARE
    }

    /** Reads a fresh backup of the car's current coding. Mandatory before a write; also the revert target afterward. */
    fun readCodingBackup() {
        val ip = detect.zgw ?: return
        if (codingBusy) return
        codingBusy = true
        codingWriteLog = null
        dtcCountBeforeWrite = null
        forceWriteArmed = false
        viewModelScope.launch(Dispatchers.IO) {
            try {
                codingBackup = com.nbtaad.editor.net.CodingWrite.readBackup(ip)
                // Best-effort "before" snapshot for the write/revert feedback below; failing to read it
                // just means that note is skipped later, not that the backup itself failed.
                dtcCountBeforeWrite = com.nbtaad.editor.net.CodingWrite.headUnitDtcCount(ip)
            } catch (e: Exception) {
                AppLog.e("Coding", "Reading the coding backup failed", e)
            } finally {
                codingBusy = false
            }
        }
    }

    /** REMOVE BACKUP (the ❌ next to the backup line): discards it so READ BACKUP can fetch a fresh one - e.g. once a write has moved on past it. */
    fun removeCodingBackup() {
        codingBackup = null
        forceWriteArmed = false
    }

    /** WRITE CODING tapped: the keys have to be loaded first, since writing can't be signed without them. */
    fun onWriteCodingTapped() {
        if (!hasNcdKey || !hasL3Key) screen = Screen.SIGNING_TOOLS else confirmWriteCoding = true
    }

    /** REVERT TO BACKUP tapped: same key check as WRITE CODING, since reverting signs the backup back through the same path. */
    fun onRevertTapped() {
        if (!hasNcdKey || !hasL3Key) screen = Screen.SIGNING_TOOLS else revertCoding()
    }

    /** The AA coding bits CHECK CODINGS found not set, paired with the value they should be — what a normal WRITE CODING tap sends. */
    private val pendingCodingFixes: List<Pair<com.nbtaad.editor.net.CodingBit, Int>>
        get() = (codingResults ?: emptyList())
            .filter { it.status == com.nbtaad.editor.net.CodingStatus.NOT_SET }
            .mapNotNull { r -> r.bit.expectedOn?.let { r.bit to it } }

    /** Every bit CHECK CODINGS has a known expected value for, set or not — what WRITE AGAIN sends while [forceWriteArmed]. */
    private val allCodingFixes: List<Pair<com.nbtaad.editor.net.CodingBit, Int>>
        get() = (codingResults ?: emptyList()).mapNotNull { r -> r.bit.expectedOn?.let { r.bit to it } }

    /** What WRITE CODING actually sends: the pending fixes normally, or every known bit once [forceWriteArmed]. */
    val codingFixes: List<Pair<com.nbtaad.editor.net.CodingBit, Int>>
        get() = if (forceWriteArmed) allCodingFixes else pendingCodingFixes

    /**
     * Whether WRITE CODING's button is enabled: a backup, a known VIN, and something to change. The keys are
     * checked separately when it's tapped (see [onWriteCodingTapped]) rather than gating `enabled` here, so a
     * tap with no keys loaded can still navigate to Signing tools instead of silently doing nothing.
     */
    val canWriteCoding: Boolean
        get() = codingBackup?.mem != null && detect.vin != null && codingFixes.isNotEmpty() && !codingBusy

    /**
     * Runs the write dialog's "Clear DTC?" choice ([choice] - none, head-unit-only, or the whole-car
     * broadcast macro, see [ClearDtcChoice]) and appends its steps to [codingWriteLog] so SHOW LOG
     * covers it too. Returns a short sentence to tack onto [feedback]: the head unit's own before/after
     * DTC count when both are readable (the [dtcCountBeforeWrite] snapshot READ BACKUP took, and a fresh
     * read now), otherwise just whether the clear itself went through - or nothing at all for DONT.
     * Called from [applyAaCoding] and [revertCoding] only once the head unit is confirmed back - never
     * right after the reset is sent.
     */
    private fun clearCarFaultsAndDescribe(ip: String, choice: ClearDtcChoice): String {
        if (choice == ClearDtcChoice.DONT) return ""
        val scope = if (choice == ClearDtcChoice.HEAD_UNIT_ONLY) "the head unit's" else "the vehicle's"
        val steps = if (choice == ClearDtcChoice.HEAD_UNIT_ONLY) {
            com.nbtaad.editor.net.CodingWrite.clearHeadUnitFaults(ip)
        } else {
            com.nbtaad.editor.net.CodingWrite.clearAllCarFaults(ip)
        }
        codingWriteLog = (codingWriteLog ?: emptyList()) + steps
        for (s in steps) {
            if (s.ok) AppLog.i("Coding", "Clear faults - ${s.name}: ${s.detail}") else AppLog.w("Coding", "Clear faults - ${s.name}: ${s.detail}")
        }
        val before = dtcCountBeforeWrite
        val after = com.nbtaad.editor.net.CodingWrite.headUnitDtcCount(ip)
        return when {
            steps.any { !it.ok } -> " Couldn't fully clear $scope fault memory - see the log."
            before != null && after != null -> " Cleared $scope fault memory (head unit: $before → $after)."
            else -> " Cleared $scope fault memory."
        }
    }

    /**
     * Polls the head unit's SSH port (up to [timeoutMs]) so the post-write/revert re-check waits for the
     * ECU to actually be alive again instead of guessing with a fixed delay - checking too soon is what
     * made CHECK CODINGS misreport a plain read failure as "codings didn't pass" right after a write.
     * Returns true once SSH answers, false if it gave up waiting.
     */
    private suspend fun waitForHeadUnitAlive(timeoutMs: Long = 40_000): Boolean {
        val hu = detect.hu ?: return true // no known address to probe - nothing to wait for
        delay(4000) // it can't possibly be back any sooner than this
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (CarDetector.sshOpen(hu)) return true
            delay(1500)
        }
        return false
    }

    /** WRITE CODING, after the person has confirmed: re-signs the backup with the fixes applied and writes it through. */
    fun applyAaCoding() {
        confirmWriteCoding = false
        val ip = detect.zgw ?: return
        val backup = codingBackup?.mem ?: return
        val vin = detect.vin ?: return
        val ncdKey = codingKeyStore.readNcdKey() ?: return
        val l3Key = codingKeyStore.readL3Key() ?: return
        val fixes = codingFixes
        if (fixes.isEmpty() || codingBusy) return
        // Captured above, while still armed if this was a forced WRITE AGAIN - disarm now so the next
        // test needs its own fresh burst of taps rather than silently staying in force-write mode.
        forceWriteArmed = false
        codingBusy = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val outcome = com.nbtaad.editor.net.CodingWrite.writeCoding(ip, backup, fixes, ncdKey, l3Key, vin)
                codingWriteLog = outcome.steps
                for (s in outcome.steps) {
                    if (s.ok) AppLog.i("Coding", "Write step - ${s.name}: ${s.detail}") else AppLog.w("Coding", "Write step - ${s.name}: ${s.detail}")
                }
                if (outcome.success) {
                    // That backup predates this write, so it no longer matches the car - clear it so REVERT TO
                    // BACKUP can't undo this write by accident, and READ BACKUP fetches a fresh one when wanted.
                    codingBackup = null
                    feedback = "Wrote ${fixes.size} coding bit(s). Waiting for the head unit to come back online…"
                    feedbackError = false
                    if (waitForHeadUnitAlive()) {
                        codingResults = try {
                            com.nbtaad.editor.net.CodingCheck.checkAll(ip)
                        } catch (e: Exception) {
                            AppLog.w("Coding", "Auto re-check after the write failed: ${e.message}")
                            null
                        }
                        // Only now, with the head unit confirmed back - not right after the reset was
                        // sent - is it safe to assume it's listening on the bus again for the clear to
                        // actually reach it.
                        val faultsNote = clearCarFaultsAndDescribe(ip, clearDtcChoice)
                        feedback = (
                            when (codingCheckState) {
                                CodingCheckState.PASSED -> "Wrote ${fixes.size} coding bit(s) - confirmed set."
                                CodingCheckState.NOT_SET -> "Wrote ${fixes.size} coding bit(s), but the re-check doesn't show them all set yet - give the head unit a moment and tap CHECK CODINGS."
                                CodingCheckState.UNKNOWN -> "Wrote ${fixes.size} coding bit(s), but couldn't read them back cleanly to confirm - give the head unit a moment and tap CHECK CODINGS."
                            }
                            ) + faultsNote
                        feedbackError = codingCheckState != CodingCheckState.PASSED
                    } else {
                        codingResults = null
                        feedback = "Wrote ${fixes.size} coding bit(s), but the head unit's SSH never came back on its own - " +
                            "give it a moment, then tap CHECK CODINGS once it's reachable again."
                        feedbackError = true
                    }
                } else {
                    feedback = "The write stopped partway (see the log) - nothing further was sent."
                    feedbackError = true
                }
            } catch (e: Exception) {
                AppLog.e("Coding", "Writing coding failed", e)
                feedback = "Writing coding failed: ${friendlyError(e)}"
                feedbackError = true
            } finally {
                codingBusy = false
            }
        }
    }

    /** REVERT TO BACKUP: writes the last backup back, unchanged - already correctly signed, since it came from the car. */
    fun revertCoding() {
        val ip = detect.zgw ?: return
        val backup = codingBackup?.mem ?: return
        val vin = detect.vin ?: return
        val ncdKey = codingKeyStore.readNcdKey() ?: return
        val l3Key = codingKeyStore.readL3Key() ?: return
        if (codingBusy) return
        codingBusy = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val outcome = com.nbtaad.editor.net.CodingWrite.revertCoding(ip, backup, ncdKey, l3Key, vin)
                codingWriteLog = outcome.steps
                for (s in outcome.steps) {
                    if (s.ok) AppLog.i("Coding", "Revert step - ${s.name}: ${s.detail}") else AppLog.w("Coding", "Revert step - ${s.name}: ${s.detail}")
                }
                if (outcome.success) {
                    // Unlike a write, the backup is still accurate afterward - it's exactly what was just written back.
                    feedback = "Reverted to the backup. Waiting for the head unit to come back online…"
                    feedbackError = false
                    if (waitForHeadUnitAlive()) {
                        codingResults = try {
                            com.nbtaad.editor.net.CodingCheck.checkAll(ip)
                        } catch (e: Exception) {
                            AppLog.w("Coding", "Auto re-check after the revert failed: ${e.message}")
                            null
                        }
                        // Same reasoning as applyAaCoding(): wait for the head unit to be confirmed back first.
                        val faultsNote = clearCarFaultsAndDescribe(ip, clearDtcChoice)
                        feedback = "Reverted to the backup." + faultsNote
                        feedbackError = false
                    } else {
                        codingResults = null
                        feedback = "Reverted to the backup, but the head unit's SSH never came back on its own - " +
                            "give it a moment, then tap CHECK CODINGS once it's reachable again."
                        feedbackError = true
                    }
                } else {
                    feedback = "The revert stopped partway (see the log)."
                    feedbackError = true
                }
            } catch (e: Exception) {
                AppLog.e("Coding", "Reverting coding failed", e)
                feedback = "Revert failed: ${friendlyError(e)}"
                feedbackError = true
            } finally {
                codingBusy = false
            }
        }
    }

    /**
     * Read-only diagnostic: tries SecurityAccess requestSeed at a handful of candidate levels inside
     * the coding session - never sends a key, so nothing is ever unlocked or written to the car. For
     * when WRITE CODING's own "SecurityAccess: request seed" step is rejected: the reference project
     * this app's write flow is ported from is explicit that level 0x01 for the *coding* write was
     * carried over from the flash level (0x11), which was proven on a real car, but was never itself
     * independently confirmed - this finds out, on this car, instead of guessing further.
     */
    fun probeSecurityAccessLevels() {
        val ip = detect.zgw ?: return
        val vin = detect.vin ?: return
        if (probingSecurityAccess) return
        probingSecurityAccess = true
        securityAccessProbeLog = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val steps = com.nbtaad.editor.net.CodingWrite.probeSecurityAccessLevels(ip, vin)
                securityAccessProbeLog = steps
                for (s in steps) {
                    if (s.ok) AppLog.i("Coding", "Probe step - ${s.name}: ${s.detail}") else AppLog.w("Coding", "Probe step - ${s.name}: ${s.detail}")
                }
            } catch (e: Exception) {
                AppLog.e("Coding", "Probing SecurityAccess levels failed", e)
                securityAccessProbeLog = listOf(com.nbtaad.editor.net.WriteStep("Connect", false, e.message ?: e.javaClass.simpleName))
            } finally {
                probingSecurityAccess = false
            }
        }
    }

    /** Why the last look for the car, started by an action that needed it, found no head unit (null: it found one). */
    @Volatile
    private var scanProblem: String? = null

    /**
     * Looks for the vehicle's SSH for an action that needs the head unit and has no address, or whose address does
     * not answer; returns the head unit's address when SSH answers there. No gateway (ZGW) scan: only Prepare
     * headunit needs that. Every page that needs the head unit does this for itself, instead of a dedicated
     * detection step before the rest of the app is reachable.
     */
    private fun rescanForHost(): String? {
        NetworkBinder.bindToLocalNetwork(ctx)
        AppLog.i("Detect", if (detect.hu == null) "Looking for the head unit (none known yet)" else "Looking for the head unit again (no SSH connection)")
        val r = detectCarSsh { AppLog.i("Detect", it) }
        applyResult(r)
        val host = if (r.sshOpen) r.huIp else null
        scanProblem = if (host != null) null else feedback
        return host
    }

    /** What a screen says while an action looks for the car: the first look when no car is known, else a new look. */
    private fun lookingNote(): String =
        if (detect.hu == null) "No vehicle known yet. Looking for the vehicle…" else "No connection. Looking for the vehicle again…"

    // =====================================================================
    // starter.cfg: reading the car's own live file (research step for the SSH-unlock feature).
    // Needs SSH already open (OPEN SSH / Bavarian) - this reads over that same session. The actual
    // SSH-unlock write goes over UDS (FlashWrite) and isn't wired to a button yet.
    // =====================================================================

    private val starterCfgStore by lazy { com.nbtaad.editor.net.StarterCfgStore(ctx) }

    var scBusy by mutableStateOf(false)
    var scError by mutableStateOf(false)
    var scFeedback by mutableStateOf("")
    var scLibraryTags by mutableStateOf(starterCfgStore.tags())
    var scCheckingBuild by mutableStateOf(false)
    var scCurrentTag by mutableStateOf<String?>(null)
    var scCurrentHasStock by mutableStateOf(false)
    var scImportBusy by mutableStateOf(false)

    /**
     * Pulls `starter.cfg` straight out of a SWFL pool file from the owner's own PSdZData (e.g.
     * `swfl_0000271a.bin.007_051_002`) and adds it to the library - no live car, no SSH, no PC/Python
     * step, matching the toolkit's own documented alternative to a live read. The build tag is parsed
     * from the file's own name, the same scheme PSdZData itself uses - refuses rather than guesses if
     * that fails, same as every other path into this library.
     */
    fun importStarterCfgFromContainer(uri: android.net.Uri, displayName: String?) {
        if (scImportBusy) return
        scImportBusy = true
        scError = false
        scFeedback = "Reading…"
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val tag = displayName?.let { com.nbtaad.editor.net.StarterCfgStore.swflVersionFromFilename(it) }
                if (tag == null) {
                    scError = true
                    scFeedback = "Couldn't find a build version (like 007_051_002) in that file's name - " +
                        "rename it to match the PSdZData convention (e.g. swfl_0000271a.bin.007_051_002) and try again."
                    return@launch
                }
                val found = ctx.contentResolver.openInputStream(uri)?.use { input ->
                    com.nbtaad.editor.net.ContainerExtract.extract(input, "starter.cfg")
                } ?: throw java.io.IOException("The phone would not open that file for reading.")
                when (found) {
                    is com.nbtaad.editor.net.ContainerExtract.Result.Found -> {
                        // PSdZData carries this payload as Intel HEX, not a plain file - one more decode
                        // step a live SSH read never needs (that's already a plain file on the car).
                        val hex = com.nbtaad.editor.net.IntelHex.looksLikeIntelHex(found.bytes)
                        val payload = if (hex) {
                            try {
                                com.nbtaad.editor.net.IntelHex.decode(found.bytes)
                            } catch (e: IllegalArgumentException) {
                                scError = true
                                scFeedback = "Found a starter.cfg payload, but decoding it as Intel HEX failed: ${e.message}"
                                return@launch
                            }
                        } else {
                            found.bytes
                        }
                        starterCfgStore.importStock(payload, tag)
                        scLibraryTags = starterCfgStore.tags()
                        if (scCurrentTag == tag) scCurrentHasStock = true
                        scError = false
                        scFeedback = "Extracted ${payload.size} bytes of starter.cfg (build $tag)" +
                            (if (hex) " - decoded from Intel HEX" else "") + " - added to the library."
                    }
                    com.nbtaad.editor.net.ContainerExtract.Result.NotFound -> {
                        scError = true
                        scFeedback = "No starter.cfg payload found inside that file."
                    }
                }
            } catch (e: Exception) {
                AppLog.e("StarterCfg", "Extracting starter.cfg from a container failed", e)
                scError = true
                scFeedback = "Failed: ${friendlyError(e)}"
            } finally {
                scImportBusy = false
            }
        }
    }

    /**
     * READ-ONLY: confirms which SWFL build the connected car is on - over UDS to the gateway, no SSH
     * needed - and whether the library already has a matching starter.cfg for it. Called whenever the
     * Prepare screen sees a gateway connection, so the unlock/lock buttons only ever show once this
     * exact unit's build is actually confirmed to be in the library, not just "some build is."
     */
    fun checkBuild() {
        val ip = detect.zgw ?: return
        if (scCheckingBuild) return
        scCheckingBuild = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                NetworkBinder.bindToLocalNetwork(ctx)
                val tag = com.nbtaad.editor.net.FlashWrite.readCoreSwflVersion(ip)
                scCurrentTag = tag
                scCurrentHasStock = tag != null && starterCfgStore.hasStock(tag)
            } catch (e: Exception) {
                AppLog.e("StarterCfg", "Checking this unit's build failed", e)
            } finally {
                scCheckingBuild = false
            }
        }
    }

    fun readStarterCfg() {
        if (scBusy) return
        scBusy = true
        scError = false
        scFeedback = "Connecting…"
        viewModelScope.launch(Dispatchers.IO) {
            try {
                NetworkBinder.bindToLocalNetwork(ctx)
                val found = runOnHeadUnit(
                    { detect.hu }, { rescanForHost() },
                    onRescan = { scFeedback = lookingNote() },
                    whyNot = { scanProblem },
                ) { hu -> com.nbtaad.editor.net.StarterCfgRemote.readLive(hu) { scFeedback = it } }
                when (found) {
                    is com.nbtaad.editor.net.StarterCfgRemote.ReadResult.Found -> {
                        val tag = detect.zgw?.let { gw ->
                            try {
                                com.nbtaad.editor.net.FlashWrite.readCoreSwflVersion(gw)
                            } catch (e: Exception) {
                                AppLog.e("StarterCfg", "Reading the build tag failed", e)
                                null
                            }
                        }
                        if (tag == null) {
                            // Refuse rather than guess a key for the library: an entry nobody can match
                            // against later is worse than no entry at all.
                            scError = true
                            scFeedback = "Read ${found.bytes.size} bytes from ${found.path}, but this unit's " +
                                "build couldn't be confirmed (SVK read failed) - not added to the library. Try again."
                        } else {
                            starterCfgStore.importStock(found.bytes, tag)
                            scLibraryTags = starterCfgStore.tags()
                            scCurrentTag = tag
                            scCurrentHasStock = true
                            scError = false
                            scFeedback = "Read ${found.bytes.size} bytes from ${found.path} (build $tag) - added to the library."
                        }
                    }
                    com.nbtaad.editor.net.StarterCfgRemote.ReadResult.NotFound -> {
                        scError = true
                        scFeedback = "starter.cfg was not found at either of the usual paths."
                    }
                }
            } catch (e: Exception) {
                AppLog.e("StarterCfg", "Reading starter.cfg failed", e)
                scError = true
                scFeedback = "Read failed: ${friendlyError(e)}"
            } finally {
                scBusy = false
            }
        }
    }

    /** Writes the library's stock file for [scCurrentTag]'s build to the file the person chose. */
    fun exportStarterCfgTo(uri: android.net.Uri) {
        val tag = scCurrentTag ?: return
        val bytes = starterCfgStore.readStock(tag) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: throw java.io.IOException("The phone would not open that file for writing.")
                scFeedback = "Exported ${bytes.size} bytes."
                scError = false
            } catch (e: Exception) {
                AppLog.e("StarterCfg", "Exporting starter.cfg failed", e)
                scError = true
                scFeedback = "Export failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    // --- The actual SSH unlock/lock: flashes the patched stock file over UDS, not SSH. ---

    var confirmSshUnlock by mutableStateOf(false)
    var confirmSshLock by mutableStateOf(false)
    var sshUnlockChecked by mutableStateOf(false)
    var showSshUnlockInfo by mutableStateOf(false)
    var showSshFlashLog by mutableStateOf(false)
    var sshFlashBusy by mutableStateOf(false)
    var sshFlashError by mutableStateOf(false)
    var sshFlashFeedback by mutableStateOf("")
    var sshFlashLog by mutableStateOf<List<com.nbtaad.editor.net.WriteStep>>(emptyList())

    /**
     * The library lookup that matters is done here, live, right before flashing - not from
     * [scCurrentTag]'s last-checked value, which could be stale (a different unit since the screen
     * last ran [checkBuild], or the same unit after a firmware update). A fresh, confirmed build tag
     * is what the stock file is matched against every single time; no match, no flash.
     */
    fun applySshToggle(target: com.nbtaad.editor.net.StarterCfgPatch.Target) {
        confirmSshUnlock = false
        confirmSshLock = false
        sshUnlockChecked = false
        if (sshFlashBusy) return
        val ip = detect.zgw
        val vin = detect.vin
        val l3Key = codingKeyStore.readL3Key()
        if (ip == null || vin == null) {
            sshFlashError = true
            sshFlashFeedback = "No vehicle connection right now."
            return
        }
        if (l3Key == null) {
            sshFlashError = true
            sshFlashFeedback = "The signing key (L3) isn't loaded yet - see Signing tools."
            return
        }
        sshFlashBusy = true
        sshFlashError = false
        sshFlashFeedback = "Confirming this unit's build…"
        viewModelScope.launch(Dispatchers.IO) {
            try {
                NetworkBinder.bindToLocalNetwork(ctx)
                val tag = com.nbtaad.editor.net.FlashWrite.readCoreSwflVersion(ip)
                scCurrentTag = tag
                if (tag == null) {
                    sshFlashError = true
                    sshFlashFeedback = "Could not confirm this unit's build (SVK read failed) - " +
                        "refusing to guess which starter.cfg fits it."
                    return@launch
                }
                val stock = starterCfgStore.readStock(tag)
                scCurrentHasStock = stock != null
                if (stock == null) {
                    sshFlashError = true
                    sshFlashFeedback = "No starter.cfg in the library for build $tag yet. Read it here " +
                        "first (needs SSH open), or import one captured for this build."
                    return@launch
                }
                when (val prep = com.nbtaad.editor.net.SshUnlock.prepare(stock, target)) {
                    is com.nbtaad.editor.net.SshUnlock.PrepareResult.Ready -> {
                        sshFlashFeedback = if (target == com.nbtaad.editor.net.StarterCfgPatch.Target.UNLOCK) "Unlocking SSH…" else "Locking SSH…"
                        val outcome = com.nbtaad.editor.net.FlashWrite.flash(ip, prep.container, l3Key, vin)
                        sshFlashLog = outcome.steps
                        for (s in outcome.steps) {
                            if (s.ok) AppLog.i("SshUnlock", "${s.name}: ${s.detail}") else AppLog.w("SshUnlock", "${s.name}: ${s.detail}")
                        }
                        sshFlashError = !outcome.success
                        sshFlashFeedback = if (outcome.success) {
                            if (target == com.nbtaad.editor.net.StarterCfgPatch.Target.UNLOCK)
                                "SSH unlocked. The head unit will carry an expected fault code until it's locked again."
                            else
                                "SSH locked back to stock."
                        } else {
                            "The flash did not complete - see the log."
                        }
                    }
                    is com.nbtaad.editor.net.SshUnlock.PrepareResult.AlreadyDone -> {
                        sshFlashError = false
                        sshFlashFeedback = if (target == com.nbtaad.editor.net.StarterCfgPatch.Target.UNLOCK) "Already unlocked." else "Already locked."
                    }
                    is com.nbtaad.editor.net.SshUnlock.PrepareResult.PatchFailed -> {
                        sshFlashError = true
                        sshFlashFeedback = prep.reason
                    }
                }
            } catch (e: Exception) {
                AppLog.e("SshUnlock", "Flashing starter.cfg failed", e)
                sshFlashError = true
                sshFlashFeedback = "Failed: ${friendlyError(e)}"
            } finally {
                sshFlashBusy = false
            }
        }
    }

    // =====================================================================
    // Navigation between the screens
    // =====================================================================

    // ---------------- the app log ----------------
    var showLog by mutableStateOf(false)
    var logNote by mutableStateOf("")

    fun logSizeText(): String = AppLog.size().let { if (it < 1024) "$it bytes" else "${it / 1024} KB" }

    fun suggestedLogName(): String = AppLog.exportName()

    fun saveLogTo(uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            logNote = try {
                val n = AppLog.saveTo(ctx, uri)
                AppLog.i("App", "The log was saved to a file ($n bytes)")
                "Saved (${n / 1024} KB). Send that file along when something goes wrong."
            } catch (e: Exception) {
                AppLog.e("App", "Saving the log failed", e)
                "Could not save the log: ${friendlyError(e)}"
            }
        }
    }

    fun clearLog() {
        AppLog.clear()
        logNote = "The log was cleared."
    }

    /** "Change a setting": the unfinished file of the last session is offered first, if there is one. */
    fun chooseSettings() {
        if (storage.hasDraft(carId)) askDraft = true else openOptions(useDraft = false)
    }

    /** "Install / update": releases, files from the phone, install and the way back. */
    fun chooseInstall() {
        inst.open()
        screen = Screen.INSTALL
    }

    /**
     * "Prepare headunit": today, a way to its own GitHub-adjacent SSH access (opening it needs the car's Ethernet
     * gateway, ZGW); later, checking and changing its coding as well.
     */
    fun choosePrepare() {
        // start this page clean; a tap on CHECK CODINGS/READ BACKUP/etc. sets its own feedback right away.
        feedback = ""
        feedbackError = false
        screen = Screen.PREPARE
    }

    /** "Experimental": issues and pull requests of the project. */
    fun chooseExperimental() {
        exp.open()
        screen = Screen.EXPERIMENTAL
    }

    // ---------------- POST WITH LOGS ----------------

    /** USE WIZARD: the guided way for the page [t]. */
    fun prepareWizard(t: Topic) {
        fromWizard = false
        wizard.start(t)
        screen = Screen.WIZARD
        maybeAskDongle()
    }

    /** MANUAL: the page where the files are ticked by hand, for the page [t]. */
    fun prepareManual(t: Topic) {
        wizard.askMode = false
        contribute.open(t)
        screen = Screen.CONTRIBUTE
        maybeAskDongle()
    }

    /** Leaves the manual page for the page it was started from. */
    fun backFromContribute() {
        screen = if (exp.selected != null) Screen.EXPERIMENTAL else Screen.HUB
    }

    /** CURRENT: the version that is on the head unit. On to the settings, which are read from it. */
    fun wizardCurrent() {
        wizard.toSettings()
        inst.readUnit()
    }

    /** DIFFERENT: the install page, with a way back to the wizard. */
    fun wizardDifferent() {
        fromWizard = true
        inst.open()
        screen = Screen.INSTALL
    }

    /** CHANGE in the wizard: the settings page, with a way back to the wizard. */
    fun wizardChange() {
        fromWizard = true
        chooseSettings()
    }

    /** BACK TO WIZARD (or back) on a page the wizard sent the person to: where the wizard was left, with the head unit read again. */
    fun backToWizard() {
        fromWizard = false
        if (wizard.step == WizardStep.VERSION) wizard.toSettings()
        inst.readUnit()
        screen = Screen.WIZARD
    }

    /** BACK in the wizard: a step back, and from the first one out to the page. */
    fun wizardBack() {
        if (contribute.packing) return
        if (!wizard.previous()) {
            wizard.leave()
            screen = Screen.EXPERIMENTAL
        }
    }

    fun wizardFinish() {
        wizard.leave()
        screen = Screen.EXPERIMENTAL
    }

    /** A setting named in an issue is offered for "Additional options" of the settings page. */
    fun addSettingFromPage(key: String) {
        val problem = addCustom(key, "")
        exp.settingNote = problem ?: "$key is now in Additional options (Change a setting)."
        AppLog.i("Experimental", "Setting $key offered for Additional options: ${problem ?: "added"}")
    }

    fun backToHub() {
        screen = Screen.HUB
    }

    fun answerDraft(use: Boolean) {
        askDraft = false
        if (!use) storage.clearDraft(carId)
        openOptions(use)
    }

    private fun openOptions(useDraft: Boolean) {
        // every documented setting is listed from the start; READ OPTIONS then marks the ones the car applies
        values.clear()
        orig = emptyMap()
        baseDoc = null
        rows = buildRows(null, null)
        search = ""
        onlyActive = false
        optError = false
        optFeedback = "Press READ OPTIONS to see which settings the head unit applies right now."
        if (useDraft) {
            storage.readDraft(carId)?.let {
                val draft = EnvDoc.parse(it)
                // changes are shown against the file as it was last read from the car, when we still have it
                applyDoc(draft, storage.readFromCar(carId)?.let { f -> EnvDoc.parse(f) } ?: draft)
                optFeedback = "Loaded the unfinished file from the last session. Review it and press SAVE, " +
                    "or press READ OPTIONS to start over from the vehicle."
            }
        }
        screen = Screen.OPTIONS
        syncCatalog() // this page shows whether the list of settings is up to date, so it checks every time it opens
    }

    // =====================================================================
    // Options: read, edit, save
    // =====================================================================

    private fun norm(v: String?): String? = v?.takeIf { it.isNotBlank() }

    /**
     * The list shows every documented setting, in README order. Settings that are in the file but that the README
     * does not name (a typo, or something newer than the list) follow at the end, so nothing in the file is hidden.
     */
    private fun buildRows(doc: EnvDoc?, baseline: EnvDoc?): List<Opt> {
        val known = catalog.map { it.key }.toSet()
        val mine = customKeys.filter { it !in known }.toSet()
        val extra = LinkedHashSet<String>()
        for (d in listOfNotNull(doc, baseline)) {
            for (k in d.keys()) if (k !in known && k !in mine && d.get(k) != null) extra += k
        }
        return customRows() + catalog + extra.map { fileOnlyOpt(it) }
    }

    private fun fileOnlyOpt(k: String) = Opt(
        key = k,
        group = ConfigParser.GROUP_FILE_ONLY,
        summary = "Found in your file, but the README's list of settings does not name it.",
        info = "This setting is in your nbt-aad.env but not in the README's list of settings. It may be " +
            "misspelled, or newer or older than the list. The app keeps it as it is.",
    )

    /**
     * The options added by hand, first in the list. A name the README's list has by now (a newer sync) is not
     * listed twice: the README's row takes over.
     */
    private fun customRows(): List<Opt> {
        val known = catalog.map { it.key }.toSet()
        return customKeys.filter { it !in known }.map { k ->
            Opt(
                key = k,
                group = ConfigParser.GROUP_CUSTOM,
                // no line under the name: everything in this group is added by hand, and it would only take room
                summary = "",
                info = "You added this option yourself, for a setting that the README's list does not have (for " +
                    "example from a newer or experimental build). The app does not know what it does or which " +
                    "values it takes, so the value is free text. It stays in this list on this phone, whatever " +
                    "the settings sync brings. \"Remove\" takes it out again.",
            )
        }
    }

    /** [list] with the added-by-hand rows (re)placed at the top; no other row keeps one of their names. */
    private fun withCustomRows(list: List<Opt>): List<Opt> {
        val mine = customKeys.toSet()
        return customRows() + list.filter { it.group != ConfigParser.GROUP_CUSTOM && it.key !in mine }
    }

    /**
     * "+ Add an option". Returns the problem to show in the dialog, or null when it worked. A name the list already
     * has is not added twice: that row is shown instead (and gets the value, if one was typed).
     */
    fun addCustom(name: String, value: String): String? {
        val p = when (val r = CustomOption.parse(name, value)) {
            is CustomOption.Bad -> return r.why
            is CustomOption.Parsed -> r
        }
        val existing = rows.firstOrNull { it.key == p.key }
        if (existing != null && existing.group != ConfigParser.GROUP_FILE_ONLY) {
            onlyActive = false
            search = p.key
            if (p.value.isNotEmpty()) edit(p.key, p.value)
            optError = false
            optFeedback = "${p.key} is already in the list, so it is shown here" +
                (if (p.value.isNotEmpty()) " and set to ${p.value}." else ".")
            return null
        }
        customKeys = customKeys + p.key
        customStore.save(customKeys)
        rows = withCustomRows(rows)
        if (p.value.isNotEmpty()) edit(p.key, p.value)
        search = ""
        onlyActive = false
        optError = false
        optFeedback = "Added ${p.key} to Additional options (top of the list)" +
            (if (p.value.isNotEmpty()) ", set to ${p.value}. SAVE uploads it." else ". Set its value there.")
        return null
    }

    /** Takes an added option out of the list again. One that the car's file has stays visible as a file-only row. */
    fun removeCustom(key: String) {
        customKeys = customKeys - key
        customStore.save(customKeys)
        val inFile = baseDoc?.get(key) != null || norm(orig[key]) != null
        val rest = withCustomRows(rows)
        rows = if (inFile) rest + fileOnlyOpt(key) else rest
        if (!inFile) values.remove(key)
        optError = false
        optFeedback = "Removed $key from Additional options."
    }

    private fun applyDoc(doc: EnvDoc, baseline: EnvDoc = doc) {
        baseDoc = doc
        rows = buildRows(doc, baseline)
        values.clear()
        rows.forEach { values[it.key] = doc.get(it.key) }
        orig = rows.associate { it.key to baseline.get(it.key) }
    }

    /** True when the car's file (as last read or saved) has this setting switched on. */
    fun isApplied(key: String): Boolean = norm(orig[key]) != null

    /** The rows that pass the search box and the "applied or changed only" switch. */
    fun visibleRows(): List<Opt> {
        val q = search.trim()
        return rows.filter { r ->
            (!onlyActive || isApplied(r.key) || norm(values[r.key]) != null || isChanged(r.key)) &&
                (q.isEmpty() || r.key.contains(q, ignoreCase = true) || r.summary.contains(q, ignoreCase = true) ||
                    r.info.contains(q, ignoreCase = true))
        }
    }

    /** How many settings the file would have switched on right now (the daemon only uses 15). */
    val settingCount: Int
        get() = EnvCheck.analyze(renderCurrent() ?: values.entries.joinToString("\n") { (k, v) ->
            if (norm(v) != null) "$k=$v" else ""
        }).count

    fun isChanged(key: String): Boolean = norm(values[key]) != norm(orig[key])

    val changes: List<Change>
        get() = rows.filter { isChanged(it.key) }.map { Change(it.key, norm(orig[it.key]), norm(values[it.key])) }

    fun undo(key: String) = edit(key, orig[key])

    /** READ OPTIONS: asks first when that would throw away edits. */
    fun onReadClick() {
        if (optBusy) return
        if (changes.isNotEmpty()) confirmRead = true else readOptions()
    }

    fun readDiscardingChanges() {
        confirmRead = false
        readOptions()
    }

    /** SAVE: shows what is about to change; the upload starts from the preview. */
    fun requestSave() {
        val text = renderCurrent() ?: return
        val check = EnvCheck.analyze(text)
        review = ReviewState(changes, check.errors, check.warnings, check.count)
    }

    fun confirmSave() {
        val r = review ?: return
        if (r.errors.isNotEmpty()) return
        review = null
        save()
    }

    fun readOptions() {
        if (optBusy) return
        optBusy = true
        optError = false
        optFeedback = "Connecting…"
        viewModelScope.launch(Dispatchers.IO) {
            try {
                NetworkBinder.bindToLocalNetwork(ctx)
                val found = runOnHeadUnit(
                    { detect.hu }, { rescanForHost() },
                    onRescan = { optFeedback = lookingNote() },
                    whyNot = { scanProblem },
                ) { hu -> EnvRemote.read(hu) { optFeedback = it } }
                when (val res = found) {
                    is EnvRemote.ReadResult.Found -> {
                        storage.saveFromCar(carId, res.text)
                        storage.clearDraft(carId)
                        applyDoc(EnvDoc.parse(res.text))
                        val applied = rows.count { isApplied(it.key) }
                        optFeedback = "Read ${res.text.length} bytes from ${res.dir}/${EnvRemote.FILE}: " +
                            "$applied setting${if (applied == 1) " is" else "s are"} currently applied (marked green)."
                    }
                    is EnvRemote.ReadResult.NoFile -> {
                        storage.clearDraft(carId)
                        applyDoc(EnvDoc.parse(""))
                        optFeedback = "Found ${res.dir} but no nbt-aad.env in it yet (the file is optional), so every " +
                            "setting runs at its default. SAVE will create the file."
                    }
                    is EnvRemote.ReadResult.NoFolder -> {
                        optError = true
                        optFeedback = "The NBT-AAD folder (/opt/aa, or NBTApps' /var/opt/nbtapp) was not found on the head unit. Is NBT-AAD installed?\n" +
                            "What the SSH shell can see:\n" + res.detail
                    }
                }
            } catch (e: Exception) {
                AppLog.e("Options", "Reading nbt-aad.env failed", e)
                optError = true
                optFeedback = "Read failed: ${friendlyError(e)}"
            } finally {
                optBusy = false
            }
        }
    }

    fun edit(key: String, value: String?) {
        values[key] = value
        renderCurrent()?.let { storage.saveDraft(carId, it) } // keep the unfinished session on the phone
    }

    /** The file as it would be uploaded now: original lines kept, only changed keys touched. */
    private fun renderCurrent(): String? {
        val doc = baseDoc?.copyDoc() ?: return null
        for ((k, v) in values) {
            val nv = v?.takeIf { it.isNotBlank() }
            if (doc.get(k) != nv) doc.set(k, nv)
        }
        return doc.render()
    }

    private fun addLine(l: DialogLine) {
        AppLog.i("Save", l.text)
        saveDialog = saveDialog?.let { it.copy(lines = it.lines + l) }
    }

    private fun setStatus(s: SaveStatus, connectionOk: Boolean = false) {
        AppLog.i("Save", "Status: $s")
        saveDialog = saveDialog?.copy(status = s, connectionOk = connectionOk)
    }

    fun save() {
        val text = renderCurrent() ?: return
        storage.saveDraft(carId, text)
        saveDialog = SaveDialogState(listOf(DialogLine("Preparing ${text.length} bytes…")), SaveStatus.RUNNING)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                NetworkBinder.bindToLocalNetwork(ctx)
                runOnHeadUnit(
                    { detect.hu }, { rescanForHost() },
                    onRescan = { addLine(DialogLine(lookingNote())) },
                    whyNot = { scanProblem },
                ) { hu -> EnvRemote.write(hu, text.toByteArray(Charsets.UTF_8)) { addLine(DialogLine(it)) } }
                storage.saveUploaded(carId, text)
                storage.clearDraft(carId)
                storage.saveFromCar(carId, text)
                applyDoc(EnvDoc.parse(text)) // what is on the car now becomes the new baseline, nothing is "changed"
                addLine(DialogLine("File uploaded and verified.", LineKind.OK))
                setStatus(SaveStatus.OK)
            } catch (e: Exception) {
                AppLog.e("Save", "Saving nbt-aad.env failed", e)
                addLine(DialogLine(friendlyError(e), LineKind.ERROR))
                setStatus(SaveStatus.FAIL)
            }
        }
    }

    /** Same detection as the first screen. */
    fun checkConnection() {
        val d = saveDialog ?: return
        if (d.status == SaveStatus.RUNNING) return
        saveDialog = d.copy(
            status = SaveStatus.RUNNING,
            connectionOk = false,
            lines = d.lines + DialogLine("Checking the connection…"),
        )
        viewModelScope.launch(Dispatchers.IO) {
            try {
                NetworkBinder.bindToLocalNetwork(ctx)
                val r = detectCar { addLine(DialogLine(it)) }
                applyResult(r)
                if ((r.zgwIp != null || r.viaCarWifi) && r.huIp != null && r.sshOpen) {
                    addLine(DialogLine("Connection OK (head unit ${r.huIp}). Press Retry to upload again.", LineKind.OK))
                    setStatus(SaveStatus.FAIL, connectionOk = true)
                } else {
                    addLine(
                        DialogLine(
                            r.problem ?: "SSH on the head unit is still not reachable. " +
                                "Go back to the first screen to reset the head unit.",
                            LineKind.ERROR,
                        )
                    )
                    setStatus(SaveStatus.FAIL)
                }
            } catch (e: Exception) {
                AppLog.e("Save", "Checking the connection failed", e)
                addLine(DialogLine(friendlyError(e), LineKind.ERROR))
                setStatus(SaveStatus.FAIL)
            }
        }
    }

    fun closeSaveDialog() {
        saveDialog = null
        resetNote = null
    }
}
