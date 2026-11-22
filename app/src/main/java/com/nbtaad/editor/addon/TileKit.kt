package com.nbtaad.editor.addon

import com.nbtaad.editor.install.Elf
import com.nbtaad.editor.update.UpdateLogic
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/*
 * The file set of deathwalker80's nbt-aa-tile (a small program that puts an Android Auto tile into the iDrive main
 * menu), read from its zip. Only the three files that go onto the head unit are taken out of the zip: the program,
 * the launcher that starts it at boot, and the two lines (hook.txt) that are added to the head unit's
 * start_webserver.sh. Everything else in the zip (the author's own install scripts, the status script) is not used:
 * the app does those steps itself, with checks of its own. Nothing here needs Android.
 */

/** What a zip holds of the tile, and whether the app can put it on a head unit. */
class TileKit(
    /** The files taken from the zip, by base name: aa-tile, aa-tile-launcher.sh, hook.txt. */
    val files: Map<String, ByteArray>,
    /** The build number the launcher names itself with ("5"), when it does. */
    val version: String?,
    /** True when all three files are identical to a published build the app knows. */
    val known: Boolean,
    /** Things that stop the install. */
    val problems: List<String>,
    /** Things worth knowing. */
    val notes: List<String>,
) {
    val ok: Boolean get() = problems.isEmpty() && TileKits.NEEDED.all { it in files }
    val program: ByteArray get() = files.getValue(TileKits.PROGRAM)
    val launcher: ByteArray get() = files.getValue(TileKits.LAUNCHER)
    val hook: ByteArray get() = files.getValue(TileKits.HOOK)
    val title: String get() = "nbt-aa-tile" + (version?.let { " $it" } ?: "")
}

object TileKits {
    const val PROGRAM = "aa-tile"
    const val LAUNCHER = "aa-tile-launcher.sh"
    const val HOOK = "hook.txt"
    val NEEDED = listOf(PROGRAM, LAUNCHER, HOOK)

    /** SHA-256 of the three files of builds the app was written for: build number to (program, launcher, hook). */
    val KNOWN: Map<String, Triple<String, String, String>> = mapOf(
        "5" to Triple(
            "d0f6f1255cc2cfaae990f43f2dca50505b2ee68d6b02a1866355c0caab6ba5df",
            "8de87a652fa49fab3e2b5fb27be1b51795016f2d4f1faa7d05b7fc02a63677a2",
            "163d257522e01c7a477c16e4a7c9e744a006b2737d03d1cb4b6d43ac92d67f7c",
        ),
    )

    private const val MAX_ENTRY = 2 * 1024 * 1024
    private const val MAX_TOTAL = 8 * 1024 * 1024
    private const val MAX_ENTRIES = 200

    /** The one command line the hook may hold: it only starts the launcher, in the background, and says nothing. */
    private val HOOK_COMMAND = Regex("""\[ -f /opt/aa-tile/aa-tile-launcher\.sh \] && /bin/sh /opt/aa-tile/aa-tile-launcher\.sh </dev/null >/dev/null 2>&1 &""")
    private val VERSION = Regex("""nbt-aa-tile (\d+)""")

