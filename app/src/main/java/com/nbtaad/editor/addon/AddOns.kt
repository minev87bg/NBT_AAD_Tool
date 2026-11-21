package com.nbtaad.editor.addon

import com.nbtaad.editor.install.Kind
import com.nbtaad.editor.install.Names

/*
 * What the app makes of a survey: a build of Sq00ky's project (the installer takes it), an add-on the app knows by its
 * file set, or something it only describes. Whether the app can APPLY an add-on is written down here, per add-on, so
 * it is never promised for one that has no recipe in the app yet.
 */

enum class Match { SQ00KY_BUILD, KNOWN_ADDON, UNKNOWN }

class Recognition(
    val match: Match,
    val title: String,
    /** What the app found out, one line each. */
    val facts: List<String>,
    /** Things that speak against applying it. */
    val problems: List<String>,
    /** True when the installer (GET FOR INSTALL) takes these files. */
    val installable: Boolean,
    /** The add-on's name when [match] is [Match.KNOWN_ADDON]. */
    val addOn: String? = null,
    /** True when the app has a recipe that applies this add-on. */
    val appliesInApp: Boolean = false,
)

/** An add-on the app knows by the files it comes with. */
class KnownAddOn(
    val id: String,
    val title: String,
    val by: String,
    /** File names (base names) that must all be there; the first one must be a program for the head unit. */
    val files: List<String>,
    val needs: String,
    /** Does the app have a recipe that installs it? Stays false until the recipe is built and tested. */
    val recipe: Boolean = false,
)

object AddOns {
    val KNOWN = listOf(
        KnownAddOn(
            id = "nbt-aa-tile",
            title = "nbt-aa-tile",
            by = "deathwalker80",
            files = listOf("aa-tile", "aa-tile-launcher.sh", "install-boot.sh", "remove-boot.sh", "hook.txt"),
            needs = "nbt-aad 1.8.66 or newer, and the CarLife widget file on the head unit",
        ),
    )

    private val NOTE_NAMES = Regex("""(?i)^(readme.*|license.*|licence.*|changelog.*|sha256sums.*|.*\.md)$""")

    /**
     * The files of the zip that the installer does not use: it takes only the build's own files (see [Names]), so
     * anything else that came with them (cluster maps, scripts, programs, pictures) stays in the zip. Notes and
     * checksum lists are not counted, nor is what is inside the plugin's own zips.
     */
    fun leftOut(s: Survey): List<Entry> = s.entries.filter { e ->
        val inBuildZip = e.path.split('/').dropLast(1).any { Names.canonical(it) != null }
        Names.canonical(e.base) == null && !Names.isContainer(e.base) && !inBuildZip && !NOTE_NAMES.matches(e.base)
    }

    private fun leftOutFacts(s: Survey): List<String> {
        val out = leftOut(s)
        if (out.isEmpty()) return emptyList()
        val programs = out.count { it.kind == EntryKind.PROGRAM || it.kind == EntryKind.ELF }
        val scripts = out.count { it.kind == EntryKind.SCRIPT }
        val what = listOfNotNull(
            if (programs > 0) "$programs program${if (programs == 1) "" else "s"}" else null,
            if (scripts > 0) "$scripts script${if (scripts == 1) "" else "s"}" else null,
        )
        val names = out.take(5).joinToString(", ") { it.base } + if (out.size > 5) ", …" else ""
        return listOf(
            "${out.size} other file${if (out.size == 1) "" else "s"} in the zip " +
                (if (out.size == 1) "is" else "are") + " not part of a build and will NOT be installed" +
                (if (what.isNotEmpty()) " (including ${what.joinToString(" and ")})" else "") + ": $names.",
        )
    }

    fun recognize(s: Survey): Recognition {
        val facts = ArrayList<String>()
        val problems = ArrayList<String>()
        s.refused?.let { problems += it }
        s.sums?.let { sums ->
            when {
                sums.allMatch -> facts += "${sums.file}: all ${sums.listed} listed files match their checksums (this shows the files are complete and unchanged, not that they are safe)."
                else -> {
                    if (sums.different.isNotEmpty()) problems += "${sums.file}: ${sums.different.joinToString(", ")} do not match the checksum list."
                    if (sums.absent.isNotEmpty()) problems += "${sums.file} lists files that are not in the zip: ${sums.absent.joinToString(", ")}."
                }
            }
        }

        val b = s.build
        if (b != null && b.kind != Kind.NOTHING) {
            facts += buildString {
                append(when (b.kind) {
                    Kind.BUILD -> "A build of NBT-AAD"
                    Kind.DAEMON_ONLY -> "The NBT-AAD daemon only"
                    Kind.PLUGIN_ONLY -> "The iDrive plugin only"
                    else -> "Some files of an NBT-AAD build"
                })
                b.version?.let { append(", version $it") }
                append(": ${b.found.size} of ${Names.ALL.size} files (${b.percent}%).")
            }
            if (b.missing.isNotEmpty() && b.kind != Kind.BUILD) facts += "Not in it: ${b.missing.joinToString(", ")}."
            problems += b.problems
            facts += b.warnings
            facts += leftOutFacts(s)
            return Recognition(
                Match.SQ00KY_BUILD, "Files of Sq00ky's NBT-AAD", facts, problems,
                installable = b.installable && s.refused == null,
            )
        }

        for (k in KNOWN) {
            val have = k.files.all { f -> s.find(f) != null }
            val program = s.find(k.files.first())?.kind == EntryKind.PROGRAM
            if (have && program) {
                facts += "The file set of ${k.title} by ${k.by}."
                facts += "It needs ${k.needs}."
                if (!k.recipe) facts += "The app cannot apply this kind of add-on yet. It can show what is inside and ask the AI to read it."
                return Recognition(
                    Match.KNOWN_ADDON, "${k.title} (add-on)", facts, problems,
                    installable = false, addOn = k.id, appliesInApp = k.recipe,
                )
            }
        }

        val programs = s.entries.count { it.kind == EntryKind.PROGRAM }
        val scripts = s.entries.count { it.kind == EntryKind.SCRIPT }
        facts += "${s.entries.size} file${if (s.entries.size == 1) "" else "s"}" +
            (if (programs > 0) ", $programs made for the head unit" else "") +
            (if (scripts > 0) ", $scripts script${if (scripts == 1) "" else "s"}" else "") + "."
        facts += "None of Sq00ky's build files (nbt-aad, pf_aa, the plugin files) are in it, and it is not an add-on the app knows."
        return Recognition(Match.UNKNOWN, "Not a build the app knows", facts, problems, installable = false)
    }
}
