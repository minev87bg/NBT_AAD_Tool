package com.nbtaad.editor.net

import android.content.Context
import com.nbtaad.editor.install.AssetInfo
import com.nbtaad.editor.install.ReleaseInfo
import com.nbtaad.editor.install.ReleasesApi
import com.nbtaad.editor.log.AppLog
import com.nbtaad.editor.update.UpdateLogic
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream
import org.json.JSONObject

/**
 * Installs JayJay3071's "NBT Android Auto" package - Android Auto and CarPlay for the ORIGINAL BMW NBT (QNX,
 * ~2012-07 to 2015-09). This is a DIFFERENT head unit generation from NBT Evo: the project's own README says
 * plainly that "NBT Evo uses a different DSI library and is not supported", and [preflight] below refuses rather
 * than guess if the checks that tell the two apart do not come back as expected. Keep this separate from the
 * app's NBT-AAD/EVO flows (net/Ssh.kt's EnvRemote, install/HeadUnit.kt) - different hardware, different protocol,
 * different files on the head unit.
 *
 * Ported from that project's own installer/nbt_aa_installer.py: the same two target folders, the same atomic
 * tmp-write / read-back / chmod / rename per file (its sshd has no reliable SFTP either), the same manifest-driven
 * file list and the same special-casing (iap.sh enables sound, decoder.hw only on a first install, nbt_reset's
 * gateway file is only ever stored here, never used to send a reset - the head unit's own nbt_reset program does
 * that, from its iDrive page, exactly as AGENTS.md requires: never a direct shutdown/reset from this app).
 *
 * Licensing: a release's finished files are under the PolyForm Noncommercial License 1.0.0 (no commercial use).
 * Valentin Radu's own permission for his programs inside it (nbt_decoder, nbt_carplay, nbt_reset) is granted to
 * JayJay3071 for "the resulting binary releases of nbt-android-auto" specifically and may not be transferred or
 * sublicensed (see PERMISSION-valinet-binary-releases.txt) - so this app must never bundle or redistribute that
 * payload itself. It only reads whatever official release zip the person already downloaded from
 * https://github.com/JayJay3071/nbt-android-auto/releases (the same "...-Python-....zip" their own installer
 * uses) and pushes its files to the head unit over SSH; nothing from it is kept in this app's own repository or
 * APK. [CREDITS] and [permissionText] must be shown wherever this feature shows "About"/"Credits" information -
 * that is condition 3 of Valentin Radu's permission.
 */
object JjAa {
    const val PROJECT_URL = "https://github.com/JayJay3071/nbt-android-auto"
    const val RELEASES_LIST_URL = "https://api.github.com/repos/JayJay3071/nbt-android-auto/releases"
    const val DEFAULT_HOST = "169.254.199.99"

    const val RT_DIR = "/var/opt/hmi/data/rw/NbtAndroidAutoRT"
    const val PLUGIN_DIR = "/var/opt/hmi/data/rw/Plugins/NbtAndroidAuto"

    const val CREDITS =
        "Sharp picture and hardware decoding (nbt_decoder), CarPlay (nbt_carplay) and the head unit reset " +
            "(nbt_reset): Copyright (c) 2026 Valentin Radu <valentin.radu@valinet.ro>, " +
            "https://github.com/valinet/nbt-android-auto\n" +
            "Android Auto core and controller code: NBT-AAD, Copyright (c) 2026 nanaisu, " +
            "https://github.com/Sq00ky/Supra-iDrive6-Android-Auto\n" +
            "Source of everything, licenses: $PROJECT_URL\n\n" +
            "The finished files of a release are under the PolyForm Noncommercial License 1.0.0: free for private " +
            "and other noncommercial use, no commercial use."

    /** The project's own releases (what a person would see browsing its GitHub Releases page), newest first. */
    fun releases(ctx: Context): List<ReleaseInfo> = ReleasesApi.listFrom(ctx, RELEASES_LIST_URL)

    /**
     * The asset of a release this app can read: the cross-platform Python zip (script + payload/ folder), not the
     * Windows .exe - that one is a PyInstaller archive with the payload packed inside it in a way this app does not
     * parse.
     */
    fun payloadAsset(release: ReleaseInfo): AssetInfo? =
        release.assets.firstOrNull { it.name.endsWith(".zip", ignoreCase = true) && it.name.contains("python", ignoreCase = true) }

    // ---- payload: the official release zip, read and checked, never bundled by this app ----

