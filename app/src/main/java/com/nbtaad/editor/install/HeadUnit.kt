package com.nbtaad.editor.install

import com.nbtaad.editor.contribute.HeadLog
import com.nbtaad.editor.contribute.LogFinder
import com.nbtaad.editor.contribute.LogSession
import com.nbtaad.editor.contribute.LogSessions
import com.nbtaad.editor.log.AppLog
import com.nbtaad.editor.net.SshCancelled
import com.nbtaad.editor.net.SshSession
import com.nbtaad.editor.update.UpdateLogic
import java.io.IOException

enum class Level { INFO, OK, WARN, ERROR }

/**
 * How the installer talks to the person while it works: lines of text, the progress of the uploads, and a note each time
 * a part ("daemon", "plugin") has really been replaced (so the app knows what the last install changed).
 */
class Reporter(
    val say: (String, Level) -> Unit,
    val progress: (label: String, done: Long, total: Long) -> Unit,
    val swapped: (String) -> Unit = {},
)

/** What an install did. */
class Outcome(
    val daemon: Boolean,
    val plugin: Boolean,
    val skipped: List<String>,
    val daemonVersion: String?,
    val problems: List<String>,
)

/**
 * Puts the files of a build on the head unit over the SSH login the app already has, the way Sq00ky's update
 * guide (updating.md) does it by hand: stop Android Auto, never write over a file that is in use (the new file is
 * uploaded under another name and checked, the old one is renamed to .old, then the new one takes its name), make the
 * plugin folder from the new files, and keep the old ones as the way back. SSH access itself is not part of this.
 *
 * The SSH login has only a few commands (see [RemoteScripts]), so the work is done with those and with what the
 * shell does itself; the files are read and written through the network paths of the other processor.
 */
object HeadUnit {
    /**
     * Where Android Auto is. [aa] is the folder of the program; [env] the folder of nbt-aad.env (the same, except for
     * NBTApps, which keeps the file in a data folder of its own); [nbtApps]: NBTApps put it there, not a person's hands.
     */
    class Layout(
        val aa: String, val plugin: String?, val nbtApps: Boolean = false, val env: String = aa, val va: String? = null,
        /** A manual install's folder that holds nbt-aad. False for a folder left behind (after an uninstall), which an NBTApps install can sit next to. */
        val daemonHere: Boolean = true,
        /** [aa] and [env] are on hu-omap and this login does not see them (see [RemoteScripts.hopPart]): nothing is read or written there from here. */
        val viaHop: Boolean = false,
        /** The Plugins folder iDrive loads plugins from, when it is there (also when the NbtAad folder in it is not: an uninstall removes that). */
        val pluginsParent: String? = null,
    ) {
        /** Where the plugin goes: its folder, or the place for a new one when only the Plugins folder is there. */
        val pluginTarget: String? get() = plugin ?: pluginsParent?.let { "${it.trimEnd('/')}/NbtAad" }
    }

    /** Why this app does not install over an NBTApps install. */
    const val NBTAPPS_NOTE =
        "Android Auto on this head unit was installed with NBTApps. Update or remove it there: an install from here " +
            "would put a second copy in /opt/aa, which NBTApps removes again. Settings, logs and the version check work here."

    /** NBTApps was found through hu-omap, and this login cannot read or write its folders. */
    const val NBTAPPS_OTHER_NODE_NOTE =
        "Android Auto on this head unit was installed with NBTApps. Its program and its nbt-aad.env are on the head unit's " +
            "other processor (hu-omap), which this connection (the first address of the head unit) cannot read. " +
            "Update, remove and change its settings in NBTApps."

    /**
     * Which layout the folders say. /opt/aa wins when the daemon is in it; when it is only an empty or left-over folder
     * (an uninstall leaves it) and NBTApps has its package, the install is NBTApps'.
     */
    internal fun layoutOf(out: String): Layout? {
        val loc = RemoteScripts.parseLocated(out)
        return when {
            loc.aa != null && (loc.aaDaemon || loc.nbtApp == null) -> Layout(loc.aa, loc.plugin, va = loc.va, daemonHere = loc.aaDaemon, pluginsParent = loc.pluginsParent)
            loc.nbtApp != null -> Layout(loc.nbtApp, loc.plugin, nbtApps = true, env = loc.nbtData ?: loc.nbtApp, viaHop = loc.viaHop, pluginsParent = loc.pluginsParent)
            else -> null
        }
    }

    /** The folder nbt-aad.env is in: where the daemon of this install reads it (null: no install found). */
    fun envFolder(ssh: SshSession): String? {
        val lay = layoutOf(ssh.exec(RemoteScripts.locate()).text) ?: return null
        // a folder on hu-omap that this login cannot reach: reading or writing there would go to a path that is not there
        if (lay.viaHop) throw IOException(NBTAPPS_OTHER_NODE_NOTE)
        return lay.env
    }

    private fun timeoutFor(bytes: Int): Long = 60_000L + bytes / 8

    /** "1.2 s" since the work began: each step of the log carries it, so a slow step can be seen. */
    private class Clock {
        private val start = System.nanoTime()
        private var lap = start
        fun total(): String = fmt(System.nanoTime() - start)

        /** The time since the last call, for one step. */
        fun step(): String {
            val now = System.nanoTime()
            val d = now - lap
            lap = now
            return fmt(d)
        }

