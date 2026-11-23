package com.nbtaad.editor.env

/**
 * Hand-made refinements on top of what [ConfigParser] reads from the README. They are used where the README's
 * text cannot be turned into good dropdown choices by a program (one description shared by several settings,
 * values hidden in prose). Every value below was taken from the README's own wording or from the daemon's source.
 * The description always stays the README's: a setting listed here only gets better choices, defaults and a
 * shorter one-line summary. Settings that are not listed here are shown from the README text alone.
 */
object ConfigOverrides {
    /** Settings the daemon reads but the README tables do not list. Their source is the daemon's own code. */
    val EXTRA: List<Opt> = listOf(
        Opt(
            key = "NBT_CLUSTER",
            group = ConfigParser.GROUP_MENTIONED,
            summary = "Cluster / head-up display driver for the navigation arrows.",
            info = "Chooses the driver for the cluster arrows (see NBT_KOMBI_NAV). j29 is the default: the J29's " +
                "SOME/IP KOMBI graph. 6wa is an F-series 6WA cluster, driven through the MOST HUDTerminal.\n\n" +
                "6wb is EXPERIMENTAL and is not in the README or in the official daemon yet: it comes from pull " +
                "request #179 (BMW 6WB cluster) and only exists in the test release tagged 6wb (v1.9.62). A daemon " +
                "without it logs the value as unknown and the arrows stay off. With it, 6wb starts in a log-only " +
                "mode: for real drawing also set NBT_HUDTERM=on and NBT_KOMBI_NAV=1 (those two are listed below). " +
                "Testers reported it not working yet on a 2013 F30 and a 2017 G31.\n\n" +
                "The daemon compares the value exactly and in lowercase: 6WB or 6WA in capitals is logged as " +
                "unknown and the cluster arrows stay off.\n\n" +
                "Source: the daemon's code (cluster/nav_core.cpp) and pull request #179. The README only mentions " +
                "it in the 6WA rows.\n\n" +
                "Default: j29",
            default = "j29",
            values = listOf("j29", "6wa", "6wb"),
            labels = mapOf("j29" to "Supra J29", "6wa" to "F-series 6WA", "6wb" to "F-series 6WB (experimental test build)"),
            closed = true,
        ),
        Opt(
            key = "NBT_HUDTERM",
            group = ConfigParser.GROUP_MENTIONED,
            summary = "6WA / 6WB cluster: draw for real (on) or only write what it would draw to the log (log).",
            info = "Only used with NBT_CLUSTER=6wa or 6wb (the cluster driver that talks to the MOST HUDTerminal).\n\n" +
                "log: shadow mode. Everything is worked out and each call is written to the log, but nothing is sent " +
                "to the cluster.\n" +
                "on: live mode, the cluster is really drawn on.\n\n" +
                "6wa: when the setting is absent it is live; log (or any other value) is shadow mode.\n" +
                "6wb (test build, pull request #179): shadow mode unless the value is exactly on.\n\n" +
                "Source: the daemon's code (cluster/nav_core.cpp) and pull request #179. The README does not list it.\n\n" +
                "Default: 6wa live, 6wb log",
            default = "6wa: on, 6wb: log",
            values = listOf("on", "log"),
            labels = mapOf("on" to "draw on the cluster", "log" to "shadow mode, log only"),
            closed = true,
        ),
    )

    /** Old aliases that the README only names to say they are gone. */
    val HIDDEN: Set<String> = setOf("NBT_CCS_DEBUG", "NBT_CCS_ALL")

    private class Ov(
        val summary: String? = null,
        val default: String? = null,
        val values: List<String>? = null,
        val labels: Map<String, String>? = null,
        val hint: String? = null,
        val closed: Boolean = false,
    )

    private val ONOFF = mapOf("1" to "on", "0" to "off")

    /** A 1 / 0 switch. [default] is how the README says it behaves when the setting is absent. */
    private fun sw(default: String? = null, summary: String? = null) =
        Ov(summary = summary, default = default, values = listOf("1", "0"), labels = ONOFF)

    private fun txt(summary: String? = null, default: String? = null, hint: String? = null) =
        Ov(summary = summary, default = default, hint = hint)

    private fun pick(
        values: List<String>,
        labels: Map<String, String> = emptyMap(),
        default: String? = null,
        summary: String? = null,
        closed: Boolean = false,
    ) = Ov(summary = summary, default = default, values = values, labels = labels, closed = closed)

