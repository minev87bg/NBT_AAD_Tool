package com.nbtaad.editor.install

import com.nbtaad.editor.update.UpdateLogic
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.math.roundToInt

/**
 * What the app knows about an NBT-AAD build, free of Android classes so the build's unit tests can run it.
 * The names are the ones Sq00ky's update guide (updating.md) and the release workflow use: the daemon and the
 * five files of the iDrive plugin (which a release ships inside `Releases.zip`). An older release also shipped a
 * separate firewall helper (`pf_aa`); since nbt-aad's firewall rules are built into the daemon itself, the app no
 * longer looks for that file (a leftover one on the head unit is still cleaned up by uninstall, see RemoteLogic).
 */
object Names {
    const val DAEMON = "nbt-aad"
    const val CONTAINER = "Releases.zip"
    val PLUGIN = listOf("libNbtAad.so", "NbtAad.manifest", "NbtAad.xml", "NbtAad_ALL_Texts.zip", "NbtAad_ALL_Images.zip")

    /** The 6 files of a complete build, in the order they are shown. */
    val ALL: List<String> = listOf(DAEMON) + PLUGIN

    // a browser adds " (1)" to a name that already exists: "nbt-aad (1)", "Releases (2).zip"
    private val COPY_SUFFIX = Regex("""\s*\(\d+\)(?=(\.[^./\\]*)?$)""")

    fun baseName(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')

    fun clean(raw: String): String = baseName(raw).trim().replace(COPY_SUFFIX, "")

    /** The project's own spelling of a file name, or null when the file is not one of a build's files. */
    fun canonical(raw: String): String? {
        val n = clean(raw)
        return ALL.firstOrNull { it.equals(n, ignoreCase = true) }
    }

    fun isContainer(raw: String): Boolean = clean(raw).equals(CONTAINER, ignoreCase = true)

    fun isPlugin(name: String): Boolean = name in PLUGIN
}

/** A file as it was handed over: its name and its bytes. */
class SourceFile(val name: String, val bytes: ByteArray)

/** One of the build's files found in what was handed over. */
class Payload(val name: String, val bytes: ByteArray, val from: String, val sha256: String) {
    val size: Int get() = bytes.size
}

/** Which files were found (by the project's names), what else was seen, and whether the input was refused. */
class Inspection(val payloads: Map<String, Payload>, val notes: List<String>, val refused: String?)

object Limits {
    const val ENTRY = 64L * 1024 * 1024
    const val TOTAL = 160L * 1024 * 1024
    const val ENTRIES = 400
    const val DEPTH = 2
}

/**
 * Looks through loose files and zip archives (also a zip inside a zip, as `Releases.zip` inside a bigger one) for
 * the build's files. Nothing is written to disk by entry name, so a hostile archive cannot place a file anywhere;
 * entries with odd paths are skipped, and the sizes are capped so a zip bomb cannot fill the phone's memory.
 */
object Inspector {
    private class Candidate(val payload: Payload, val rank: Int)

    private class State {
        val found = LinkedHashMap<String, Candidate>()
        val notes = ArrayList<String>()
        var refused: String? = null
        var total = 0L
    }

    fun inspect(files: List<SourceFile>): Inspection {
        val st = State()
        for (f in files) {
            if (st.refused != null) break
            add(st, f.name, f.bytes, depth = 0, from = f.name, rank = 0)
        }
        val payloads = LinkedHashMap<String, Payload>()
        for (n in Names.ALL) st.found[n]?.let { payloads[n] = it.payload }
        return Inspection(payloads, st.notes, st.refused)
    }

    fun isZip(b: ByteArray): Boolean =
        b.size >= 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() && b[2] == 3.toByte() && b[3] == 4.toByte()

    private fun add(st: State, name: String, bytes: ByteArray, depth: Int, from: String, rank: Int) {
        st.total += bytes.size
        if (st.total > Limits.TOTAL) {
            st.refused = "That is more data than a build has (over ${Limits.TOTAL / 1024 / 1024} MB), so it was not opened."
            return
        }
        val canon = Names.canonical(name)
        if (canon != null) {
            val old = st.found[canon]
            if (old == null || rank < old.rank) {
                if (old != null) st.notes += "$canon was found twice; the one at $from is used."
                st.found[canon] = Candidate(Payload(canon, bytes, from, UpdateLogic.sha256Hex(bytes)), rank)
            } else {
                st.notes += "$canon was found twice; the one at ${old.payload.from} is used."
            }
            return
        }
        if (isZip(bytes)) {
            if (depth >= Limits.DEPTH) {
                st.notes += "Did not open $from (an archive inside archives inside archives)."
                return
            }
            openZip(st, bytes, depth + 1, from)
        } else {
            st.notes += "Ignored $from (not a file of a build)."
        }
    }

