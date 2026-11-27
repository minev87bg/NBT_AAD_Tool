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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The "NBT Android Auto (original NBT)" screen: JayJay3071's package for the OLDER, non-Evo BMW NBT - a different
 * head unit generation from the rest of this app, kept deliberately separate from [HeadUnit]/[InstallController]
 * (see net/JjAa.kt for why). Fetches that project's own GitHub releases, reads whichever official release zip the
 * person picks - from there, or one already on the phone - and pushes it to the head unit over SSH. This app never
 * bundles or keeps a copy of that payload itself; only [payload] (in memory, for the one install this session) does.
 */
class JjAaController(
    private val ctx: Context,
    private val scope: CoroutineScope,
    /** The address to install to: the head unit this app already found, when there is one. */
    private val host: () -> String?,
) {
    var releases by mutableStateOf<List<ReleaseInfo>>(emptyList()); private set
    var loading by mutableStateOf(false); private set
    var loadMessage by mutableStateOf(""); private set
    var checkedAt by mutableStateOf(0L); private set

    /** The payload read from a release or a picked file, ready to install - null until one checks out. */
    var payload by mutableStateOf<JjAa.Payload?>(null); private set
    var payloadSource by mutableStateOf(""); private set
    var payloadError by mutableStateOf<String?>(null); private set
    var payloadBusy by mutableStateOf(false); private set

    /** The original NBT's root password. Defaults to the same one this app already uses for NBT-AAD/EVO - the
     * same QNX lineage is likely to share it - but it is editable, since this is a different head unit and that
     * is not confirmed. Never stored. */
    var password by mutableStateOf(SshConfig.PASSWORD)
    var hostOverride by mutableStateOf("")

    var busy by mutableStateOf(false); private set
    var log by mutableStateOf<List<String>>(emptyList()); private set
    var result by mutableStateOf<String?>(null); private set
    var resultOk by mutableStateOf(true); private set
    var showCredits by mutableStateOf(false)

    val effectiveHost: String get() = hostOverride.trim().ifEmpty { host() ?: JjAa.DEFAULT_HOST }

    fun visit() {
        if (checkedAt == 0L && !loading) refresh()
    }

    fun refresh() {
        if (loading) return
        loading = true
        loadMessage = ""
        scope.launch(Dispatchers.IO) {
            try {
                releases = JjAa.releases(ctx)
                AppLog.i("JjAa", "${releases.size} release(s) from ${JjAa.PROJECT_URL}")
            } catch (e: Exception) {
                AppLog.w("JjAa", "Could not read releases: ${e.javaClass.simpleName}: ${e.message}")
                loadMessage = Online.describe(e)
            }
            checkedAt = System.currentTimeMillis()
            loading = false
        }
    }

    private fun setPayload(bytes: ByteArray, source: String) {
        payload = JjAa.parsePayloadZip(bytes)
        payloadSource = source
    }

    /** Downloads one release's payload zip and reads it. Never kept after the session ends. */
    fun useRelease(release: ReleaseInfo) {
        val asset = JjAa.payloadAsset(release)
        if (asset == null) {
            payloadError = "${release.tag} has no cross-platform (\"...-Python-....zip\") file this app can read."
            return
        }
        payload = null
        payloadError = null
        payloadBusy = true
        scope.launch(Dispatchers.IO) {
            try {
                val bytes = ReleasesApi.download(ctx, asset) { }
                setPayload(bytes, "${release.tag} (${asset.name})")
                AppLog.i("JjAa", "Read payload from ${asset.name}: ${payload?.manifest?.version}")
            } catch (e: Exception) {
                AppLog.w("JjAa", "Reading ${asset.name} failed: ${e.javaClass.simpleName}: ${e.message}")
                payloadError = "Could not get or read ${asset.name}: ${Online.describe(e)}"
            } finally {
                payloadBusy = false
            }
        }
    }

    /** Reads a release zip the person already has on the phone. */
    fun usePicked(uri: Uri) {
        payload = null
        payloadError = null
        payloadBusy = true
        scope.launch(Dispatchers.IO) {
            try {
                val f = ReleasesApi.readPicked(ctx, listOf(uri)).first()
                setPayload(f.bytes, f.name)
                AppLog.i("JjAa", "Read payload from picked file ${f.name}: ${payload?.manifest?.version}")
            } catch (e: Exception) {
                AppLog.w("JjAa", "Reading the picked file failed: ${e.javaClass.simpleName}: ${e.message}")
                payloadError = "Could not read that file: ${Online.describe(e)}"
            } finally {
                payloadBusy = false
            }
        }
    }

    fun clearPayload() {
        payload = null
        payloadSource = ""
        payloadError = null
    }

    private fun findGateway(): String? = try {
        CarDetector.discoverZgw(CarDetector.localNetworks())?.ip
    } catch (e: Exception) {
        AppLog.w("JjAa", "Gateway search failed: ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    fun install() {
        val pkg = payload ?: return
        val h = effectiveHost
        busy = true
        log = emptyList()
        result = null
        scope.launch(Dispatchers.IO) {
            try {
                val outcome = JjAa.install(h, password, pkg, rep = { line -> log = log + line }, findGateway = ::findGateway)
                result = "Installed ${outcome.version} (${outcome.filesWritten} files)." +
                    (outcome.gatewayNote?.let { "\n$it" } ?: "")
                resultOk = true
            } catch (e: Exception) {
                AppLog.w("JjAa", "Install failed: ${e.javaClass.simpleName}: ${e.message}")
                result = friendlyError(e)
                resultOk = false
            } finally {
                busy = false
            }
        }
    }

    fun uninstall() {
        val h = effectiveHost
        busy = true
        log = emptyList()
        result = null
        scope.launch(Dispatchers.IO) {
            try {
                val notRemoved = JjAa.uninstall(h, password, rep = { line -> log = log + line })
                result = if (notRemoved.isEmpty()) "Removed." else "Could not remove: ${notRemoved.joinToString(", ")}"
                resultOk = notRemoved.isEmpty()
            } catch (e: Exception) {
                AppLog.w("JjAa", "Uninstall failed: ${e.javaClass.simpleName}: ${e.message}")
                result = friendlyError(e)
                resultOk = false
            } finally {
                busy = false
            }
        }
    }
}
