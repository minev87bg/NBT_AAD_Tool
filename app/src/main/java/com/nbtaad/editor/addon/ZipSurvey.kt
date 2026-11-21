package com.nbtaad.editor.addon

import com.nbtaad.editor.experimental.TextFiles
import com.nbtaad.editor.install.BuildPackage
import com.nbtaad.editor.install.Elf
import com.nbtaad.editor.install.Inspector
import com.nbtaad.editor.install.Limits
import com.nbtaad.editor.install.SourceFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/*
 * A look inside a file that someone shared: every file of a zip, what each one is, whether the checksums that come
 * with it agree. Nothing is written anywhere and nothing is run: the zip is read in memory, with the same limits as
 * the installer's own scan (size, number of entries, archives in archives), and an entry with an odd path is only
 * named, never used.
 */

/** What one file inside a zip is, judged by its first bytes (the name only breaks ties). */
enum class EntryKind(val label: String) {
    /** A 32-bit ARM program or library that asks for the QNX loader: made for the head unit. */
    PROGRAM("program for the head unit"),
    ELF("other program"),
    SCRIPT("script"),
    TEXT("text"),
    ARCHIVE("archive"),
    PICTURE("picture"),
    OTHER("other file"),
}

/**
 * One file of the zip. [path] is where it is, with the zip it came out of in front when that was inside another zip.
 * [text] is the start of a script or text, for reading (null for the rest).
 */
class Entry(
    val path: String,
    val size: Long,
    val kind: EntryKind,
    val sha256: String,
    val text: String? = null,
    val note: String = "",
) {
    val base: String get() = path.substringAfterLast('/')
}

/** The checksum list that came with the files (a SHA256SUMS file) against the files themselves. */
class Sums(val file: String, val listed: Int, val matching: List<String>, val different: List<String>, val absent: List<String>) {
    val allMatch: Boolean get() = listed > 0 && different.isEmpty() && absent.isEmpty()
}

class Survey(
    val name: String,
    val entries: List<Entry>,
    val notes: List<String>,
    /** Why the look stopped early (too big, too many entries); null when the whole zip was looked at. */
    val refused: String?,
    val build: BuildPackage?,
    val sums: Sums?,
) {
    fun find(base: String): Entry? = entries.firstOrNull { it.base.equals(base, ignoreCase = true) }
}

object ZipSurvey {
    /** How much of the start of a file is kept: enough to tell what it is, and to read a script. */
    private const val HEAD = 16 * 1024

    /** Archives inside archives are opened one level deeper than the installer does: a release holds zips with zips. */
    private const val MAX_DEPTH = Limits.DEPTH + 1

    /** The characters of a text kept for reading. */
    const val TEXT_KEEP = 8000

