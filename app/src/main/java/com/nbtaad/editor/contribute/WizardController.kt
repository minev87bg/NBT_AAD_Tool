package com.nbtaad.editor.contribute

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nbtaad.editor.experimental.Topic
import com.nbtaad.editor.log.AppLog
import com.nbtaad.editor.net.CarDetector
import com.nbtaad.editor.net.NetworkBinder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The state of the log wizard, which takes the person from "I want to test this" to the logs on the issue's page:
 * which version, which settings, the test itself, the way back to the head unit's WiFi (watched live), the logs.
 * The pages it needs (Install, settings) are the app's own; the wizard only remembers where it was.
 */
class WizardController(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val host: () -> String?,
    private val zgw: () -> String?,
    /** A head unit address and whether SSH answers there: the first screen's state is brought up to date. */
    private val onHost: (String, Boolean) -> Unit,
    private val contribute: ContributeController,
) {
    /** The page the logs are for. */
    var topic by mutableStateOf<Topic?>(null); private set
    var step by mutableStateOf(WizardStep.VERSION); private set

    /** The question "wizard or manual?" that POST WITH LOGS asks. */
    var askMode by mutableStateOf(false)

    /** The window that says why Android Auto must keep running, with the tick. */
    var askWhy by mutableStateOf(false); private set
    var understood by mutableStateOf(false)

    /** Does SSH answer on the head unit now? null: not looked yet. */
    var sshUp by mutableStateOf<Boolean?>(null); private set
    var waited by mutableStateOf(0); private set
    var gaveUp by mutableStateOf(false); private set

    private var poll: Job? = null

    fun start(t: Topic) {
        stopPolling()
        topic = t
        step = WizardStep.VERSION
        askMode = false
        askWhy = false
        understood = false
        sshUp = null
        gaveUp = false
        contribute.chooseTarget(t)
        AppLog.i("Wizard", "Started for #${t.number}")
    }

    fun toSettings() {
        step = WizardStep.SETTINGS
    }

    fun toTest() {
        step = WizardStep.TEST
    }

    fun askDone() {
        understood = false
        askWhy = true
    }

    fun cancelWhy() {
        askWhy = false
    }

    /** The tick was given: the person goes to the dongle's WiFi, and the app waits for the head unit. */
    fun confirmWhy() {
        askWhy = false
        step = WizardStep.RETURN
        startPolling()
    }

    /** BACK: one step back; false on the first step, where the caller leaves the wizard. */
    fun previous(): Boolean {
        val before = step.before() ?: return false
        stopPolling()
        step = before
        return true
    }

    fun leave() {
        stopPolling()
        askWhy = false
    }

    fun retryWaiting() {
        startPolling()
    }

    private fun stopPolling() {
        poll?.cancel()
        poll = null
    }

    /**
     * Looks at the head unit's SSH port about once a second and, now and then, for the head unit's address on the
     * phone's networks (the dongle's network may hand out another address than before). Two answers in a row move the
     * wizard on to the logs. For a person on the cable that is at once.
     */
    private fun startPolling() {
        stopPolling()
        sshUp = null
        waited = 0
        gaveUp = false
        val began = System.currentTimeMillis()
        poll = scope.launch(Dispatchers.IO) {
            var tick = 0
            var greens = 0
            while (isActive) {
                try {
                    NetworkBinder.bindToLocalNetwork(ctx)
                    var ip = host()
                    var open = ip != null && CarDetector.sshOpen(ip)
                    if (!open && (ip == null || tick % 5 == 0)) {
                        val nets = CarDetector.localNetworks()
                        if (nets.isNotEmpty()) {
                            val (hu, o) = CarDetector.findHeadUnit(nets, zgw())
                            if (hu != null) {
                                ip = hu
                                open = o
                                onHost(hu, o)
                            }
                        }
                    }
                    sshUp = open
                    greens = if (open) greens + 1 else 0
                    if (greens >= 2 && isActive) {
                        AppLog.i("Wizard", "SSH answers on $ip: on to the logs")
                        step = WizardStep.LOGS
                        contribute.look()
                        return@launch
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.w("Wizard", "Waiting for the head unit: ${e.javaClass.simpleName}: ${e.message}")
                }
                waited = ((System.currentTimeMillis() - began) / 1000).toInt()
                if (waited > MAX_WAIT_SECONDS) {
                    gaveUp = true
                    AppLog.i("Wizard", "Gave up waiting for the head unit after $waited s")
                    return@launch
                }
                delay(1000)
                tick++
            }
        }
    }

    companion object {
        /** After this long the app stops asking and offers to try again (15 minutes). */
        const val MAX_WAIT_SECONDS = 900
    }
}