    data class ManifestEntry(val source: String, val target: String, val mode: String, val size: Long, val sha256: String)
    data class Manifest(val version: String, val entries: List<ManifestEntry>)

    /** A verified payload: the manifest, and every file it lists, by its manifest "source" name. */
    class Payload(val manifest: Manifest, val files: Map<String, ByteArray>) {
        /** The full text of Valentin Radu's permission, read from the release's own copy so it can never drift
         * from what the project actually ships (condition 3 of that permission wants it shown verbatim). */
        val permissionText: String
            get() = files.entries.firstOrNull { it.key.endsWith("PERMISSION-valinet-binary-releases.txt") }
                ?.value?.toString(Charsets.UTF_8)
                ?: "(PERMISSION-valinet-binary-releases.txt was not in this release's payload; see $PROJECT_URL)"
    }

    class PayloadError(message: String) : IOException(message)

    private const val MAX_ENTRY = 64L * 1024 * 1024
    private const val MAX_TOTAL = 160L * 1024 * 1024

    /**
     * Reads the official "...-Python-....zip" release: finds payload/manifest.json inside it (at whatever depth,
     * in case the zip has an extra top folder), reads every file the manifest lists, and checks its size and
     * SHA-256 against the manifest - the same check the Python installer's own load_manifest() does. Throws
     * [PayloadError] with a plain reason rather than importing anything that does not check out.
     */
    fun parsePayloadZip(bytes: ByteArray): Payload {
        if (bytes.size < 4 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) {
            throw PayloadError("That is not a zip file.")
        }
        val entries = LinkedHashMap<String, ByteArray>()
        var total = 0L
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    if (e.isDirectory) continue
                    val path = e.name.replace('\\', '/')
                    if (path.contains("..")) continue
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val n = zin.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_TOTAL) throw PayloadError("That zip holds more data than a release has.")
                        out.write(buf, 0, n)
                        if (out.size() > MAX_ENTRY) throw PayloadError("$path is larger than any file of a release.")
                    }
                    entries[path] = out.toByteArray()
                }
            }
        } catch (e: PayloadError) {
            throw e
        } catch (e: Exception) {
            throw PayloadError("Could not read the zip: ${e.message ?: e.javaClass.simpleName}")
        }
        val manifestKey = entries.keys.firstOrNull { it.endsWith("payload/manifest.json") }
            ?: throw PayloadError(
                "No payload/manifest.json was found in this zip - is it the official \"...-Python-....zip\" " +
                    "release from $PROJECT_URL/releases?"
            )
        val base = manifestKey.removeSuffix("manifest.json")
        val manifestObj = try {
            JSONObject(String(entries.getValue(manifestKey), Charsets.UTF_8))
        } catch (e: Exception) {
            throw PayloadError("payload/manifest.json is not valid JSON.")
        }
        val version = manifestObj.optString("version", "?")
        val filesArr = manifestObj.optJSONArray("files")
            ?: throw PayloadError("payload/manifest.json has no \"files\" list.")
        val parsed = ArrayList<ManifestEntry>()
        val files = LinkedHashMap<String, ByteArray>()
        for (i in 0 until filesArr.length()) {
            val o = filesArr.getJSONObject(i)
            val source = o.getString("source")
            val target = o.getString("target")
            val mode = o.optString("mode", "644")
            val size = o.optLong("size", -1L)
            val sha256 = o.getString("sha256")
            val data = entries[base + source]
                ?: throw PayloadError("$source is listed in the manifest but is missing from the zip.")
            if (size >= 0 && data.size.toLong() != size) {
                throw PayloadError("$source is ${data.size} bytes, expected $size - the zip may be damaged.")
            }
            if (UpdateLogic.sha256Hex(data) != sha256) {
                throw PayloadError("$source does not match the checksum in the manifest - the zip may be damaged.")
            }
            files[source] = data
            parsed += ManifestEntry(source, target, mode, data.size.toLong(), sha256)
        }
        AppLog.i("JjAa", "Payload $version: ${parsed.size} files, ${entries.size} zip entries read")
        return Payload(Manifest(version, parsed), files)
    }

    // ---- SSH protocol: exec channels only (no reliable SFTP on this head unit either) ----

    /** Writes [data] to [target] atomically: target.tmp, read back, compared byte for byte, then chmod + rename -
     * exactly nbt_aa_installer.py's own Nbt.put(). Throws if anything along the way does not check out. */
    private fun SshSession.putAtomic(data: ByteArray, target: String, mode: String) {
        val tmp = "$target.tmp"
        val timeout = 30_000L + data.size / 4
        val w = exec("cat > '$tmp'", stdin = data, timeoutMs = timeout)
        if (w.exit != 0) throw IOException("Writing $target failed: ${w.err.trim().ifEmpty { "exit ${w.exit}" }}")
        val back = exec("cat '$tmp'", timeoutMs = timeout)
        if (!back.out.contentEquals(data)) {
            exec("rm -f '$tmp'")
            throw IOException("Read-back mismatch: $target")
        }
        val r = exec("chmod $mode '$tmp' && mv '$tmp' '$target'")
        if (r.exit != 0) throw IOException("Could not finish writing $target: ${r.err.trim().ifEmpty { "exit ${r.exit}" }}")
    }

    class PreflightError(message: String) : IOException(message)

    private class Check(val cmd: String, val what: String, val required: Boolean)

    /** Confirms this is really the original NBT (and not NBT Evo, or anything else) before a single file is
     * written - the same checks nbt_aa_installer.py's own preflight() makes. Refuses rather than guesses. */
    private fun preflight(ssh: SshSession, log: (String) -> Unit) {
        val uname = ssh.exec("uname -s; uname -m; uname -r", timeoutMs = 10_000)
        val info = uname.text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        log("System: ${info.joinToString(" ").ifEmpty { "no answer" }}")
        if (info.isEmpty() || info[0] != "QNX") {
            throw PreflightError(
                "This does not look like a QNX head unit (uname said: ${info.joinToString(" ").ifEmpty { "nothing" }}). " +
                    "This installer is only for the original BMW NBT, not NBT Evo."
            )
        }
        val checks = listOf(
            Check("test -d '/var/opt/hmi/data/rw'", "HMI data directory /var/opt/hmi/data/rw", true),
            Check("test -x '/opt/mm/bin/NmeCmdLine'", "Cinemo NmeCmdLine (video decoder)", true),
            Check("test -f '/opt/mm/lib/cinemo/cinemo_classes.xml'", "Cinemo configuration", true),
            Check("test -f '/opt/sys/lib/libsys_dsi_colibry.so.3'", "DSI library (iDrive controller)", false),
            Check("test -e '/dev/DisplayManager:0'", "DisplayManager", false),
        )
        for (c in checks) {
            val ok = ssh.exec(c.cmd, timeoutMs = 8_000).exit == 0
            log((if (ok) "  ok      " else if (c.required) "  MISSING " else "  warning ") + c.what)
            if (c.required && !ok) {
                throw PreflightError(
                    "Unsupported head unit: ${c.what} not found. This package is only for the original NBT - " +
                        "NBT Evo uses a different DSI library and cannot run it."
                )
            }
        }
        if (ssh.exec(
                "test -d '/opt/hmi/ID5/data/ro/Plugins/NbtAad' -o -d '/var/opt/hmi/data/rw/Plugins/NbtAad'",
                timeoutMs = 8_000,
            ).exit == 0
        ) {
            log("  warning another Android Auto solution (NbtAad) is installed on this head unit - do not run both at once")
        }
        ssh.exec("mkdir -p '$RT_DIR' '$PLUGIN_DIR'", timeoutMs = 10_000)
        val probe = "$RT_DIR/.write-test"
        if (ssh.exec("touch '$probe' && rm -f '$probe'", timeoutMs = 8_000).exit != 0) {
            throw PreflightError("The HMI data directory is read-only.")
        }
    }

    /** What an install left behind for the person to know: the version now on the head unit, how many files were
     * written, and a note about the car's gateway (for the head unit's own "Restart NBT" button), if relevant. */
    class Outcome(val version: String, val filesWritten: Int, val gatewayNote: String?)

    /**
     * Installs or updates the package on the head unit at [host] (plain SSH, port 22, user root - the password is
     * asked for by the caller and passed in [password], never stored). [findGateway] is called only if the
     * payload includes nbt_reset and no gateway is already stored on the head unit; a null result is not an
     * error - it only means the head unit's own "Restart NBT" page will say its gateway is not set yet.
     */
    fun install(
        host: String,
        password: String,
        payload: Payload,
        rep: (String) -> Unit,
        findGateway: () -> String? = { null },
    ): Outcome {
        rep("Logging in to $host…")
        return SshSession(host, password).use { ssh ->
            rep("Logged in.")
            preflight(ssh, rep)
            rep("Package ${payload.manifest.version}, ${payload.manifest.entries.size} files")
            ssh.exec("chmod 700 '$RT_DIR'")
            val hadDecoder = ssh.exec("test -f '$RT_DIR/nbt_decoder'").exit == 0
            // the plugin's manifest goes last: the HMI must never see a half-written package
            val ordered = payload.manifest.entries.sortedBy { it.target.endsWith(".manifest") }
            for (entry in ordered) {
                val data = payload.files.getValue(entry.source)
                rep("  write ${entry.target.substringAfterLast('/')} (${data.size} bytes)")
                ssh.putAtomic(data, entry.target, entry.mode)
            }
            val dpi = "$RT_DIR/vid.dpi"
            ssh.exec("test -f '$dpi' || echo 100 > '$dpi'")
            ssh.exec("rm -f '$RT_DIR/disabled'")
            if (ordered.any { it.target.endsWith("/iap.sh") }) {
                ssh.exec("rm -f '$RT_DIR/iap.off'")
                rep(
                    "Sound: Android Auto shows up under Multimedia as Apple device \"Android Auto\" (title, cover, " +
                        "skip). Plug the phone in by USB, then connect it by Bluetooth."
                )
            }
            val hasDecoder = ordered.any { it.target.endsWith("/nbt_decoder") }
            if (hasDecoder) {
                if (!hadDecoder) ssh.exec("touch '$RT_DIR/decoder.hw'")
                rep(
                    "Picture: 1280 pixels wide through the car's own hardware decoder. Switching to software is a " +
                        "\"Decoder\" button on the Android Auto / CarPlay page; it applies after an NBT restart."
                )
            }
            if (ordered.any { it.target.endsWith("/nbt_carplay") }) {
                rep("CarPlay: plug the iPhone in by USB and confirm \"Trust\".")
            }
            var gatewayNote: String? = null
            if (ordered.any { it.target.endsWith("/nbt_reset") }) {
                val gatewayFile = "$RT_DIR/gateway"
                val already = ssh.exec("test -s '$gatewayFile'").exit == 0
                if (!already) {
                    val gw = findGateway()
                    gatewayNote = if (gw != null) {
                        ssh.putAtomic("$gw\n".toByteArray(), gatewayFile, "644")
                        "Found and stored the car's gateway for \"Restart NBT\": $gw"
                    } else {
                        "The car's gateway was not found - \"Restart NBT\" on the head unit's page will not work " +
                            "until its address is set (the installer's own Gateway/Search can also do that)."
                    }
                }
            }
            ssh.exec("echo '${payload.manifest.version}' > '$RT_DIR/VERSION'")
            rep("Done: now restart iDrive, then Multimedia -> Android Auto / CarPlay.")
            Outcome(payload.manifest.version, ordered.size, gatewayNote)
        }
    }

    /** Removes exactly the two folders this package writes, after stopping its processes - nbt_aa_installer.py's
     * own uninstall(). Returns which of the two directories, if any, could not be removed (empty: both gone). */
    fun uninstall(host: String, password: String, rep: (String) -> Unit): List<String> {
        rep("Logging in to $host…")
        return SshSession(host, password).use { ssh ->
            rep("Logged in.")
            if (ssh.exec("test -d '/var/opt/hmi/data/rw'").exit != 0) {
                throw IOException("HMI data directory not found - this does not look like the original NBT.")
            }
            ssh.exec("test -d '$RT_DIR' && touch '$RT_DIR/disabled'")
            rep("Stopping the Android Auto service…")
            ssh.exec("slay -f -Q -s TERM native_service")
            ssh.exec("slay -f -Q -s TERM nbt_cluster")
            Thread.sleep(3000)
            for (name in listOf("nbt_aa_ipod", "nbt_ipod_tap", "nbt_a2i_play")) {
                ssh.exec("slay -f -Q -s KILL $name")
            }
            val notRemoved = ArrayList<String>()
            for (dir in listOf(PLUGIN_DIR, RT_DIR)) {
                ssh.exec("rm -r '$dir'")
                val still = ssh.exec("test -e '$dir'").exit == 0
                rep("  ${if (still) "NOT removed" else "removed"} $dir")
                if (still) notRemoved += dir
            }
            notRemoved
        }
    }
}
