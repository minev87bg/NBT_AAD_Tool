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
import com.nbtaad.editor.addon.AddOns
import com.nbtaad.editor.install.HuRestart
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
import com.nbtaad.editor.net.NetworkBinder
import com.nbtaad.editor.net.PortProbe
import com.nbtaad.editor.net.PortState
import com.nbtaad.editor.net.Reach
import com.nbtaad.editor.net.Online
import com.nbtaad.editor.net.friendlyError
import com.nbtaad.editor.net.runOnHeadUnit
import com.nbtaad.editor.update.Check
import com.nbtaad.editor.update.InstallEvents
import com.nbtaad.editor.update.UpdateOffer
import com.nbtaad.editor.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

enum class Screen { DETECT, HUB, OPTIONS, INSTALL, EXPERIMENTAL, CONTRIBUTE, WIZARD, TILE, MENULOOK, ADDONS }
enum class UpdatePhase { IDLE, CHECKING, CURRENT, AVAILABLE, DOWNLOADING, FAILED }
enum class SaveStatus { RUNNING, OK, FAIL }
enum class LineKind { INFO, OK, ERROR }

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

    private var screenState by mutableStateOf(Screen.DETECT)
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
    )

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

    /** What the Add-ons page says about the zip that was picked (empty when all is well). */
    var addOnMessage by mutableStateOf("")

    /** The add-on whose page is open on the Add-ons screen (its catalog id); null: the list. */
    var addOnOpen by mutableStateOf<String?>(null)
    var addOnBusy by mutableStateOf(false); private set

    /**
     * Keeps [file] on the phone (so it is there without a car and after the app was closed) and opens the page of the
     * add-on it is; false when it is neither the tile nor the menu look.
     */
    private fun openAddOnFile(file: SourceFile, origin: String): Boolean {
        if (AddOns.tileKitOf(file.name, file.bytes) == null && AddOns.menuLookKitOf(file.name, file.bytes) == null) return false
        val kept = inst.keepForLater(file, origin, null, null)
        return openTileWith(file, origin, null, kept?.id)
    }

    /** "A zip from this phone" on the Add-ons page. */
    fun pickAddOn(uris: List<android.net.Uri>) {
        if (uris.isEmpty() || addOnBusy) return
        addOnBusy = true
        addOnMessage = "Reading the file…"
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val files = com.nbtaad.editor.install.ReleasesApi.readPicked(ctx, uris)
                val f = files.firstOrNull()
                if (f != null && openAddOnFile(f, "a file from this phone")) {
                    addOnMessage = ""
                } else {
                    addOnMessage = (f?.name?.let { "$it is" } ?: "This is") + " not the Android Auto tile or the menu look. " +
                        "The tile zip holds aa-tile, aa-tile-launcher.sh and hook.txt; the menu look zip holds aa-look. " +
                        "A build of NBT-AAD goes to Install / update."
                }
            } catch (e: Exception) {
                AppLog.e("AddOns", "Reading the picked file failed", e)
                addOnMessage = "Could not read the file: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                addOnBusy = false
            }
        }
    }

    fun chooseAddOns() {
        // the list needs the files kept on the phone and the releases of the add-ons, not a look for the car: that is done
        // when an add-on's own page opens (and a tap on an add-on must not wait for it)
        inst.loadShelf()
        addOnMessage = ""
        addOnOpen = null
        addOns.visit()
        screen = Screen.ADDONS
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

    /** The manual way of PREPARE LOGS: the daemon's logs from the head unit, packed into a zip for an issue. */
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

    // ---------------- screen 1: detect ----------------
    var busy by mutableStateOf(false)
    var resetting by mutableStateOf(false)
    private var feedbackState by mutableStateOf("")

    /** The line under DETECT CAR; whatever it says is also written to the log. */
    var feedback: String
        get() = feedbackState
        set(v) {
            feedbackState = v
            AppLog.i("Detect", v)
        }
    var feedbackError by mutableStateOf(false)
    var detect by mutableStateOf(DetectState())
    var confirmReset by mutableStateOf(false)

    /**
     * The progress of a reset that was started from a result window (install, go back, save): null while none is shown.
     * The windows clear it when they close, and the reset stops writing to it then.
     */
    var resetNote by mutableStateOf<String?>(null)
    var askDraft by mutableStateOf(false)
    var manualDialog by mutableStateOf(false)
    private var pollJob: Job? = null

    // ---------------- the car's network and the dongle (this session only) ----------------

    /** The WiFi the car answered on at the last good DETECT CAR; null before one, and while DETECT CAR looks again. */
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
        syncCatalog()
    }

    // ---------------- updates of this app (from the project's GitHub releases) ----------------
    var updatePhase by mutableStateOf(UpdatePhase.IDLE)
    var updateMessage by mutableStateOf("")
    var updateOffer by mutableStateOf<UpdateOffer?>(null)
    var updateProgress by mutableStateOf(0f)

    // after the properties above: initializers and init blocks run in the order they are written
    init {
        checkUpdate()
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

    /** The "Check for updates" button: the app and the list of settings, now (they are also checked at every start). */
    fun checkForUpdates() {
        syncCatalog()
        checkUpdate()
    }

    val checkingForUpdates: Boolean
        get() = syncBusy || updatePhase == UpdatePhase.CHECKING || updatePhase == UpdatePhase.DOWNLOADING

    /** Looks for a newer release on GitHub. Needs internet; with none it stays quiet (the settings sync says so). */
    fun checkUpdate() {
        if (updatePhase == UpdatePhase.CHECKING || updatePhase == UpdatePhase.DOWNLOADING) return
        updatePhase = UpdatePhase.CHECKING
        updateMessage = "Checking for a new version…"
        updateOffer = null
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

    /** The detection with what Android says about the phone's networks (the routers: on the car's own WiFi that is the head unit). */
    private fun detectCar(progress: (String) -> Unit): DetectResult {
        val hints = try {
            PortProbe.netFacts(ctx).let { it.gateways + it.dhcp + it.dns }
        } catch (_: Exception) {
            emptyList()
        }
        return CarDetector.detect(hints, progress)
    }

    fun onDetect() {
        if (busy) return
        pollJob?.cancel()
        resetting = false
        busy = true
        feedbackError = false
        feedback = "Starting…"
        detect = DetectState()
        carNet = null // the network is marked again by the new look, if the car answers
        viewModelScope.launch(Dispatchers.IO) {
            try {
                NetworkBinder.bindToLocalNetwork(ctx)
                val r = detectCar { feedback = it }
                applyResult(r)
            } catch (e: Exception) {
                AppLog.e("Detect", "Detection failed", e)
                feedbackError = true
                feedback = "Detection failed: ${friendlyError(e)}"
            } finally {
                busy = false
            }
        }
    }

    private fun applyResult(r: DetectResult) {
        AppLog.i("Detect", "Applied: gateway ${r.zgwIp}, VIN ${r.vin}, head unit ${r.huIp}, SSH ${r.sshOpen}, problem ${r.problem}")
        detect = DetectState(r.zgwIp != null || r.viaCarWifi, r.zgwIp, r.huIp, r.vin, r.sshOpen, r.viaCarWifi, r.pfBlocked)
        feedbackError = false
        feedback = when {
            r.pfBlocked -> {
                feedbackError = true
                r.problem ?: HeadUnitLight.PF_PROBLEM
            }
            r.viaCarWifi && r.sshOpen -> "Connected through the car's Android Auto WiFi (no car gateway needed). SSH is open. Press NEXT."
            r.viaCarWifi -> {
                feedbackError = true
                r.problem ?: "The head unit answers over the car's WiFi, but SSH did not work. Press DETECT CAR again."
            }
            r.zgwIp == null -> {
                feedbackError = true
                r.problem ?: "No car detected."
            }
            r.huIp != null && r.sshOpen -> "Car found. SSH is open. Press NEXT."
            r.huIp != null -> "Car found, but SSH (port 22) is closed. Use the reset button next to the red dot."
            else -> "Car found, but no head unit with SSH was found. Use the reset button or set the IPs manually."
        }
        if (r.zgwIp != null && r.huIp != null && r.sshOpen) markCarNetwork(r.huIp)
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
        AppLog.i("Wifi", "The car answers on ${net.name} ${net.ip}" + (ssid?.let { " (WiFi \"$it\")" } ?: " (the WiFi name is not known)"))
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
                    h != null && ssh -> "SSH is open on $h. Press NEXT."
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
     * Reset the head unit through the ZGW (no SSH), then keep checking until SSH opens. The car is scanned again first,
     * as DETECT CAR does, so the reset goes to the gateway as it is now (the addresses of an earlier scan may be old);
     * only when the scan finds nothing, the address that was known is tried. [fromWindow]: it was started from a result
     * window, which shows the progress as [resetNote] instead of the feedback line of the first screen.
     */
    fun resetHeadUnit(fromWindow: Boolean = false) {
        confirmReset = false
        pollJob?.cancel()
        resetting = true
        feedbackError = false
        resetNote = if (fromWindow) "Looking for the car…" else null
        fun note(text: String) { if (resetNote != null) resetNote = text }
        feedback = "Looking for the car…"
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
                val shellHost = scan.huIp
                if (gw == null && scan.sshOpen && shellHost != null) {
                    // the car's own WiFi has no gateway to send to, but the head unit's shell answers: it is asked to restart
                    restartThroughShell(shellHost, ::note)
                    return@launch
                }
                if (gw == null) {
                    val text = scan.problem ?: "No car detected."
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
                        feedback = "Head unit is back and SSH is open. Press NEXT."
                        note("The head unit is back.")
                        return@launch
                    }
                }
                feedbackError = true
                feedback = "SSH did not open within 4 minutes. Check the head unit, then press DETECT CAR."
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

    /**
     * Asks the head unit's shell to restart (the gateway is out of reach on the car's Android Auto WiFi) and follows it: the
     * SSH answer has to go away, which shows that it restarted, and come back, which needs Android Auto to be connected again.
     */
    private suspend fun CoroutineScope.restartThroughShell(host: String, note: (String) -> Unit) {
        feedback = "Asking the head unit to restart…"
        note("Asking the head unit to restart…")
        val r = HuRestart.ask(host)
        if (r.error != null) {
            feedbackError = true
            feedback = r.error
            note(r.error)
            return
        }
        if (r.state["kind"] == "hmi") {
            // only the iDrive software restarts: the head unit keeps running, so SSH stays up and its start files do not run again
            val text = "The head unit's iDrive software was asked to restart (not the whole head unit). " +
                "A tile that was just installed starts with a full restart: hold the power button for 30 seconds."
            AppLog.i("Reset", text)
            feedback = text
            note(text)
            return
        }
        detect = detect.copy(ssh = false)
        var waited = 0
        var wentDown = false
        val downBy = System.currentTimeMillis() + 90_000
        while (isActive && !wentDown && System.currentTimeMillis() < downBy) {
            feedback = "Waiting for the head unit to shut down… (${waited}s)"
            note("The head unit is restarting… (${waited}s)")
            delay(3000)
            waited += 3
            wentDown = HeadUnitLight.see(host).ssh != PortState.OPEN
        }
        if (!wentDown) {
            val text = "The head unit was asked to restart, but it kept answering. Hold the power button for 30 seconds instead."
            AppLog.w("Reset", text)
            feedbackError = true
            feedback = text
            note(text)
            return
        }
        AppLog.i("Reset", "The head unit's SSH went away after ${waited}s: it is restarting")
        val backBy = System.currentTimeMillis() + 300_000
        while (isActive && System.currentTimeMillis() < backBy) {
            feedback = "The head unit is restarting… (${waited}s)"
            note("The head unit is restarting… (${waited}s)")
            delay(5000)
            waited += 5
            val seen = HeadUnitLight.see(host)
            if (HeadUnitLight.judge(listOf(seen), true).green) {
                detect = detect.copy(hu = host, ssh = true)
                feedback = "Head unit is back and SSH is open. Press NEXT."
                note("The head unit is back.")
                return
            }
        }
        val text = "The head unit restarted. SSH answers again once Android Auto is connected to the phone again: start it, then press DETECT CAR."
        AppLog.i("Reset", text)
        feedback = text
        note(text)
    }

    /** A reset can be sent: through the car's gateway, or, on the car's Android Auto WiFi where there is none, asked of the head unit's shell. */
    val canResetHeadUnit: Boolean get() = (detect.zgw != null && detect.vin != null) || (detect.hu != null && detect.ssh)

    /** Why the last look for the car, started by an action that needed it, found no head unit (null: it found one). */
    @Volatile
    private var scanProblem: String? = null

    /**
     * Looks for the car (what DETECT CAR does) for an action that needs the head unit and has no address, or whose
     * address does not answer; returns the head unit's address when SSH answers there.
     */
    private fun rescanForHost(): String? {
        NetworkBinder.bindToLocalNetwork(ctx)
        AppLog.i("Detect", if (detect.hu == null) "Looking for the car (no car is known yet)" else "Looking for the car again (no SSH connection)")
        val r = detectCar { AppLog.i("Detect", it) }
        applyResult(r)
        val host = if (r.sshOpen) r.huIp else null
        scanProblem = if (host != null) null else feedback
        return host
    }

    /** What a screen says while an action looks for the car: the first look when no car is known, else a new look. */
    private fun lookingNote(): String =
        if (detect.hu == null) "No car known yet. Looking for the car…" else "No connection. Looking for the car again…"

    // =====================================================================
    // Navigation between the screens
    // =====================================================================

    /** NEXT on the first screen: the page where the person chooses what to do. */
    fun onNext() {
        screen = Screen.HUB
    }

    /**
     * SKIP on the first screen: on to the rest of the app without a car (to look at the releases, or to try the app away
     * from the car). What needs the head unit says so when it is used.
     */
    fun onSkip() {
        AppLog.i("Detect", "Skipped: on to the choice page with gateway ${detect.zgw}, head unit ${detect.hu}")
        screen = Screen.HUB
    }

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

    /** "Experimental": issues and pull requests of the project. */
    fun chooseExperimental() {
        exp.open()
        screen = Screen.EXPERIMENTAL
    }

    // ---------------- PREPARE LOGS ----------------

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
                    "or press READ OPTIONS to start over from the car."
            }
        }
        screen = Screen.OPTIONS
    }

    fun backToDetect() {
        screen = Screen.DETECT
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
