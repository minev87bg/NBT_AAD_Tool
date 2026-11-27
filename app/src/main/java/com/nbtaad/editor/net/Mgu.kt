package com.nbtaad.editor.net

/**
 * Connection facts for iDrive 7 / iDrive 8 (MGU) head units, confirmed against robertnisipeanu/mgu-store's own
 * transport (deploy/transport/mgu_connect.py) and its deploy/README.md: the car's own "DIRECT-BMW..." hotspot
 * always answers at the same fixed address, and over an ENET cable the unit is just some 169.254.x address on
 * the link-local subnet - there is no vehicle gateway (ZGW) to go through first, unlike NBT/NBT EVO.
 *
 * Root SSH on these units is not the fixed NBT password: it is enabled once (by a separate tool, outside this
 * app - see the "Root SSH" note on the MGU hub) and after that logs in with a private key specific to that car.
 * This app only ever consumes that key, pasted in by the person; it never creates or forges one (see MguKeyStore).
 */
object MguConfig {
    const val HOTSPOT_IP = "172.16.222.97"
    const val PORT = 22
    const val USER = "root"

    /** Where the Store's own deploy tool (once installed) lives on the unit. */
    const val DEPLOY_SH = "/var/opt/mgu-deploy/mgu-deploy.sh"
}

object MguDetector {
    /** The hotspot address first (fast, and right whenever the phone is simply joined to the car's own WiFi),
     * then an ENET sweep when the phone has a link-local interface up - the same two routes mgu-install.js looks
     * for itself. Returns the address found, or null. */
    fun findHost(nets: List<LocalNet>): String? {
        if (CarDetector.probe(MguConfig.HOTSPOT_IP, MguConfig.PORT, 1000) == PortState.OPEN) return MguConfig.HOTSPOT_IP
        val enet = nets.filter { it.ip.startsWith("169.254.") }
        if (enet.isEmpty()) return null
        val hosts = LinkedHashSet<String>()
        enet.forEach { hosts += it.sweepHosts() }
        val open = CarDetector.sweep(hosts, MguConfig.PORT, 700).filterValues { it == PortState.OPEN }.keys
        return open.firstOrNull()
    }
}