    /** Reads the zip; a zip that is no zip, or has none of the files, gives a kit with problems. */
    fun parse(zip: ByteArray): TileKit {
        val found = LinkedHashMap<String, ByteArray>()
        val seen = HashMap<String, Int>()
        val notes = ArrayList<String>()
        val problems = ArrayList<String>()
        var total = 0L
        var count = 0
        try {
            ZipInputStream(ByteArrayInputStream(zip)).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.isDirectory) continue
                    if (++count > MAX_ENTRIES) {
                        problems += "The zip holds too many files."
                        break
                    }
                    val base = e.name.replace('\\', '/').substringAfterLast('/')
                    val wanted = base in NEEDED || base in EXTRA
                    if (!wanted) continue
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = z.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                        if (out.size() > MAX_ENTRY || total > MAX_TOTAL) {
                            problems += "$base is far bigger than the tile's files are."
                            return TileKit(found, null, false, problems, notes)
                        }
                    }
                    seen[base] = (seen[base] ?: 0) + 1
                    found[base] = out.toByteArray()
                }
            }
        } catch (e: Exception) {
            return TileKit(emptyMap(), null, false, listOf("This is not a zip the app can read."), notes)
        }
        for ((n, c) in seen) if (c > 1) problems += "$n is in the zip more than once, so the app cannot tell which one is meant."
        return of(found, problems, notes)
    }

    private const val SUMS = "SHA256SUMS"
    private const val SIZE = "aa-tile.size"
    private val EXTRA = listOf(SUMS, SIZE)

    /** The checks on the files themselves (the zip is already open). Separate so that they can be tried without a zip. */
    fun of(all: Map<String, ByteArray>, earlier: List<String> = emptyList(), earlierNotes: List<String> = emptyList()): TileKit {
        val problems = ArrayList(earlier)
        val notes = ArrayList(earlierNotes)
        for (n in NEEDED) if (n !in all) problems += "$n is not in the zip."
        val files = all.filterKeys { it in NEEDED }
        if (problems.isNotEmpty()) return TileKit(files, null, false, problems, notes)

        val program = all.getValue(PROGRAM)
        val launcher = all.getValue(LAUNCHER)
        val hook = all.getValue(HOOK)

        // checksums the author listed
        val sums = all[SUMS]?.toString(Charsets.UTF_8)?.let(::parseSums)
        if (sums == null) notes += "The zip has no SHA256SUMS list: only the app's own checks were done."
        else for (n in NEEDED) {
            val want = sums[n]
            when {
                want == null -> notes += "SHA256SUMS does not list $n."
                !want.equals(UpdateLogic.sha256Hex(all.getValue(n)), ignoreCase = true) -> problems += "$n does not match SHA256SUMS: it was changed or is incomplete."
            }
        }
        all[SIZE]?.toString(Charsets.UTF_8)?.trim()?.toLongOrNull()?.let {
            if (it != program.size.toLong()) problems += "aa-tile is ${program.size} bytes but aa-tile.size says $it."
        }

        // the program must be one the head unit can run
        val elf = Elf.parse(program)
        when {
            elf == null -> problems += "aa-tile is not a program (no ELF header)."
            !elf.isArm32 || !elf.qnx || elf.type != 2 && elf.type != 3 -> problems += "aa-tile is not a 32-bit ARM program for QNX, so the head unit cannot run it."
        }

        // the launcher is a shell script that runs at every boot
        val launcherText = launcher.toString(Charsets.UTF_8)
        when {
            launcher.size > 32 * 1024 || launcher.any { it == 0.toByte() } -> problems += "$LAUNCHER does not look like a small shell script."
            !launcherText.startsWith("#!/bin/sh\n") -> problems += "$LAUNCHER does not start with #!/bin/sh."
            "/opt/aa-tile/aa-tile" !in launcherText -> problems += "$LAUNCHER does not start /opt/aa-tile/aa-tile, so it is not this program's launcher."
        }

        // the hook is added to a system script: only a comment and the one known line
        val hookText = hook.toString(Charsets.UTF_8)
        val hookLines = hookText.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
        val commands = hookLines.filterNot { it.startsWith("#") }
        when {
            hook.size > 1024 || hook.any { it == 0.toByte() } -> problems += "$HOOK is not the two short lines it should be."
            hook.lastOrNull() != '\n'.code.toByte() -> problems += "$HOOK does not end with a new line, so the line after it would be joined to it."
            commands.size != 1 || !HOOK_COMMAND.matches(commands.single()) ->
                problems += "$HOOK does something other than start the launcher in the background. The app does not add that to the head unit."
            hookLines.none { it.startsWith("# nbt-aa-tile hook") } ->
                problems += "$HOOK has no \"# nbt-aa-tile hook\" comment line, which is how the removal finds its own lines."
        }

        val version = VERSION.find(launcherText)?.groupValues?.get(1)
        val hashes = Triple(
            UpdateLogic.sha256Hex(program), UpdateLogic.sha256Hex(launcher), UpdateLogic.sha256Hex(hook),
        )
        val known = KNOWN.entries.firstOrNull { it.value == hashes }?.key
        if (known == null && problems.isEmpty()) {
            notes += "These files are not identical to a build the app knows (${KNOWN.keys.joinToString { "nbt-aa-tile $it" }}). They passed the app's checks, but the program has not been looked at."
        }
        return TileKit(files, version ?: known, known != null, problems, notes)
    }

    /** The lines of a SHA256SUMS file: file name to hash (names as written, with any folder part removed). */
    fun parseSums(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (raw in text.lines()) {
            val m = Regex("""^([0-9a-fA-F]{64})\s+\*?(.+)$""").find(raw.trim()) ?: continue
            out[m.groupValues[2].trim().replace('\\', '/').substringAfterLast('/')] = m.groupValues[1].lowercase()
        }
        return out
    }
}

/** What the tile needs from the rest of the head unit. */
object TileLogic {
    /** The first nbt-aad that has the control port and state file the tile uses (from the tile's own README). */
    const val NEEDS_DAEMON = "1.8.66"

    private val NUMBERS = Regex("""(\d+)\.(\d+)\.(\d+)""")

    /** Is [version] (as the daemon names itself, "v1.9.116" or "1.8.66") new enough? Null when it cannot be told. */
    fun daemonEnough(version: String?): Boolean? {
        val m = NUMBERS.find(version ?: return null) ?: return null
        val have = m.groupValues.drop(1).map { it.toLongOrNull() ?: return null }
        val need = NUMBERS.find(NEEDS_DAEMON)!!.groupValues.drop(1).map { it.toLong() }
        for (i in 0..2) if (have[i] != need[i]) return have[i] > need[i]
        return true
    }
}
