package com.nbtaad.editor

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nbtaad.editor.net.BluetoothState
import com.nbtaad.editor.net.WifiJoin
import com.nbtaad.editor.net.WifiNames
import com.nbtaad.editor.net.WifiPanel
import kotlinx.coroutines.delay

// =========================================================================
// The dongle's WiFi (name and password) and CONNECT AGAIN
// =========================================================================

/**
 * Asked once when a page that reads the logs is opened after a good look for the vehicle: is the WiFi it answered
 * on the dongle's? With its name and password the app can bring the phone back to it (see [ConnectAgainDialog]).
 */
@Composable
internal fun DongleDialog(vm: MainViewModel) {
    val ctx = LocalContext.current
    var ssid by remember { mutableStateOf(vm.carNet?.ssid ?: "") }
    var password by remember { mutableStateOf("") }
    var showInfo by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }

    fun readName() {
        val name = vm.readCarSsid()
        if (name != null) {
            ssid = name
            note = ""
        } else {
            note = "The phone would not tell the name. Switch Location on, or type it."
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) readName() else note = "Without the permission, type the name."
    }

    AlertDialog(
        onDismissRequest = { vm.declineDongle() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Is this your WiFi dongle?", fontSize = 18.sp, modifier = Modifier.weight(1f))
                Box {
                    InfoDot { showInfo = true }
                    if (showInfo) {
                        InfoBalloon(
                            "Why the WiFi name and password",
                            "When Android Auto starts, the vehicle pulls the phone off the dongle's WiFi onto its own, and the app " +
                                "can no longer reach the head unit.\n\nWith the dongle's WiFi name and password the app can " +
                                "bring the phone back by itself (CONNECT AGAIN), so you do not have to pick the network in " +
                                "Android's list.\n\nThey stay in this app's memory only and are forgotten when the app is " +
                                "closed. Nothing is sent anywhere. Android asks you to allow the connection.\n\nThe name can " +
                                "be read from the phone, but Android only allows that with the Location permission. You can " +
                                "also type it.",
                            mono = false,
                        ) { showInfo = false }
                    }
                }
            }
        },
        text = {
            Column {
                Text(
                    "The vehicle answered on the WiFi below. If that is the dongle's WiFi, give its name and password.",
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = ssid,
                    onValueChange = { ssid = it },
                    singleLine = true,
                    label = { Text("WiFi name", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (ssid.isBlank()) {
                    TextButton(
                        onClick = {
                            if (WifiNames.hasLocationPermission(ctx)) readName()
                            else permission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        },
                    ) { Text("READ THE NAME FROM THE PHONE", fontSize = 12.sp) }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    label = { Text("Password (empty if it has none)", fontSize = 12.sp) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (note.isNotEmpty()) {
                    Text(note, fontSize = 12.sp, color = Amber, modifier = Modifier.padding(top = 6.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.saveDongle(ssid, password) }, enabled = ssid.isNotBlank()) { Text("YES, SAVE") }
        },
        dismissButton = { TextButton(onClick = { vm.declineDongle() }) { Text("NOT NOW") } },
    )
}

/**
 * CONNECT AGAIN: Bluetooth must be off first (Android Auto starts over Bluetooth and would pull the phone away again),
 * which is checked live; only then the button that joins the dongle's WiFi is on.
 */
@Composable
internal fun ConnectAgainDialog(vm: MainViewModel) {
    val ctx = LocalContext.current
    val wifi = vm.carWifi
    val dongle = vm.dongle
    var btOn by remember { mutableStateOf(BluetoothState.isOn(ctx)) }
    var showInfo by remember { mutableStateOf(false) }
    var noList by remember { mutableStateOf(false) }

    // the Bluetooth state, looked at again and again while the window is open
    LaunchedEffect(Unit) {
        while (true) {
            btOn = BluetoothState.isOn(ctx)
            delay(700)
        }
    }
    // joined: a moment to read it, then the window closes
    LaunchedEffect(wifi.join) {
        if (wifi.join == WifiJoin.CONNECTED) {
            delay(1200)
            vm.closeConnectAgain()
        }
    }

    val waiting = wifi.join == WifiJoin.WAITING
    val done = wifi.join == WifiJoin.CONNECTED
    // "null" is a phone that would not say: then the person is trusted, not locked out
    val canConnect = dongle != null && btOn != true && !waiting && !done

    AlertDialog(
        onDismissRequest = { vm.closeConnectAgain() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Connect again", fontSize = 18.sp, modifier = Modifier.weight(1f))
                Box {
                    InfoDot { showInfo = true }
                    if (showInfo) {
                        InfoBalloon(
                            "Connect again",
                            "Android Auto starts over Bluetooth and then moves the phone onto the vehicle's own WiFi. While " +
                                "Bluetooth is on, the phone is pulled away again as soon as it joins the dongle.\n\nSo: turn " +
                                "Bluetooth off (the switch is in the quick settings: pull the top bar down; the settings page " +
                                "of some phones only lists devices), then press CONNECT. Android asks you to allow the connection to the dongle's " +
                                "WiFi. When it is joined, the app carries on with the head unit.\n\nIf it does not work, the " +
                                "WiFi list of the phone is the other way.",
                            mono = false,
                        ) { showInfo = false }
                    }
                }
            }
        },
        text = {
            Column {
                Text("1. Turn Bluetooth off", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(14.dp).background(if (btOn == false) Green else Red, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when (btOn) {
                            true -> "Bluetooth is on"
                            false -> "Bluetooth is off"
                            null -> "The phone does not say if Bluetooth is on"
                        },
                        fontSize = 13.sp,
                    )
                }
                Text(
                    "Easiest: pull the top bar down and tap the Bluetooth switch.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                TextButton(
                    onClick = {
                        try {
                            ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                        } catch (_: Exception) {
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                ) { Text("or open the Bluetooth settings", fontSize = 11.sp) }

                Spacer(Modifier.height(14.dp))
                Text("2. Join ${dongle?.ssid ?: "the dongle's WiFi"}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                if (wifi.message.isNotEmpty()) {
                    Text(
                        wifi.message,
                        fontSize = 12.sp,
                        color = when (wifi.join) {
                            WifiJoin.FAILED -> Red
                            WifiJoin.CONNECTED -> Green
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (waiting) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                if (wifi.join == WifiJoin.FAILED) {
                    TextButton(onClick = { noList = !WifiPanel.open(ctx) }) { Text("OPEN THE WIFI LIST INSTEAD", fontSize = 12.sp) }
                    if (noList) Text("The phone would not open its WiFi list.", fontSize = 12.sp, color = Red)
                }
                if (btOn == true) {
                    Text("CONNECT is on as soon as Bluetooth is off.", fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.connectAgain() }, enabled = canConnect) {
                Text(if (wifi.join == WifiJoin.FAILED) "TRY AGAIN" else "CONNECT")
            }
        },
        dismissButton = { TextButton(onClick = { vm.closeConnectAgain() }) { Text("CLOSE") } },
    )
}