        private fun fmt(ns: Long) = String.format(java.util.Locale.US, "%.1f s", ns / 1e9)
    }

    // No `| head` here - a Tools Check run against this exact QNX image (2026-11-23) confirmed it has
    // no `head` binary at all, so piping to it would silently lose this diagnostic's output right when
    // it matters most. `.take(900)` below is the only truncation, same overall cap as before.
    private fun diagnoseFolders(ssh: SshSession): String =
        ssh.exec(
            "echo '/opt:'; ls /opt 2>&1; echo '/net:'; ls /net 2>&1; " +
                "echo 'hu-omap opt:'; ls /net/hu-omap/fs/sda0/opt 2>&1; " +
                "echo 'nbtapp:'; ls /var/opt/nbtapp /var/opt/nbtapp/apps /var/opt/nbtapp/data 2>&1; " +
                "echo 'hu-omap nbtapp:'; ls /net/hu-omap/var/opt/nbtapp /net/hu-omap/var/opt/nbtapp/apps 2>&1"
        ).text.trim().take(900)

    /** Android Auto is on the head unit, but not where this app puts it (/opt/aa) or where NBTApps does. */
    class UnfamiliarLayout(val found: RemoteScripts.ForeignInstall) : IOException(unfamiliarNote(found))

    /** /opt/aa has no daemon, and the one that runs is somewhere else: the folders alone do not say where Android Auto is. */
    private fun stray(ssh: SshSession, lay: Layout, probeText: String): RemoteScripts.ForeignInstall? {
        if (lay.nbtApps || lay.daemonHere) return null
        val scan = RemoteScripts.parseForeign(probeText)
        val other = scan.exes.firstOrNull { !RemoteScripts.sameFolder(it.path.substringBeforeLast('/', ""), lay.aa) } ?: return null
        return foreignFrom(ssh, scan, other)
    }

    /** The words to type before this app removes Android Auto from a layout it does not know. */
    const val SURE = "I am sure"

    fun isSure(typed: String): Boolean =
        typed.trim().split(Regex("\\s+")).joinToString(" ").equals(SURE, ignoreCase = true)

    fun unfamiliarNote(f: RemoteScripts.ForeignInstall): String = if (f.byNbtApps) NBTAPPS_NOTE else buildString {
        append("Android Auto is installed here in a layout this app does not know")
        if (f.plugin != null) append(": the plugin is in ${f.plugin}")
        if (f.seen != null) append(if (f.plugin != null) ", and the daemon runs from ${f.seen}" else ": the daemon runs from ${f.seen}")
        append(". For safety this app does not manage such installs: no install, no settings, no Go back. ")
        append("Remove it with the tool that installed it last, and then use this app.")
        if (f.manageable) {
            append(" The Uninstall button below can still remove it, after you type \"$SURE\".")
        } else {
            append(" The daemon is not running, so the app cannot see where it lives and cannot remove it either.")
        }
    }

    private fun notFound(ssh: SshSession, probeText: String? = null): IOException {
        // Android Auto may be there, in another place: say so instead of "not installed"
        val text = probeText ?: try { ssh.exec(RemoteScripts.probe(), timeoutMs = 60_000).text } catch (e: SshCancelled) { throw e } catch (e: Exception) { "" }
        foreignOf(ssh, text)?.let { return UnfamiliarLayout(it) }
        return IOException(
            "The NBT-AAD folder (/opt/aa, or the folders of NBTApps in /var/opt/nbtapp) was not found on the head unit. " +
                "Is NBT-AAD installed?\n" +
                "What the SSH shell can see:\n" + diagnoseFolders(ssh)
        )
    }

    /** What there is of Android Auto when none of the known layouts is there, or null when nothing. */
    private fun foreignOf(ssh: SshSession, probeText: String): RemoteScripts.ForeignInstall? {
        val scan = RemoteScripts.parseForeign(probeText)
        if (scan.plugin == null && scan.exes.isEmpty()) return null
        return foreignFrom(ssh, scan, scan.exes.firstOrNull())
    }

    private fun foreignFrom(ssh: SshSession, scan: RemoteScripts.ForeignScan, first: RemoteScripts.SeenExe?): RemoteScripts.ForeignInstall {
        val file = first?.let { e ->
            val cands = RemoteScripts.exeCandidates(e)
            if (cands.isEmpty()) null
            else try { RemoteScripts.parseExeFile(ssh.exec(RemoteScripts.resolveExe(cands), timeoutMs = 30_000).text) } catch (x: SshCancelled) { throw x } catch (x: Exception) { null }
        }
        return RemoteScripts.ForeignInstall(scan.plugin, first?.path, file)
    }

    private fun locate(ssh: SshSession): Layout {
        val text = ssh.exec(RemoteScripts.locate()).text
        return layoutOf(text) ?: throw notFound(ssh)
    }

    /** The first look at the head unit, in one command: where the folders are, and whether Android Auto runs. */
    private class Probe(val layout: Layout, val scan: ProcScan)

    private fun probe(ssh: SshSession): Probe {
        val text = ssh.exec(RemoteScripts.probe(), timeoutMs = 60_000).text
        val lay = layoutOf(text) ?: throw notFound(ssh, text)
        stray(ssh, lay, text)?.let { throw UnfamiliarLayout(it) }
        return Probe(lay, RemoteScripts.parseProc(text))
    }