    private fun unsafe(path: String): Boolean =
        path.startsWith("/") || path.startsWith("\\") || path.contains(':') ||
            path.split('/', '\\').any { it == ".." }

    private fun openZip(st: State, bytes: ByteArray, depth: Int, from: String) {
        var entries = 0
        var ignored = 0
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
                while (st.refused == null) {
                    val e = zin.nextEntry ?: break
                    if (++entries > Limits.ENTRIES) {
                        st.refused = "$from has too many entries to be a build."
                        break
                    }
                    if (e.isDirectory) continue
                    val path = e.name
                    if (unsafe(path)) {
                        st.notes += "Skipped an entry with an unsafe path in $from."
                        continue
                    }
                    val base = Names.baseName(path)
                    val wanted = Names.canonical(base) != null || Names.isContainer(base) ||
                        (depth < Limits.DEPTH && base.endsWith(".zip", ignoreCase = true))
                    if (!wanted) {
                        if (base.endsWith(".zip", ignoreCase = true)) {
                            st.notes += "Did not open $path in $from (archives inside archives inside archives)."
                        } else {
                            ignored++
                        }
                        continue
                    }
                    val data = readLimited(zin)
                    if (data == null) {
                        st.refused = "$path in $from is larger than any file of a build."
                        break
                    }
                    val rank = depth * 100 + path.count { it == '/' }
                    add(st, base, data, depth, "$from › $path", rank)
                }
            }
        } catch (e: Exception) {
            st.notes += "$from could not be read to the end (${e.message ?: "damaged archive"})."
        }
        if (ignored > 0) st.notes += "Ignored $ignored other file${if (ignored == 1) "" else "s"} in $from."
    }

    private fun readLimited(zin: ZipInputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(32 * 1024)
        var n = 0L
        while (true) {
            val r = zin.read(buf)
            if (r < 0) break
            n += r
            if (n > Limits.ENTRY) return null
            out.write(buf, 0, r)
        }
        return out.toByteArray()
    }
}

/** The header of a program or library for the head unit (32-bit ARM, QNX). */
object Elf {
    const val EM_ARM = 40

    class Info(val bits32: Boolean, val littleEndian: Boolean, val type: Int, val machine: Int, val qnx: Boolean) {
        val isArm32: Boolean get() = bits32 && littleEndian && machine == EM_ARM
        val isProgram: Boolean get() = type == 2 || type == 3
        val isLibrary: Boolean get() = type == 3
    }

    fun parse(b: ByteArray): Info? {
        if (b.size < 52) return null
        if (b[0] != 0x7F.toByte() || b[1] != 'E'.code.toByte() || b[2] != 'L'.code.toByte() || b[3] != 'F'.code.toByte()) return null
        val le = b[5].toInt() == 1
        fun u16(o: Int): Int =
            if (le) (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
            else ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
        // the dynamic loader named in the file: QNX programs ask for ldqnx.so
        val head = String(b, 0, minOf(b.size, 2048), Charsets.ISO_8859_1)
        return Info(b[4].toInt() == 1, le, u16(16), u16(18), head.contains("ldqnx"))
    }
}

enum class Kind { BUILD, DAEMON_ONLY, PLUGIN_ONLY, SOME_FILES, NOTHING }

/**
 * The verdict on a set of files: how much of a complete build it is, whether every file looks right, and what it
 * would change on the head unit. A complete release (`nbt-aad` and the five plugin files) is all 6 names, 100 %.
 */
class BuildPackage(val inspection: Inspection) {
    val payloads: Map<String, Payload> get() = inspection.payloads

    /** Names found, in the order of [Names.ALL]. */
    val found: List<String> = Names.ALL.filter { it in inspection.payloads }
    val missing: List<String> = Names.ALL.filter { it !in inspection.payloads }
    val score: Double = found.size.toDouble() / Names.ALL.size
    val percent: Int = (score * 100).roundToInt()

    val hasDaemon: Boolean = Names.DAEMON in inspection.payloads
    val pluginFiles: List<String> = found.filter { Names.isPlugin(it) }

