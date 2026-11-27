package com.nbtaad.editor.mgu

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nbtaad.editor.data.MguKeyStore
import com.nbtaad.editor.log.AppLog
import com.nbtaad.editor.net.CarDetector
import com.nbtaad.editor.net.MguConfig
import com.nbtaad.editor.net.MguDetector
import com.nbtaad.editor.net.NetworkBinder
import com.nbtaad.editor.net.SshSession
import com.nbtaad.editor.net.friendlyError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The state behind the MGU (iDrive 7/8) hub's "Head unit status" card - phase one of this app's MGU support.
 * Later phases add browsing/installing apps through the Store's own deploy tool (mgu-deploy.sh); this phase only
 * proves the connection works and reports what is already on the unit, which is the thing every later phase
 * needs anyway.
 *
 * Root SSH on MGU is key-based, not this app's usual fixed NBT password, and this app does not and will not
 * enable it itself (see the "Root SSH" note the hub screen shows) - it only uses a key the person already has
 * from running that separate tool themselves.
 */
class McController(
    private val ctx: Context,
    private val scope: CoroutineScope,
    /** The head unit this app already found over the platform check, when there is one. */
    private val host: () -> String?,
) {
    private val keyStore = MguKeyStore(ctx)

    var keyPem by mutableStateOf(keyStore.readKey() ?: "")
    var hostOverride by mutableStateOf(keyStore.readHostOverride())
    var keySaved by mutableStateOf(keyStore.readKey() != null); private set

    var busy by mutableStateOf(false); private set
    var message by mutableStateOf(""); private set
    var error by mutableStateOf(false); private set

    fun saveKey() {
        val pem = keyPem.trim()
        keyStore.writeHostOverride(hostOverride)
        if (pem.isEmpty()) {
            keyStore.clearKey()
            keySaved = false
        } else {
            keyStore.writeKey(pem)
            keySaved = true
        }
    }

    fun checkStatus() {
        if (busy) return
        val pem = keyPem.trim()
        if (pem.isEmpty()) {
            message = "Paste the car's root SSH key first (see \"Root SSH\" below), then Save."
            error = true
            return
        }
        busy = true
        error = false
        message = "Looking for the head unit…"
        scope.launch(Dispatchers.IO) {
            try {
                NetworkBinder.bindToLocalNetwork(ctx)
                val target = hostOverride.trim().ifEmpty {
                    host() ?: MguDetector.findHost(CarDetector.localNetworks())
                }
                if (target == null) {
                    message = "No MGU head unit found. Join the car's own WiFi, connect ENET, or enter its address below."
                    error = true
                    return@launch
                }
                message = "Logging in to $target…"
                SshSession(target, user = MguConfig.USER, privateKey = pem.toByteArray(Charsets.UTF_8)).use { ssh ->
                    val probe = ssh.exec("test -x ${MguConfig.DEPLOY_SH} && echo yes || echo no")
                    message = if (probe.text.trim() == "yes") {
                        val r = ssh.exec("${MguConfig.DEPLOY_SH} status --read-only", timeoutMs = 15000)
                        error = r.exit != 0
                        r.text.trim().ifEmpty { "Connected, but the deploy tool gave no output." }
                    } else {
                        "Logged in to $target as root - the connection works. " +
                            "The Store's deploy tool isn't installed on this head unit yet (next up)."
                    }
                }
            } catch (e: Exception) {
                AppLog.w("Mgu", "Status check failed: ${e.javaClass.simpleName}: ${e.message}")
                message = friendlyError(e)
                error = true
            } finally {
                busy = false
            }
        }
    }
}