    private val PICTURES = listOf(
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47), byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()),
        byteArrayOf(0x47, 0x49, 0x46, 0x38),
    )
    private val OTHER_ARCHIVES = byteArrayOf(0x1F, 0x8B.toByte()) // gzip
    private val ARCHIVE_NAMES = listOf(".gz", ".tgz", ".tar", ".7z", ".rar", ".xz", ".bz2")
    private val SUMS_NAMES = setOf("sha256sums", "sha256sums.txt", "sha256sum", "checksums.sha256")

    private fun starts(b: ByteArray, p: ByteArray) = b.size >= p.size && p.indices.all { b[it] == p[it] }

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun unsafe(path: String): Boolean =
        path.startsWith("/") || path.startsWith("\\") || path.contains(':') || path.split('/', '\\').any { it == ".." }

    /** What a file is, from its first bytes and its name. */
    fun classify(path: String, head: ByteArray): EntryKind {
        Elf.parse(head)?.let { return if (it.isArm32 && it.qnx) EntryKind.PROGRAM else EntryKind.ELF }
        if (Inspector.isZip(head) || starts(head, OTHER_ARCHIVES)) return EntryKind.ARCHIVE
        val lower = path.lowercase()
        if (ARCHIVE_NAMES.any { lower.endsWith(it) }) return EntryKind.ARCHIVE
        if (PICTURES.any { starts(head, it) }) return EntryKind.PICTURE
        if (TextFiles.sniff(head)) {
            val shebang = head.size >= 2 && head[0] == '#'.code.toByte() && head[1] == '!'.code.toByte()
            return if (shebang || lower.endsWith(".sh") || lower.endsWith(".bash")) EntryKind.SCRIPT else EntryKind.TEXT
        }
        return EntryKind.OTHER
    }

    private class Run(val limitTotal: Long = Limits.TOTAL) {
        val entries = ArrayList<Entry>()
        val notes = ArrayList<String>()
        var refused: String? = null
        var total = 0L
        var count = 0
        /** Content of every file by its path, kept only for the files that name checksums. */
        val sumsText = LinkedHashMap<String, String>()
    }

    /** Looks at [bytes], the file called [name]: a zip, or a single file (then it is the only entry). */
    fun survey(name: String, bytes: ByteArray): Survey {
        val run = Run()
        if (Inspector.isZip(bytes)) {
            openZip(run, bytes, depth = 1, from = "")
        } else {
            addEntry(run, name, bytes.size.toLong(), bytes.copyOf(minOf(bytes.size, HEAD)), digest(bytes))
        }
        val build = try { BuildPackage.of(listOf(SourceFile(name, bytes))).takeIf { it.found.isNotEmpty() } } catch (_: Exception) { null }
        return Survey(name, run.entries, run.notes, run.refused, build, sumsOf(run))
    }

    private fun digest(b: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(b))

    private fun addEntry(run: Run, path: String, size: Long, head: ByteArray, sha: String) {
        val kind = classify(path, head)
        val text = if (kind == EntryKind.SCRIPT || kind == EntryKind.TEXT) {
            TextFiles.decode(head, cut = size > head.size).let { if (it.length > TEXT_KEEP) it.take(TEXT_KEEP) + "\n[… cut]" else it }
        } else null
        val note = when (kind) {
            EntryKind.PROGRAM -> "32-bit ARM, QNX"
            EntryKind.ELF -> "not a QNX program for the head unit"
            else -> ""
        }
        run.entries += Entry(path, size, kind, sha, text, note)
        if (path.substringAfterLast('/').lowercase() in SUMS_NAMES && text != null) run.sumsText[path] = text
    }

    private fun openZip(run: Run, bytes: ByteArray, depth: Int, from: String) {
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
                while (run.refused == null) {
                    val e = zin.nextEntry ?: break
                    if (++run.count > Limits.ENTRIES) {
                        run.refused = "${from.ifEmpty { "The zip" }} has more than ${Limits.ENTRIES} entries, so the rest was not looked at."
                        break
                    }
                    if (e.isDirectory) continue
                    val path = from + e.name
                    if (unsafe(e.name)) {
                        run.notes += "Skipped an entry with an unsafe path ($path)."
                        continue
                    }
                    val md = MessageDigest.getInstance("SHA-256")
                    val head = ByteArrayOutputStream()
                    var whole: ByteArrayOutputStream? = null
                    var size = 0L
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val n = zin.read(buf)
                        if (n < 0) break
                        size += n
                        run.total += n
                        if (size > Limits.ENTRY || run.total > run.limitTotal) {
                            run.refused = if (size > Limits.ENTRY) "$path is larger than ${Limits.ENTRY / 1024 / 1024} MB, so the look stopped there."
                            else "The zip holds more than ${run.limitTotal / 1024 / 1024} MB, so the look stopped at $path."
                            break
                        }
                        md.update(buf, 0, n)
                        if (head.size() < HEAD) head.write(buf, 0, minOf(n, HEAD - head.size()))
                        if (whole == null && size == n.toLong() && n >= 4 && starts(buf, byteArrayOf(0x50, 0x4B, 0x03, 0x04))) {
                            whole = ByteArrayOutputStream()
                        }
                        whole?.write(buf, 0, n)
                    }
                    if (run.refused != null) break
                    val headBytes = head.toByteArray()
                    addEntry(run, path, size, headBytes, hex(md.digest()))
                    if (whole != null) {
                        val inner = whole.toByteArray()
                        if (Inspector.isZip(inner)) {
                            if (depth >= MAX_DEPTH) {
                                run.notes += "Did not open $path (an archive inside archives inside archives)."
                            } else {
                                openZip(run, inner, depth + 1, "$path/")
                            }
                        }
                    }
                }
            }
        } catch (e: java.io.IOException) {
            run.notes += "${from.ifEmpty { "The zip" }} could not be read to the end (${e.message ?: "damaged"})."
        }
    }

    // ---- the checksum list ----

    /** "hex  name" lines (the name may start with * or ./), as sha256sum writes them. */
    fun parseSums(text: String): List<Pair<String, String>> =
        text.lineSequence().mapNotNull { line ->
            val m = Regex("""^\s*([0-9a-fA-F]{64})\s+\*?(\S.*?)\s*$""").find(line) ?: return@mapNotNull null
            m.groupValues[1].lowercase() to m.groupValues[2].removePrefix("./")
        }.toList()

    private fun sumsOf(run: Run): Sums? {
        val (path, text) = run.sumsText.entries.firstOrNull() ?: return null
        val dir = path.substringBeforeLast('/', "")
        val listed = parseSums(text)
        if (listed.isEmpty()) return null
        val ok = ArrayList<String>()
        val bad = ArrayList<String>()
        val gone = ArrayList<String>()
        for ((sum, name) in listed) {
            val full = if (dir.isEmpty()) name else "$dir/$name"
            val e = run.entries.firstOrNull { it.path == full }
            when {
                e == null -> gone += name
                e.sha256 == sum -> ok += name
                else -> bad += name
            }
        }
        return Sums(path.substringAfterLast('/'), listed.size, ok, bad, gone)
    }
}