    private val MAP: Map<String, Ov> = mapOf(
        // ---- Common settings
        "NBT_LOG_FILE" to txt(hint = "/mnt/umass01200t7/nbt-aad.log"),
        "NBT_MENU_RETURN" to pick(
            listOf("home", "media", "off"),
            mapOf("home" to "iDrive home", "media" to "Media/Radio list", "off" to "stay in our menu"),
            default = "home", closed = true,
        ),
        "NBT_VID_FPS" to pick(
            listOf("30", "60"),
            mapOf("30" to "decoder at 30, smoother on a slower unit", "60" to "ask the phone for 60"),
            default = "unset: phone asked for 30, decoder declared at 60",
        ),
        "NBT_KNOB_INVERT" to sw(default = "off"),
        "NBT_VEHICLE" to pick(
            listOf("toyota", "bmw", "mini", "rollsroyce"),
            default = "from carplay.ini (CarPlay logo, then Manufacturer)", closed = true,
        ),
        "NBT_SCREEN" to pick(
            listOf("1280x480", "1440x540"),
            mapOf("1280x480" to "most panels", "1440x540" to "BMW 10.25\""),
            default = "from carplay.ini, else what LayerManager reports, else 1280x480",
        ),

        // ---- Features you can turn off
        "NBT_AA_CAMERA_CTX" to txt(hint = "692,1023"),
        "NBT_KEEP_PF" to Ov(values = listOf("1"), labels = mapOf("1" to "don't run pfctl -d")),
        "NBT_LCI_MEDIA" to sw(default = "decided by the Settings checkbox"),
        "NBT_PHONE_GPS" to sw(default = "decided by the Settings checkbox"),

        // ---- Advanced / experimental
        "NBT_AA_SURFACE" to pick(
            listOf("1282", "1280"),
            mapOf("1282" to "its own surface", "1280" to "shared with the factory CarPlay"),
            default = "1282",
        ),
        "NBT_USB" to sw(default = "off", summary = "Android Auto over USB (experiment, off). Needs a restart of the daemon."),
        "NBT_USB_DIRECT" to sw(default = "on while USB is on", summary = "Read the phone through io-usb (1) or through /dev/oap* (0)."),
        "NBT_BTSPP_DEV" to txt(summary = "Use only this Bluetooth SPP node.", hint = "/dev/btspp0"),
        "NBT_WPP_MODE" to pick(
            listOf("0", "1", "2"),
            default = "rotates 2, 0, 1 on failures",
            summary = "Pin the Bluetooth handshake mode.",
        ),
        "NBT_AP_CFG" to txt("Hotspot config file.", "iap2.cfg (iap2_Wifi.cfg as fallback)", "iap2.cfg"),
        "NBT_AP_IF" to txt("Hotspot network interface.", "uap0", "uap0"),
        "NBT_AP_IP" to txt("Hotspot address.", "172.16.224.1", "172.16.224.1"),
        "NBT_AP_BSSID" to txt("Hotspot BSSID."),
        "NBT_ALT_WIFI" to pick(
            listOf("CARPLAY", "DIRECT"),
            mapOf("CARPLAY" to "the CarPlay hotspot", "DIRECT" to "the vehicle's Wi-Fi Direct group only"),
            default = "CARPLAY", summary = "Which Wi-Fi the phone is offered, one per run.", closed = true,
        ),
        "NBT_WFD_IF" to txt("Wi-Fi Direct network interface.", "wfd0", "wfd0"),
        "NBT_WFD_SSID" to txt("Wi-Fi Direct group name.", hint = "DIRECT-03-Toyota51807"),
        "NBT_SPP_UUID_CON" to txt(default = "the Android Auto UUID"),
        "NBT_BT_TRACE" to sw(default = "off"),
        "NBT_BT_SRC" to txt("Bluetooth mode: audio source.", "0x3a", "0x3a"),
        "NBT_BT_SINK" to txt("Bluetooth mode: audio sink.", "0x1", "0x1"),
        "NBT_BT_RELEASE" to sw(default = "off", summary = "Release the Bluetooth audio source when the phone leaves."),
        "NBT_AUDIOCONN" to txt("Path of the audioconn tool.", "/fs/sda0/opt/mm/bin/audioconn", "/fs/sda0/opt/mm/bin/audioconn"),
        "NBT_AUDIOCONN_SUFFIX" to txt("Extra arguments for audioconn."),
        "NBT_AUDIOCONN_RETRIES" to txt("Retries while the vehicle refuses the audio source (max 20).", "6", "6"),
        "NBT_AUDIOCONN_RETRY_S" to txt("Pause between those retries in seconds, growing by 2 s up to 12 s.", "5", "5"),
        "NBT_BT_ROUTE_DELAY_MS" to pick(
            listOf("1500", "0"), mapOf("0" to "at once"), default = "1500",
            summary = "Wait before the Bluetooth-source audioconn after a session starts (ms).",
        ),
        "NBT_CHILD_NO_PRELOAD" to sw(default = "on", summary = "Remove LD_PRELOAD for child processes (on by default since v1.9.69)."),
        "NBT_TOUCH_CONNECT" to sw(default = "on", summary = "Connect the touch socket so BMW gesture control reaches iDrive."),
        "NBT_TOUCH_PEER" to txt("Touch controller address, learned automatically after the first touch.", hint = "ip:port"),
        "NBT_BT_PLAY" to pick(
            listOf("1", "2"),
            mapOf("1" to "send PLAY once the source is granted", "2" to "also AVRCP START"),
            default = "off", summary = "Experiment: start playback on the vehicle's Bluetooth source.",
        ),
        "NBT_BT_CONN" to pick(
            listOf("0xc7", "0"), mapOf("0xc7" to "vehicle-verified on a MINI F60", "0" to "old focus form"),
            default = "0xc7", summary = "ConnId of the vehicle's Bluetooth audio source.",
        ),
        "NBT_SDR_DROP" to txt(default = "9 in Bluetooth mode", hint = "9"),
        "NBT_RAM_LOG" to sw(default = "on"),
        "NBT_AUDIO_MODE" to pick(
            listOf("bt", "browser", "a2i"),
            mapOf("bt" to "Bluetooth A2DP", "browser" to "the vehicle's browser audio", "a2i" to "A2I pipeline, experimental"),
            default = "bt", closed = true,
        ),
        "NBT_BROWSER_CONN" to pick(listOf("0xb8", "0"), mapOf("0" to "don't route"), default = "0xb8"),
        "NBT_BROWSER_IDLE_S" to pick(listOf("10", "0"), mapOf("0" to "keep until the session ends"), default = "10"),
        "NBT_BROWSER_PROMPT_IDLE_MS" to pick(listOf("1000", "0"), mapOf("0" to "use the 10 s rule"), default = "1000"),
        "NBT_BROWSER_REFOCUS" to sw(default = "on", summary = "Browser mode: ask for the audio source again when the mic closes."),
        "NBT_BROWSER_PCM" to txt(hint = "card:device"),
        "NBT_BROWSER_RATE" to pick(
            listOf("48000", "44100"), mapOf("44100" to "the vehicle's browser device takes up to 44100"),
            default = "48000",
        ),
        "NBT_BROWSER_ASOUND" to txt(hint = "/opt/mm/lib/libasound/libasound.so"),
        "NBT_DUCK_DB" to txt("Browser mode: media level under a prompt or system sound, in dB.", "-12", "-12"),
        "NBT_DUCK_ATTACK_MS" to txt("Browser mode ducking: ramp-down time in ms.", "80", "80"),
        "NBT_DUCK_HOLD_MS" to txt("Browser mode ducking: hold time in ms.", "600", "600"),
        "NBT_DUCK_RELEASE_MS" to txt("Browser mode ducking: ramp-up time in ms.", "500", "500"),
        "NBT_MEDIA_GAIN_DB" to txt("Browser mode: fixed gain of the media stream, in dB.", "0", "0"),
        "NBT_GUIDANCE_GAIN_DB" to txt("Browser mode: fixed gain of the guidance stream, in dB.", "0", "0"),
        "NBT_SYSTEM_GAIN_DB" to txt("Browser mode: fixed gain of the system sounds, in dB.", "0", "0"),
        "NBT_SYSTEM_SINK" to sw(default = "in, in browser mode only"),
        "NBT_BROWSER_DRIFT" to sw(default = "on", summary = "Browser mode: clock-drift correction for Android Auto media."),
        "NBT_BROWSER_DRIFT_LOOP" to pick(
            listOf("pi", "p"),
            mapOf("pi" to "proportional-integral", "p" to "proportional only"),
            default = "pi", closed = true,
        ),
        "NBT_AA_DEFER_ACKS" to sw(default = "on", summary = "Browser mode: ack audio frames when the mixer has taken them."),
        "NBT_AA_MAX_UNACKED" to txt("Browser mode: how many audio frames the phone may run ahead (4 to 64).", "10", "10"),
        "NBT_AA_ACK_FORCE_MS" to txt("Browser mode: ack a frame anyway after this many ms (0 = never).", "1000", "1000"),
        "NBT_BROWSER_PRIO" to pick(listOf("15", "0"), mapOf("0" to "leave it alone"), default = "15"),
        "NBT_AA_MINOR" to pick(
            listOf("6", "7"), mapOf("6" to "1.6", "7" to "1.7, what Google's head unit asks"),
            default = "6", summary = "Experiment: the Android Auto protocol minor version the daemon asks for.",
        ),
        "NBT_AA_ACK_TIMES" to sw(default = "on", summary = "Put the arrival time into audio acks."),
        "NBT_GUIDANCE_RATE" to pick(
            listOf("48000", "16000", "0"),
            mapOf("16000" to "offer only 16 kHz", "0" to "keep both offers"),
            default = "48000",
        ),
        "NBT_AA_UNDERFLOW_REPORT" to sw(default = "on", summary = "Browser mode: tell the phone when a stream ran dry."),
        "NBT_BROWSER_RESAMPLER" to pick(
            listOf("speex", "linear"), mapOf("speex" to "speexdsp, best quality", "linear" to "old linear interpolation"),
            default = "speex", summary = "Browser mode: how the Android Auto streams are converted to the device rate.", closed = true,
        ),
        "NBT_BROWSER_SPEEX_Q" to txt("Browser mode: speex resampler quality, 0 to 10.", "5", "5"),
        "NBT_BROWSER_RESAMPLER_FILE" to txt("Browser mode: file read once a second to switch the resampler.", hint = "/tmp/nbt_resampler"),
        "NBT_BROWSER_FORCE_RATE" to txt(default = "off", hint = "48000"),
        "NBT_SPEECH" to sw(default = "on in browser mode, off in Bluetooth mode"),
        "NBT_SPEECH_EP" to pick(
            listOf("/input_mono_0", "/input_stereo_AirPlayAlt"),
            mapOf("/input_mono_0" to "browser mode default", "/input_stereo_AirPlayAlt" to "Bluetooth mode default"),
            default = "/input_mono_0 in browser mode, /input_stereo_AirPlayAlt in Bluetooth mode",
            summary = "Endpoint for the navigation prompts.",
        ),
        "NBT_SPEECH_SRC" to txt("Audio source requested for each prompt.", "0x1a in browser mode, 0 in Bluetooth mode", "0x1a"),
        "NBT_SPEECH_SINK" to txt("Audio sink requested for each prompt.", "0x1", "0x1"),
        "NBT_SPEECH_BT" to sw(default = "off"),
        "NBT_VID_INSETS" to pick(
            listOf("off", "0,240,0,0", "0,180,0,0"),
            mapOf(
                "off" to "keep the manifest's insets",
                "0,240,0,0" to "1280x480 at 720p",
                "0,180,0,0" to "1280x480 at 480p",
            ),
            default = "calculated from the canvas and panel size",
            summary = "Phone UI insets: top,bottom,left,right.",
        ),
        "NBT_TOUCH_OX" to txt("Touch offset x, in canvas pixels.", "0", "0"),
        "NBT_TOUCH_OY" to txt("Touch offset y, in canvas pixels.", "0", "0"),
        "NBT_VID_DPI" to pick(
            listOf("140", "152", "160", "0"),
            mapOf("152" to "MINI round display", "0" to "phone default"),
            default = "140 at 720p (scaled with the canvas)",
        ),
        "NBT_RAIL_HIDE" to pick(
            listOf("all", "clock", "signal", "battery"),
            mapOf("all" to "clock, signal and battery"),
            default = "unset: all shown",
            summary = "Hide the phone's clock, signal and/or battery level at the top of the rail.",
        ),
        "NBT_VID_RES" to pick(
            listOf("1", "2", "3"),
            mapOf("1" to "800x480", "2" to "1280x720", "3" to "1920x1080"),
            default = "2 (1280x720)", closed = true,
        ),
        "NBT_VID_MW" to txt("Manual video margin width.", "0 at 480p / 1080p", "0"),
        "NBT_VID_MH" to txt("Manual video margin height.", "0 at 480p / 1080p", "0"),
        "NBT_VID_SRC_H" to txt("Manual decoder height."),
        "NBT_VID_DST_H" to txt("Manual renderer height."),
        "NBT_MMR_SPAWN" to sw(default = "on"),
        "NBT_TOUCH_SX" to txt("Touch scale, x."),
        "NBT_TOUCH_SY" to txt("Touch scale, y."),
        "NBT_TOUCH_W" to txt("Touch clamp width.", "1280", "1280"),
        "NBT_TOUCH_H" to txt("Touch clamp height.", "720", "720"),
        "NBT_TOUCH_IDLE_MS" to txt("Stuck-finger release time in ms.", "400", "400"),
        "NBT_TOUCH_CFG_H" to txt("Declared touch height.", "720", "720"),
        "NBT_TOUCH_PORT" to txt("UDP port of the touch controller.", "30567", "30567"),
        "NBT_TOUCH_PROBE_SECS" to txt("Length of the raw touch log in seconds."),
        "NBT_GEAR_REVERSE" to pick(
            listOf("2", "3", "1", "5"),
            mapOf("2" to "R", "3" to "P", "1" to "N", "5" to "D"),
            default = "2 (R)", summary = "Gear value the vehicle reports for reverse.",
        ),
        "NBT_NEUTRAL_RESUME_S" to txt("Manual gearbox: seconds in neutral after reverse before Android Auto returns.", "5", "5"),
        "NBT_GEAR_NEUTRAL" to pick(
            listOf("1", "2", "3", "5"), mapOf("2" to "R", "3" to "P", "1" to "N", "5" to "D"),
            default = "1 (N)", summary = "Gear value the vehicle reports for neutral.",
        ),
        "NBT_REVERSE_FOCUS" to sw(summary = "Also tell the phone it lost the screen in reverse."),
        "NBT_BACK_HOLD_FOCUS" to sw(summary = "Also tell the phone it lost the screen on a BACK hold."),
        "NBT_CP_HANDOFF" to sw(default = "on"),
        "NBT_CP_COMPAT" to sw(default = "decided by the Settings checkbox"),
        "NBT_CP_YIELD" to sw(default = "decided by CarPlay compatibility", summary = "Yield to CarPlay completely."),
        "NBT_CP_PURGE" to pick(
            listOf("1", "0"), mapOf("1" to "destroy the CarPlay decoder context", "0" to "detach it"),
            default = "decided by CarPlay compatibility",
        ),
        "NBT_CP_WINGRP" to pick(
            listOf("1", "0"), mapOf("1" to "in our window group", "0" to "top-level"),
            default = "decided by CarPlay compatibility", summary = "Where CarPlay's window lives.",
        ),
        "NBT_CP_LIVE_RETUNE" to sw(default = "on"),
        "NBT_PTT_ASSISTANT" to sw(default = "off"),
        "NBT_MFL_NEXT" to txt("Steering-wheel button that becomes the Android next key.", "511:2", "511:2"),
        "NBT_MFL_PREV" to txt("Steering-wheel button that becomes the Android previous key.", "511:1", "511:1"),
        "NBT_MFL_PLAYPAUSE" to txt("Steering-wheel button that becomes the Android play/pause key."),
        "NBT_MFL_LOG" to sw(default = "on", summary = "Log every change of the steering-wheel buttons."),
        "NBT_AA_BROWSE" to sw(default = "off"),
        "NBT_GPS_ACCURACY_M" to txt(default = "HDOP x 5 m, at least 2 m; 10 m without HDOP", hint = "2 to 499"),
        "NBT_NIGHT_MODE" to pick(
            listOf("0", "1"), mapOf("0" to "always day", "1" to "always night"),
            default = "follows the vehicle", summary = "Force day or night mode.",
        ),
        "NBT_DRIVING_STATUS" to sw(summary = "Driving-status sensor value."),
        "NBT_CAR_MAKE" to txt("Vehicle make reported to the phone."),
        "NBT_CAR_MODEL" to txt("Vehicle model reported to the phone."),
        "NBT_CAR_YEAR" to txt("Vehicle model year reported to the phone."),
        "NBT_VEHICLE_ID" to txt("Vehicle id reported to the phone."),
        "NBT_HU_MAKE" to txt("Head unit make reported to the phone."),
        "NBT_HU_MODEL" to txt("Head unit model reported to the phone."),
        "NBT_SW_VERSION" to txt("Software version reported to the phone."),
        "NBT_BT_ADDR" to txt("Bluetooth address reported to the phone."),
        "NBT_DRIVER_SIDE" to pick(
            listOf("left", "right"), default = "left", closed = true,
            summary = "The driver's side Android Auto is told.",
        ),
        "NBT_CARPLAY_INI" to txt(hint = "/var/opt/conn/carplay/carplay.ini"),
        "NBT_KOMBI_NAV" to sw(default = "decided by the menu checkbox"),
        "NBT_KOMBI_NAV_APPROACH" to pick(
            listOf("time", "distance", "off"),
            mapOf("time" to "by the phone's seconds to it", "distance" to "within NBT_KOMBI_NAV_APPROACH_MI", "off" to "at once"),
            default = "time", closed = true,
        ),
        "NBT_KOMBI_NAV_WARN_S" to txt("Time mode: seconds before the maneuver it shows (10 to 120).", "50", "50"),
        "NBT_KOMBI_NAV_MAIN_S" to txt("Time mode: seconds before the maneuver it is shown again (0 to 60, 0 = never).", "20", "20"),
        "NBT_KOMBI_NAV_MWAY_WARN_M" to txt("Motorway off-ramps and forks show this many metres before them (200 to 5000).", "2000", "2000"),
        "NBT_KOMBI_NAV_MWAY_MAIN_M" to txt("Motorway off-ramps and forks show again at this distance in metres (200 to 5000).", "1000", "1000"),
        "NBT_KOMBI_NAV_APPROACH_MI" to txt("Show a maneuver only within this many miles (0.1 to 9.9, 0 = at once).", "1", "1"),
        "NBT_NO_LOG" to sw(default = "off"),
        "NBT_KOMBI_NAV_SHOW_BAR" to sw(default = "off"),
        "NBT_KOMBI_NAV_YIELD" to sw(default = "on"),
        "NBT_KOMBI_NAV_LANES" to sw(default = "on"),
        "NBT_HUD_JUNCTION" to sw(default = "on", summary = "6WA cluster: junction view near the turn (0 keeps the arrow)."),
        "NBT_HUD_LANES" to sw(default = "off"),
        "NBT_KOMBI_NAV_INPLACE" to pick(
            listOf("1", "2", "0"),
            mapOf("1" to "in-place update", "2" to "in-place, without the guiding model", "0" to "the whole route graph again"),
            default = "1", closed = true,
            summary = "How the next maneuver is sent on a route already shown.",
        ),
        "NBT_KOMBI_NAV_HANDLE" to txt("KOMBI guidance instance handle.", "0xC8", "0xC8"),
        "NBT_KOMBI_NAV_HOST" to txt("KOMBI guidance address.", "160.48.199.96", "160.48.199.96"),
        "NBT_KOMBI_NAV_PORT" to txt("KOMBI guidance port.", "30502", "30502"),
        "NBT_DIAG" to sw(default = "off", summary = "Window/LayerManager dump 8 s into a session."),
        "NBT_VERBOSE" to sw(default = "off", summary = "Verbose protocol log."),
        "NBT_USB_CLAIM_DRIVER" to sw(default = "on", summary = "Keep devc-serusb-aoap from starting (0 = stop it after it started)."),
        "NBT_USB_MANUFACTURER" to txt("USB identity: manufacturer name sent to a phone that never answers."),
        "NBT_USB_MODEL" to txt("USB identity: model name sent to a phone that never answers."),
        "NBT_AUDIO_STAGE" to txt("A2I test pipeline (NBT_AUDIO_BT=0 only): stage 0 to 2."),
        "NBT_MEDIA_RATE" to txt("A2I test pipeline: media rate."),
        "NBT_ROUTE_MODE" to txt("A2I test pipeline: routing mode."),
        "NBT_ROUTE_MEDIA" to txt("A2I test pipeline: media route."),
        "NBT_ROUTE_NAV" to txt("A2I test pipeline: navigation route."),
        "NBT_ROUTE_SINK" to txt("A2I test pipeline: sink."),
        "NBT_BT_PLAY_DELAY" to txt("Delay in ms before PLAY is sent.", "1500", "1500"),
    )

    fun apply(o: Opt): Opt {
        val ov = MAP[o.key] ?: return o
        var info = o.info
        if (ov.default != null) {
            info = info.replace(Regex("\n\nDefault: [\\s\\S]*$"), "") + "\n\nDefault: " + ov.default
        }
        val values = ov.values ?: o.values
        val labels = ov.labels ?: if (ov.values != null) emptyMap() else o.labels
        return o.copy(
            summary = ov.summary ?: o.summary,
            info = info,
            default = ov.default ?: o.default,
            values = values,
            labels = labels.filterKeys { it in values },
            hint = ov.hint ?: o.hint,
            closed = ov.closed || o.closed,
        )
    }
}
