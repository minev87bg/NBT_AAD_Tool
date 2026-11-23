package com.nbtaad.editor.net

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nbtaad.editor.log.AppLog

/**
 * The network the vehicle answered on at the last good look for it. Kept for the session only (the app's memory, gone
 * when it closes). [ssid] is null when the phone would not tell the name (Android hides it without the Location
 * permission).
 */
data class CarNet(val ifName: String, val ip: String, val subnet: String, val ssid: String?)

/** What the person said about the dongle: its WiFi name and password ("" when it has none). In memory only. */
data class DongleWifi(val ssid: String, val password: String)

/** Small pure helpers, tested without a phone. */
object CarNetLogic {
    /** "192.168.0.13" -> "192.168.0": the /24 the phone is on, which is how the app tells that it is still on the vehicle's network. */
    fun subnetOf(ip: String): String = ip.substringBeforeLast('.', ip)

    /** Android's SSID text ("\"Name\"", "<unknown ssid>", "0x") -> the name, or null when it is not a name. */
    fun cleanSsid(raw: String?): String? {
        val t = raw?.trim()?.removeSurrounding("\"")?.trim() ?: return null
        if (t.isEmpty() || t == "<unknown ssid>" || t == "0x") return null
        return t
    }

    /** Whether an address is on the same /24 as [subnet] ("192.168.0"). */
    fun onSubnet(ip: String, subnet: String): Boolean = subnet.isNotEmpty() && subnetOf(ip) == subnet
}

object WifiNames {
    fun hasLocationPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** The name of the WiFi the phone is on now, or null (no WiFi, no Location permission, Location switched off). */
    @Suppress("DEPRECATION")
    fun currentSsid(ctx: Context): String? = try {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        CarNetLogic.cleanSsid(wm.connectionInfo?.ssid)
    } catch (_: Exception) {
        null
    }
}

object BluetoothState {
    /** true: Bluetooth is on, false: it is off (or the phone has none), null: the phone would not say. */
    fun isOn(ctx: Context): Boolean? = try {
        val bm = ctx.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bm?.adapter
        if (adapter == null) false else adapter.isEnabled
    } catch (_: Exception) {
        null
    }
}

enum class WifiJoin { IDLE, WAITING, CONNECTED, FAILED }

/**
 * Brings the phone back to the dongle's WiFi with the name and password the person gave. Since Android 10 an app cannot
 * switch the phone's WiFi by itself; it can ask for one network (WifiNetworkSpecifier), Android asks the person to allow
 * it, and the connection is the app's own for as long as the request is kept. The app's traffic is then tied to it.
 */
class CarWifi(private val ctx: Context) {
    var join by mutableStateOf(WifiJoin.IDLE); private set
    var message by mutableStateOf(""); private set

    private var callback: ConnectivityManager.NetworkCallback? = null

    fun connect(wifi: DongleWifi) {
        release()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            join = WifiJoin.FAILED
            message = "This phone's Android is too old for the app to join a WiFi by itself. Pick it in the WiFi list."
            return
        }
        join = WifiJoin.WAITING
        message = "Connecting to ${wifi.ssid}… Android may ask you to allow it."
        AppLog.i("Wifi", "Asking Android for the WiFi \"${wifi.ssid}\" (password ${if (wifi.password.isEmpty()) "none" else "given"})")
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val builder = WifiNetworkSpecifier.Builder().setSsid(wifi.ssid)
            if (wifi.password.isNotEmpty()) builder.setWpa2Passphrase(wifi.password)
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(builder.build())
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    AppLog.i("Wifi", "Connected to \"${wifi.ssid}\"")
                    NetworkBinder.hold(ctx, network)
                    join = WifiJoin.CONNECTED
                    message = "Connected to ${wifi.ssid}."
                }

                override fun onUnavailable() {
                    AppLog.w("Wifi", "\"${wifi.ssid}\" was not reached (declined, wrong password, or out of reach)")
                    join = WifiJoin.FAILED
                    message = "Could not connect to ${wifi.ssid}. Is the dongle on, is the password right, and did you allow it?"
                }

                override fun onLost(network: Network) {
                    AppLog.w("Wifi", "Lost \"${wifi.ssid}\"")
                    NetworkBinder.drop(ctx, network)
                    if (join == WifiJoin.CONNECTED) {
                        join = WifiJoin.FAILED
                        message = "The connection to ${wifi.ssid} was lost."
                    }
                }
            }
            callback = cb
            cm.requestNetwork(request, cb, 60_000)
        } catch (e: Exception) {
            AppLog.e("Wifi", "Asking for the WiFi failed", e)
            callback = null
            join = WifiJoin.FAILED
            message = "The phone would not start the connection: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** Lets go of the request (and with it the connection). */
    fun release() {
        val cb = callback
        callback = null
        if (cb != null) {
            try {
                (ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(cb)
            } catch (_: Exception) {
            }
        }
        NetworkBinder.dropAll(ctx)
        join = WifiJoin.IDLE
        message = ""
    }
}