    val kind: Kind = when {
        found.isEmpty() -> Kind.NOTHING
        hasDaemon && score >= BUILD_THRESHOLD -> Kind.BUILD
        hasDaemon && pluginFiles.isEmpty() -> Kind.DAEMON_ONLY
        !hasDaemon -> Kind.PLUGIN_ONLY
        else -> Kind.SOME_FILES
    }

    /** The version the daemon names itself in, for example v1.9.91-sppreq3. */
    val version: String? = inspection.payloads[Names.DAEMON]?.let { findVersion(it.bytes) }

    /** Things that make an install unsafe: the install button stays off. */
    val problems: List<String>

    /** Things worth knowing that do not block. */
    val warnings: List<String>

    init {
        val bad = ArrayList<String>()
        val warn = ArrayList<String>()
        inspection.refused?.let { bad += it }
        for ((n, p) in inspection.payloads) {
            val b = p.bytes
            if (b.isEmpty()) {
                bad += "$n is empty."
                continue
            }
            when (n) {
                Names.DAEMON -> {
                    val e = Elf.parse(b)
                    when {
                        e == null -> bad += "$n is not a program: it does not start like an ELF file."
                        !e.isArm32 || !e.isProgram ->
                            bad += "$n is not a 32-bit ARM program, so the head unit cannot run it."
                        !e.qnx -> warn += "$n does not name the QNX loader (ldqnx), which the project's own programs do."
                    }
                }
                "libNbtAad.so" -> {
                    val e = Elf.parse(b)
                    when {
                        e == null -> bad += "$n is not a library: it does not start like an ELF file."
                        !e.isArm32 || !e.isLibrary -> bad += "$n is not a 32-bit ARM library, so iDrive cannot load it."
                    }
                }
                "NbtAad_ALL_Texts.zip", "NbtAad_ALL_Images.zip" ->
                    if (!Inspector.isZip(b)) bad += "$n is not a zip file."
                "NbtAad.xml" ->
                    if (!looksLikeXml(b)) bad += "$n does not look like an XML file."
                "NbtAad.manifest" ->
                    if (b.take(4096).any { it.toInt() == 0 }) bad += "$n looks like a binary file, not text."
            }
        }
        if (found.isNotEmpty() && kind == Kind.SOME_FILES) {
            warn += "Only ${found.size} of ${Names.ALL.size} files of a build are here (${percent}%). Installing replaces " +
                "just those files and keeps the others as they are on the head unit."
        }
        if (hasDaemon && version == null) {
            warn += "The daemon does not name its version inside (no NBT-AAD-VERSION=…), so it cannot be compared with " +
                "the installed one."
        }
        problems = bad
        warnings = warn
    }

    val installable: Boolean get() = problems.isEmpty() && found.isNotEmpty()

    /** Does the set replace the whole plugin folder (all five files), or only some files in it? */
    val fullPlugin: Boolean get() = pluginFiles.size == Names.PLUGIN.size

    companion object {
        const val BUILD_THRESHOLD = 0.8

        fun of(files: List<SourceFile>): BuildPackage = BuildPackage(Inspector.inspect(files))

        private fun looksLikeXml(b: ByteArray): Boolean {
            var i = 0
            if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) i = 3
            while (i < b.size && (b[i].toInt() == 0x20 || b[i].toInt() in 9..13)) i++
            return i < b.size && b[i] == '<'.code.toByte()
        }

        /** "v1.9.91-sppreq3" from the text NBT-AAD-VERSION=v1.9.91-sppreq3 inside the daemon, or null. */
        fun findVersion(b: ByteArray): String? {
            val key = "NBT-AAD-VERSION=".toByteArray(Charsets.US_ASCII)
            if (b.size < key.size) return null
            var i = 0
            val last = b.size - key.size
            while (i <= last) {
                if (b[i] == key[0]) {
                    var j = 1
                    while (j < key.size && b[i + j] == key[j]) j++
                    if (j == key.size) {
                        var k = i + key.size
                        val sb = StringBuilder()
                        while (k < b.size && sb.length < 40) {
                            val c = (b[k].toInt() and 0xFF).toChar()
                            if (c.isLetterOrDigit() || c == '.' || c == '-' || c == '_' || c == '+') {
                                sb.append(c)
                                k++
                            } else break
                        }
                        val v = sb.toString()
                        if (Regex("""^v\d+\.\d+\.\d+""").containsMatchIn(v)) return v
                    }
                }
                i++
            }
            return null
        }
    }
}