    private fun readStatus(ssh: SshSession, lay: Layout, withEnv: Boolean = false): UnitStatus =
        RemoteScripts.parseStatus(
            lay.aa, lay.plugin,
            // not asked for what hu-omap keeps in /var: this login would answer "no such file", which is not the truth
            ssh.exec(RemoteScripts.status(lay.aa, lay.plugin, withEnv && !lay.viaHop, lay.env), timeoutMs = 60_000).text,
            nbtApps = lay.nbtApps,
            otherNode = lay.viaHop,
        )

    /** The wide look for the log (see [RemoteScripts.deepLook]); never allowed to spoil the status. */
    private fun deepLook(ssh: SshSession) {
        try {
            val text = ssh.exec(RemoteScripts.deepLook(), timeoutMs = 45_000).text
            AppLog.d("Unit", "Deep look (no nbt-aad where this login sees it):\n" + text.trim().take(8000))
        } catch (e: SshCancelled) {
            throw e
        } catch (e: Exception) {
            AppLog.w("Unit", "Deep look failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * The installed nbt-aad read back with `cat` and looked at here (size, SHA-256, the version named inside it), or
     * null when that did not work. The head unit's own tools cannot be counted on for this: its login has no `wc`, and
     * its `grep` may not know the options the version search needs. Whatever goes wrong here must not spoil the rest of
     * the status, so it is logged and left at that.
     */
    private fun readDaemon(ssh: SshSession, lay: Layout): DaemonFile? = try {
        val r = ssh.exec("cat ${lay.aa}/${Names.DAEMON}", timeoutMs = 120_000)
        if (r.exit != 0 || r.out.isEmpty()) {
            AppLog.w("Unit", "Reading nbt-aad back failed: exit ${r.exit}, ${r.out.size} bytes${r.err.trim().takeIf { it.isNotEmpty() }?.let { ", $it" } ?: ""}")
            null
        } else {
            DaemonFile(r.out.size.toLong(), UpdateLogic.sha256Hex(r.out), BuildPackage.findVersion(r.out))
        }
    } catch (e: Exception) {
        AppLog.w("Unit", "Reading nbt-aad back failed: ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    /**
     * What is on the head unit now (read only): the folders and files, the version where the head unit's own tools can
     * find it, and the text of nbt-aad.env. This takes a moment; the installed file itself is read only on request
     * ([readInstalledFile]), as that can take a minute.
     */
    fun status(host: String): UnitStatus =
        SshSession(host).use { ssh ->
            val lay = locate(ssh)
            // no nbt-aad in the folder: if one runs from somewhere else, that is where Android Auto is
            if (!lay.nbtApps && !lay.daemonHere) {
                stray(ssh, lay, ssh.exec(RemoteScripts.probe(), timeoutMs = 60_000).text)?.let { throw UnfamiliarLayout(it) }
            }
            val s = readStatus(ssh, lay, withEnv = true)
            if (!s.hasDaemon) deepLook(ssh)
            s
        }

    /**
     * The installed nbt-aad read back whole and hashed, for its version and to compare with the official releases (null:
     * it could not be read). The head unit's disk is slow to read from here: about 30 seconds for the 1 MB file on the
     * first car this was tried on.
     */
    fun readInstalledFile(host: String): DaemonFile? =
        SshSession(host).use { ssh -> readDaemon(ssh, locate(ssh)) }

    // ---- logs for the project (the CONTRIBUTE page) ----

    /** What reading one file gave: its bytes, or what went wrong (then [bytes] is null). */
    class FileRead(val name: String, val bytes: ByteArray?, val problem: String?)

    /** The daemon's log files on the head unit, with sizes (read only, one quick command). */
    fun findLogs(host: String): List<HeadLog> =
        SshSession(host).use { ssh ->
            val logs = LogFinder.parse(ssh.exec(RemoteScripts.findLogs(), timeoutMs = 30_000).text)
            AppLog.i("Contribute", "Logs on the head unit: " + logs.joinToString { "${it.path} (${it.size})" }.ifEmpty { "none" })
            logs
        }

    private val SAFE_PATH = Regex("^/[A-Za-z0-9._/@+-]+$")

    /**
     * The sessions in a log on the USB stick (the daemon appends one after the other, each with a header). The head unit
     * reads through the whole file for this, which the stick does slowly, but only a few lines come back. Empty when the
     * file has no headers; throws when it cannot be looked at.
     */
    fun findSessions(host: String, log: HeadLog, cancelled: () -> Boolean): List<LogSession> {
        if (!SAFE_PATH.matches(log.path)) throw IOException("The file name has characters the app will not pass on.")
        return SshSession(host).use { ssh ->
            val t0 = System.currentTimeMillis()
            val r = ssh.exec(RemoteScripts.sessions(log.path), timeoutMs = 600_000, cancelled = cancelled)
            val text = r.text
            if (text.lineSequence().any { it.trim() == "NOFILE" }) throw IOException("The file is not on the head unit any more.")
            val list = LogSessions.parse(text, log.size)
            AppLog.i(
                "Contribute",
                "Sessions in ${log.path}: ${list.size} (" + list.joinToString { "#${it.number} line ${it.line}, ${it.size} B" } +
                    ") in ${(System.currentTimeMillis() - t0) / 1000} s; options: " +
                    (text.lineSequence().firstOrNull { it.startsWith("OPTIONS") }?.removePrefix("OPTIONS")?.trim() ?: "?"),
            )
            list
        }
    }

    /** Deletes the daemon's log files on the head unit. Returns what could not be deleted, as "name: why" (empty: all gone). */
    fun clearLogs(host: String, logs: List<HeadLog>): List<String> =
        SshSession(host).use { ssh ->
            val problems = ArrayList<String>()
            val safe = ArrayList<String>()
            for (l in logs) {
                if (SAFE_PATH.matches(l.path)) safe += l.path
                else problems += "${l.name}: the file name has characters the app will not pass on"
            }
            if (safe.isNotEmpty()) {
                val answer = ssh.exec(RemoteScripts.removeFiles(safe), timeoutMs = 60_000).text.lines().map { it.trim() }
                for (line in answer) {
                    if (line.startsWith("FAIL ")) problems += "${line.removePrefix("FAIL ").substringAfterLast('/')}: the head unit would not delete it"
                }
                AppLog.i("Contribute", "Deleted logs: ${answer.count { it.startsWith("OK ") }}, already gone: ${answer.count { it.startsWith("GONE ") }}, failed: ${answer.count { it.startsWith("FAIL ") }}")
            }
            problems
        }

    /**
     * Reads the chosen logs, and with [withEnv] nbt-aad.env, one after the other over one connection (`cat` is what the
     * head unit has; it is slow, about 30 KB/s on the first car this was tried on, so [onProgress] says which file and how
     * far, and [cancelled] stops it). A file that cannot be read is told in its result and the others go on.
     */
    fun readLogs(
        host: String,
        logs: List<HeadLog>,
        withEnv: Boolean,
        onProgress: (name: String, done: Long, total: Long) -> Unit,
        cancelled: () -> Boolean,
        /** For a log (by path) that is in here only these sessions are read, each as a file of its own. */
        sessions: Map<String, List<LogSession>> = emptyMap(),
    ): List<FileRead> =
        SshSession(host).use { ssh ->
            val out = ArrayList<FileRead>()
            fun read(name: String, path: String, size: Long): FileRead {
                if (!SAFE_PATH.matches(path)) return FileRead(name, null, "the file name has characters the app will not pass on")
                onProgress(name, 0, size)
                val t0 = System.currentTimeMillis()
                return try {
                    val r = ssh.exec(
                        "cat $path",
                        timeoutMs = if (size > 0) 120_000L + size / 8 else 900_000L,
                        onOut = { onProgress(name, it, size) },
                        cancelled = cancelled,
                    )
                    if (r.exit != 0) {
                        FileRead(name, null, r.err.trim().ifEmpty { "the head unit answered with ${r.exit}" })
                    } else {
                        AppLog.i("Contribute", "Read $path: ${r.out.size} bytes in ${(System.currentTimeMillis() - t0) / 1000} s")
                        FileRead(name, r.out, null)
                    }
                } catch (e: SshCancelled) {
                    throw e
                } catch (e: Exception) {
                    AppLog.w("Contribute", "Reading $path failed: ${e.javaClass.simpleName}: ${e.message}")
                    FileRead(name, null, e.message ?: e.javaClass.simpleName)
                }
            }
            if (withEnv) {
                val lay = try { locate(ssh) } catch (e: SshCancelled) { throw e } catch (e: Exception) { null }
                out += if (lay == null) {
                    FileRead("nbt-aad.env", null, "the NBT-AAD folder was not found")
                } else if (lay.viaHop) {
                    FileRead("nbt-aad.env", null, NBTAPPS_OTHER_NODE_NOTE)
                } else {
                    read("nbt-aad.env", "${lay.env}/nbt-aad.env", -1)
                }
            }
            /** One session cut out of a log: with dd where the head unit has it, else with read and cat; each is checked to start with the header. */
            fun readSession(l: HeadLog, s: LogSession): FileRead {
                val name = LogSessions.fileName(s.number)
                if (!SAFE_PATH.matches(l.path)) return FileRead(name, null, "the file name has characters the app will not pass on")
                // the newest session may still grow: it is read to the end of the file
                val length = if (s.last) -1L else s.size
                val total = if (s.size > 0) s.size else -1L
                val t0 = System.currentTimeMillis()
                onProgress(name, 0, total)
                val ways = if (s.offset >= 0) listOf(true, false) else listOf(false)
                for (viaDd in ways) {
                    val way = if (viaDd) "dd" else "read"
                    try {
                        val lead = if (viaDd) (s.offset % RemoteScripts.DD_BLOCK).toInt() else 0
                        val cmd = if (viaDd) RemoteScripts.sessionDd(l.path, s.offset, length) else RemoteScripts.sessionRead(l.path, s.line)
                        val r = ssh.exec(
                            cmd,
                            timeoutMs = if (length > 0) 120_000L + length / 8 else 900_000L,
                            onOut = { onProgress(name, (it - lead).coerceAtLeast(0), total) },
                            cancelled = cancelled,
                            maxBytes = if (length > 0) lead + length else -1L,
                        )
                        var bytes = LogSessions.trim(r.out, lead, length)
                        if (bytes == null) {
                            AppLog.w("Contribute", "Session ${s.number} via $way: not the session (exit ${r.exit}, ${r.out.size} B${r.err.trim().takeIf { it.isNotEmpty() }?.let { ", $it" } ?: ""})")
                            continue
                        }
                        if (!s.last && s.size < 0) bytes = LogSessions.cutAtNextHeader(bytes)
                        AppLog.i("Contribute", "Read session ${s.number} of ${l.path} via $way: ${bytes.size} bytes in ${(System.currentTimeMillis() - t0) / 1000} s")
                        return FileRead(name, bytes, null)
                    } catch (e: SshCancelled) {
                        throw e
                    } catch (e: Exception) {
                        AppLog.w("Contribute", "Reading session ${s.number} via $way failed: ${e.javaClass.simpleName}: ${e.message}")
                    }
                }
                return FileRead(name, null, "the head unit could not cut this session out of the log (read the whole log instead)")
            }
            for (l in logs) {
                val picked = sessions[l.path].orEmpty()
                if (picked.isEmpty()) out += read(l.name, l.path, l.size) else for (s in picked) out += readSession(l, s)
            }
            out
        }

    /** The commands and folders the SSH login has, as text for the person to read or send on. */
    fun diagnose(host: String): String =
        SshSession(host).use { ssh ->
            val lay = try { locate(ssh) } catch (e: Exception) { null }
            val r = ssh.exec(RemoteScripts.diagnose(), timeoutMs = 40_000)
            buildString {
                appendLine("NBT-AAD folder: ${lay?.aa ?: "not found"}${if (lay?.nbtApps == true) " (installed by NBTApps; nbt-aad.env in ${lay.env})" else ""}")
                appendLine("Plugin folder: ${lay?.plugin ?: "not found"}")
                append(r.text.trim())
                if (r.err.isNotBlank()) append("\n[errors]\n" + r.err.trim())
            }
        }

    // ---- stopping Android Auto, as the guide's step 2 ----

    private fun scan(ssh: SshSession): ProcScan =
        RemoteScripts.parseProc(try { ssh.exec(RemoteScripts.procScan(), timeoutMs = 30_000).text } catch (e: Exception) { "" })

    private fun stopDaemon(ssh: SshSession, rep: Reporter, first: ProcScan) {
        var p = first
        if (!p.scanned) {
            rep.say(
                "The app cannot see the head unit's process list, so it cannot tell whether Android Auto runs. " +
                    "If it does, press Force Stop (Media/Radio, Android Auto) and run this again.",
                Level.WARN,
            )
            return
        }
        if (!p.any) {
            rep.say("Android Auto is not running.", Level.OK)
            return
        }
        rep.say("Android Auto is still running. Stopping it…", Level.INFO)
        for (pid in p.here) try { ssh.exec(RemoteScripts.kill(pid), timeoutMs = 10_000) } catch (e: Exception) { /* checked below */ }
        for (pid in p.onHuOmap) try { ssh.exec(RemoteScripts.killOnHuOmap(pid), timeoutMs = 15_000) } catch (e: Exception) { /* checked below */ }
        Thread.sleep(2000)
        p = scan(ssh)
        if (p.any) {
            throw IOException(
                "Android Auto is still running. On the head unit open Media/Radio, Android Auto and press " +
                    "Force Stop, then try again."
            )
        }
        rep.say("Android Auto is stopped.", Level.OK)
    }


    // ---- uploading ----

    /** Sends the bytes to [path]. What arrived is checked once for all files afterwards (see [checkUpload]). */
    private fun upload(ssh: SshSession, path: String, p: Payload, done: Long, total: Long, rep: Reporter, clock: Clock) {
        rep.progress(p.name, done, total)
        var last = 0L
        val r = ssh.exec("cat > $path", stdin = p.bytes, timeoutMs = timeoutFor(p.size)) { sent ->
            if (sent - last >= 64 * 1024 || sent == p.size.toLong()) {
                last = sent
                rep.progress(p.name, done + sent, total)
            }
        }
        if (r.exit != 0) {
            throw IOException(
                "Uploading ${p.name} failed: ${r.err.trim().ifEmpty { "exit ${r.exit}" }}. " +
                    "Is there enough free space on the head unit?"
            )
        }
        rep.progress(p.name, done + p.size, total)
        rep.say("Uploaded ${p.name} (${ReleaseLogic.mb(p.size.toLong())}, ${clock.step()}).", Level.INFO)
    }

    /** The slow, sure way to prove a file arrived: read it back and compare. Only used for what `ls` could not confirm. */
    private fun readBack(ssh: SshSession, path: String, p: Payload) {
        val back = ssh.exec("cat $path", timeoutMs = timeoutFor(p.size))
        if (!back.out.contentEquals(p.bytes)) {
            throw IOException("${p.name} on the head unit differs from what was sent. Nothing was replaced.")
        }
    }

    /**
     * One command asks the head unit whether the new daemon can be run and how big each uploaded file is. The upload
     * itself is protected by SSH, so a size that matches proves nothing was cut off; a file whose size the head unit
     * cannot confirm is read back and compared.
     */
    private fun checkUpload(
        ssh: SshSession, lay: Layout, daemon: Payload?, plugin: List<Payload>, rep: Reporter, clock: Clock,
    ) {
        val r = ssh.exec(RemoteScripts.check(lay.aa, daemon != null, plugin.map { it.name }), timeoutMs = 60_000)
        if (r.exit != 0) throw IOException("Checking the uploaded files failed: ${RemoteScripts.exitMeaning(r.exit)}.")
        val c = RemoteScripts.parseCheck(r.text)
        if (daemon != null) {
            when (c.exec) {
                0 -> {}
                14 -> throw IOException(
                    "The new nbt-aad is uploaded but cannot be made executable on the head unit: its SSH login has " +
                        "no chmod the app can use. Nothing was replaced."
                )
                15 -> throw IOException("The uploaded nbt-aad is not on the head unit. Nothing was replaced.")
                else -> throw IOException("The head unit did not say whether the new nbt-aad can be run. Nothing was replaced.")
            }
        }
        var readBacks = 0
        val items = listOfNotNull(daemon?.let { Triple("nbt-aad.new", "${lay.aa}/nbt-aad.new", it) }) +
            plugin.map { Triple(it.name, "${lay.aa}/NbtAad.new/${it.name}", it) }
        for ((listedName, path, p) in items) {
            if (c.sizeIs(listedName, p.size.toLong())) continue
            readBacks++
            readBack(ssh, path, p)
        }
        rep.say(
            (if (daemon != null) "nbt-aad can be run. " else "") +
                (if (readBacks == 0) "All files arrived complete" else "All files arrived complete ($readBacks read back)") +
                " (${clock.step()}).",
            Level.OK,
        )
    }

    private fun stepFailure(what: String, code: Int, err: String): IOException {
        val why = RemoteScripts.exitMeaning(code)
        val extra = err.trim().takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""
        return IOException("$what failed: $why$extra.")
    }

    // ---- install ----

    fun install(host: String, pkg: BuildPackage, rep: Reporter): Outcome {
        if (!pkg.installable) throw IOException("These files cannot be installed: ${pkg.problems.firstOrNull() ?: "nothing to install"}")
        val daemon = pkg.payloads[Names.DAEMON]
        val plugin = pkg.pluginFiles.map { pkg.payloads.getValue(it) }
        val skipped = pkg.found.filter { it == Names.FIREWALL }
        val total = (daemon?.size ?: 0).toLong() + plugin.sumOf { it.size.toLong() }
        val clock = Clock()

        rep.say("Logging in to $host…", Level.INFO)
        return SshSession(host).use { ssh ->
            rep.say("Logged in (${clock.step()}).", Level.INFO)
            val pr = probe(ssh)
            val lay = pr.layout
            if (lay.nbtApps) throw IOException(NBTAPPS_NOTE)
            rep.say("NBT-AAD folder: ${lay.aa}", Level.INFO)
            // The NbtAad folder is gone after an uninstall (or on a clean head unit): a full install makes it again in the
            // Plugins folder, which stays. Part of a plugin cannot make a folder, and without a Plugins folder there is no place.
            val pl: String? = if (plugin.isEmpty()) lay.plugin else {
                val target = lay.pluginTarget ?: throw IOException(
                    "The Plugins folder (…/ID5/data/ro/Plugins) was not found on the head unit, so the plugin cannot be " +
                        "installed. Nothing was changed."
                )
                if (lay.plugin == null) {
                    if (!pkg.fullPlugin) throw IOException(
                        "The plugin folder (…/Plugins/NbtAad) is not on the head unit and these files are only part of the " +
                            "plugin. Install a full release first. Nothing was changed."
                    )
                    rep.say("Plugin folder: $target (not there: it will be made)", Level.INFO)
                } else {
                    rep.say("Plugin folder: $target", Level.INFO)
                }
                target
            }
            stopDaemon(ssh, rep, pr.scan)
            clock.step()

            // Everything is uploaded and checked first; nothing on the head unit changes until all of it is there.
            try {
                val prep = ssh.exec(
                    RemoteScripts.prepare(lay.aa, pl, daemon != null, plugin.isNotEmpty(), keepOld = !pkg.fullPlugin),
                    timeoutMs = 90_000,
                )
                if (prep.exit != 0) throw stepFailure("Preparing the head unit", prep.exit, prep.err)
                if (RemoteScripts.remountFailed(prep.text)) {
                    rep.say("The partition could not be made writable (continuing).", Level.WARN)
                }
                rep.say("Head unit prepared (${clock.step()}).", Level.INFO)

                var done = 0L
                if (daemon != null) {
                    upload(ssh, "${lay.aa}/nbt-aad.new", daemon, done, total, rep, clock)
                    done += daemon.size
                }
                for (p in plugin) {
                    upload(ssh, "${lay.aa}/NbtAad.new/${p.name}", p, done, total, rep, clock)
                    done += p.size
                }
                checkUpload(ssh, lay, daemon, plugin, rep, clock)
            } catch (e: Exception) {
                runCatching { ssh.exec(RemoteScripts.cleanStaging(lay.aa)) }
                throw e
            }

            // the replacing and the look at the result are one command
            val ops = listOfNotNull(
                if (daemon != null) RemoteScripts.Op.SWAP_DAEMON else null,
                if (plugin.isNotEmpty()) RemoteScripts.Op.SWAP_PLUGIN else null,
            )
            val r = ssh.exec(RemoteScripts.apply(lay.aa, pl, ops, remount = false), timeoutMs = 120_000)
            val (daemonDone, pluginDone) = RemoteScripts.parseDone(r.text)
            if (daemonDone) rep.swapped("daemon")
            if (pluginDone) rep.swapped("plugin")
            if (r.exit != 0) {
                runCatching { ssh.exec(RemoteScripts.cleanStaging(lay.aa)) }
                val e = stepFailure(if (daemonDone || daemon == null) "Replacing the plugin" else "Replacing nbt-aad", r.exit, r.err)
                if (daemonDone) {
                    throw IOException(
                        "${e.message} The new nbt-aad is already in place, the old plugin is still there. " +
                            "Use \"Go back to the previous version\" (daemon only) to undo it, or install again."
                    )
                }
                throw e
            }
            rep.say(
                "Replaced " + listOfNotNull(if (daemonDone) "nbt-aad" else null, if (pluginDone) "the plugin" else null)
                    .joinToString(" and ") + ": the old files are kept as .old (${clock.step()}).",
                Level.OK,
            )

            // what the head unit holds now, as the same command printed it
            val problems = ArrayList<String>()
            val st = RemoteScripts.parseStatus(lay.aa, pl, r.text)
            if (daemon != null) {
                val f = st.files[Names.DAEMON]
                when {
                    f == null -> problems += "nbt-aad is not in the folder after the install."
                    !f.executable -> problems += "nbt-aad is not executable after the install."
                    f.size >= 0 && f.size != daemon.size.toLong() ->
                        problems += "nbt-aad on the head unit has ${f.size} bytes, expected ${daemon.size}."
                }
            }
            for (p in plugin) {
                val sz = st.pluginFiles[p.name]
                when {
                    sz == null -> problems += "${p.name} is not in the plugin folder after the install."
                    sz >= 0 && sz != p.size.toLong() ->
                        problems += "${p.name} in the plugin folder has $sz bytes, expected ${p.size}."
                }
            }
            for (m in problems) rep.say(m, Level.WARN)
            if (skipped.isNotEmpty()) {
                rep.say(
                    "pf_aa was not installed: the firewall rules are built into nbt-aad now and are set up once from the " +
                        "iDrive menu.",
                    Level.INFO,
                )
            }
            rep.say("Done in ${clock.total()}.", Level.INFO)
            Outcome(daemonDone, pluginDone, skipped, daemon?.let { BuildPackage.findVersion(it.bytes) } ?: st.version, problems)
        }
    }

    // ---- uninstall (a hand install, the one this app makes) ----

    /** Why this app does not remove an NBTApps install. */
    const val NBTAPPS_UNINSTALL_NOTE =
        "Android Auto on this head unit was installed with NBTApps. Remove it in NBTApps: it keeps records of what it " +
            "installed, and deleting its files from outside would leave it confused. This app removes what it installs " +
            "itself (Sq00ky's release in /opt/aa)."

    private fun planOf(ssh: SshSession, lay: Layout): RemoteScripts.UninstallPlan =
        RemoteScripts.parseUninstall(
            ssh.exec(RemoteScripts.uninstall(lay.aa, lay.plugin, lay.va, keepEnv = false, apply = false), timeoutMs = 60_000).text
        )

    /** What the first look found: a layout this app knows, or something of Android Auto that it does not. */
    private sealed class Found {
        class Known(val probe: Probe) : Found()
        class Foreign(val install: RemoteScripts.ForeignInstall, val scan: ProcScan) : Found()
    }

    private fun look(ssh: SshSession): Found {
        val text = ssh.exec(RemoteScripts.probe(), timeoutMs = 60_000).text
        val lay = layoutOf(text)
        if (lay != null) {
            stray(ssh, lay, text)?.let { return Found.Foreign(it, RemoteScripts.parseProc(text)) }
            return Found.Known(Probe(lay, RemoteScripts.parseProc(text)))
        }
        val f = foreignOf(ssh, text) ?: throw notFound(ssh, text)
        return Found.Foreign(f, RemoteScripts.parseProc(text))
    }

    private fun planOfForeign(ssh: SshSession, f: RemoteScripts.ForeignInstall): RemoteScripts.UninstallPlan =
        RemoteScripts.parseUninstall(
            ssh.exec(
                RemoteScripts.uninstall(f.dir!!, f.plugin, null, keepEnv = false, apply = false, foreignExe = f.exe),
                timeoutMs = 60_000,
            ).text,
            f,
        )

    /** What an uninstall would do, without doing anything. For a layout this app does not know the plan says so ([UninstallPlan.foreign]). */
    fun uninstallPlan(host: String): RemoteScripts.UninstallPlan =
        SshSession(host).use { ssh ->
            val plan = when (val found = look(ssh)) {
                is Found.Known -> {
                    if (found.probe.layout.nbtApps) throw IOException(NBTAPPS_UNINSTALL_NOTE)
                    planOf(ssh, found.probe.layout)
                }
                is Found.Foreign -> {
                    if (found.install.byNbtApps) throw IOException(NBTAPPS_UNINSTALL_NOTE)
                    if (!found.install.manageable) throw UnfamiliarLayout(found.install)
                    planOfForeign(ssh, found.install)
                }
            }
            if (plan.failed != null) throw IOException("Looking at the head unit failed (${plan.failed}).")
            plan
        }

    /** What an uninstall did and what is still there afterwards that should not be. */
    class Removal(val plan: RemoteScripts.UninstallPlan, val problems: List<String>)

    /**
     * Removes Android Auto. For a layout this app does not know, [confirmed] must be what the person was shown and typed
     * "I am sure" for; if the head unit looks different now, nothing is done.
     */
    fun uninstall(host: String, rep: Reporter, keepEnv: Boolean, confirmed: RemoteScripts.ForeignInstall? = null): Removal {
        val clock = Clock()
        rep.say("Logging in to $host…", Level.INFO)
        return SshSession(host).use { ssh ->
            rep.say("Logged in (${clock.step()}).", Level.INFO)
            val changed = IOException("The head unit looks different from when the list was made. Nothing was removed; open Uninstall again.")
            val found = look(ssh)
            val script: (Boolean) -> String
            val scan: ProcScan
            val foreign: RemoteScripts.ForeignInstall?
            when (found) {
                is Found.Known -> {
                    val lay = found.probe.layout
                    if (lay.nbtApps) throw IOException(NBTAPPS_UNINSTALL_NOTE)
                    if (confirmed != null) throw changed
                    rep.say("NBT-AAD folder: ${lay.aa}", Level.INFO)
                    script = { apply -> RemoteScripts.uninstall(lay.aa, lay.plugin, lay.va, keepEnv, apply) }
                    scan = found.probe.scan
                    foreign = null
                }
                is Found.Foreign -> {
                    val f = found.install
                    if (f.byNbtApps) throw IOException(NBTAPPS_UNINSTALL_NOTE)
                    if (confirmed == null) throw UnfamiliarLayout(f)
                    if (!f.manageable || !f.same(confirmed)) throw changed
                    rep.say("Unfamiliar layout, confirmed with \"$SURE\": the daemon is ${f.file}, the plugin ${f.plugin ?: "not there"}.", Level.WARN)
                    script = { apply -> RemoteScripts.uninstall(f.dir!!, f.plugin, null, keepEnv, apply, f.exe) }
                    scan = found.scan
                    foreign = f
                }
            }
            stopDaemon(ssh, rep, scan)
            clock.step()
            val r = ssh.exec(script(true), timeoutMs = 120_000)
            val plan = RemoteScripts.parseUninstall(r.text, foreign)
            if (r.exit != 0 || !plan.done || plan.failed != null) throw stepFailure("Removing Android Auto", r.exit, r.err)
            if (plan.remountFailed) rep.say("The partition could not be made writable (continuing).", Level.WARN)
            for ((from, to) in plan.moves) rep.say("Moved aside: $from -> $to", Level.INFO)
            rep.say("Removed ${plan.removes.size + plan.envFiles.size} files and folders (${clock.step()}).", Level.INFO)
            for (k in plan.kept) rep.say("Kept: $k", Level.INFO)
            for (l in plan.left) rep.say("Left alone (not part of NBT-AAD): $l", Level.WARN)
            // look again: what a second uninstall would still remove was not removed
            val again = try { RemoteScripts.parseUninstall(ssh.exec(script(false), timeoutMs = 60_000).text, foreign) } catch (e: SshCancelled) { throw e } catch (e: Exception) { null }
            val problems = ArrayList<String>()
            again?.let { a ->
                val rest = a.removes.filter { !it.endsWith("/NbtAad.old") } + (if (keepEnv) emptyList() else a.envFiles)
                if (rest.isNotEmpty()) problems += "Still on the head unit: ${rest.joinToString(", ")}."
            }
            for (m in problems) rep.say(m, Level.WARN)
            rep.say("Done in ${clock.total()}.", Level.INFO)
            Removal(plan, problems)
        }
    }

    // ---- the way back ----

    /**
     * Swaps the current version with the one the last install moved aside (doing it again swaps back). The daemon and
     * the plugin are chosen separately: a failed install may have changed only one of them.
     */
    fun revert(host: String, rep: Reporter, daemon: Boolean, plugin: Boolean): Outcome {
        val clock = Clock()
        rep.say("Logging in to $host…", Level.INFO)
        return SshSession(host).use { ssh ->
            rep.say("Logged in (${clock.step()}).", Level.INFO)
            val pr = probe(ssh)
            val lay = pr.layout
            if (lay.nbtApps) throw IOException(NBTAPPS_NOTE)
            val before = readStatus(ssh, lay)
            val doDaemon = daemon && before.hasPreviousDaemon
            val doPlugin = plugin && before.hasOldPlugin && lay.plugin != null
            if (!doDaemon && !doPlugin) {
                throw IOException(
                    "There is nothing to swap back for what you chose (no nbt-aad.old or NbtAad.old in ${lay.aa})."
                )
            }
            stopDaemon(ssh, rep, pr.scan)
            clock.step()
            val ops = listOfNotNull(
                if (doDaemon) RemoteScripts.Op.REVERT_DAEMON else null,
                if (doPlugin) RemoteScripts.Op.REVERT_PLUGIN else null,
            )
            val r = ssh.exec(RemoteScripts.apply(lay.aa, lay.plugin, ops, remount = true), timeoutMs = 120_000)
            val (d, p) = RemoteScripts.parseDone(r.text)
            if (d) rep.swapped("daemon")
            if (p) rep.swapped("plugin")
            if (r.exit != 0) throw stepFailure(if (d || !doDaemon) "Going back to the previous plugin" else "Going back to the previous nbt-aad", r.exit, r.err)
            if (d) rep.say("nbt-aad is swapped with nbt-aad.old.", Level.OK)
            if (p) rep.say("The plugin folder is swapped with NbtAad.old.", Level.OK)
            val after = RemoteScripts.parseStatus(lay.aa, lay.plugin, r.text)
            if (d && after.daemonBroken) {
                rep.say("The nbt-aad that is in place now is not executable. Swap it again or install a release.", Level.WARN)
            }
            rep.say("Done in ${clock.total()}.", Level.INFO)
            Outcome(d, p, emptyList(), after.version, emptyList())
        }
    }
}
