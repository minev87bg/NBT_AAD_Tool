package com.nbtaad.editor.addon

import com.nbtaad.editor.install.AssetInfo
import com.nbtaad.editor.install.ReleaseInfo

/*
 * The add-ons the app has approved and follows. Each is a project on GitHub: the app reads the project's releases, takes
 * the newest one that has the add-on's zip, and the add-on's own page does the rest. A new add-on is one more entry here
 * (plus the page that knows how to put it on the head unit).
 */

/** A GitHub project the app follows for its releases. */
class Followed(val owner: String, val repo: String) {
    val name: String get() = "$owner/$repo"
    val listUrl: String get() = "https://api.github.com/repos/$owner/$repo/releases?per_page=30"
    val pageUrl: String get() = "https://github.com/$owner/$repo"
    val releasesUrl: String get() = "$pageUrl/releases"
}

/** One approved add-on. */
class CatalogEntry(
    /** Short and fixed: it names the screenshot file (drawable `addon_shot_<id with _>`). */
    val id: String,
    val title: String,
    val author: String,
    val project: Followed,
    /** The start of the zip's file name; the number after it is the add-on's version. */
    val zipName: Regex,
    /** What it does, in a few plain sentences for the person. */
    val summary: String,
    /** How the shelf lists a kept copy of it. */
    val shelfKind: String,
    /** The name of the screenshot in the app's drawables, when there is one. */
    val screenshot: String? = null,
)

object AddOnCatalog {
    val DEATHWALKER80 = Followed("deathwalker80", "nbt-aa-tile")

    val ENTRIES: List<CatalogEntry> = listOf(
        CatalogEntry(
            id = "nbt-aa-tile",
            title = "Android Auto tile",
            author = "deathwalker80",
            project = DEATHWALKER80,
            zipName = Regex("""^nbt-aa-tile-(\d+)""", RegexOption.IGNORE_CASE),
            summary = "A tile in the iDrive main menu. Pressing it brings Android Auto back to the front. " +
                "Test it for 2 minutes first, then install it at boot; one tap removes it.",
            shelfKind = ShelfIndex.TILE_KIND,
            screenshot = "addon_shot_nbt_aa_tile",
        ),
        CatalogEntry(
            id = "aa-menu-look",
            title = "Menu look",
            author = "deathwalker80",
            project = DEATHWALKER80,
            zipName = Regex("""^aa-menu-look-(\d+)""", RegexOption.IGNORE_CASE),
            summary = "Makes the CarLife entry of the iDrive menu read \"Android Auto\" in every language, with your own " +
                "pictures if you like. Check first; the originals are kept and can be restored.",
            shelfKind = ShelfIndex.MENU_LOOK_KIND,
            screenshot = "addon_shot_aa_menu_look",
        ),
    )

    /** The projects followed, each once. */
    val PROJECTS: List<Followed> = ENTRIES.map { it.project }.distinctBy { it.name }

    /** The newest release that has an add-on's zip, and that zip. */
    class Found(val release: ReleaseInfo, val asset: AssetInfo, val version: String)

    /** The add-on's version in a zip's file name ("nbt-aa-tile-5.zip" is "5"), or null when the name is not the add-on's zip. */
    fun versionOf(entry: CatalogEntry, fileName: String): String? {
        if (!fileName.endsWith(".zip", ignoreCase = true)) return null
        return entry.zipName.find(fileName)?.groupValues?.get(1)
    }

    /**
     * The newest release among [releases] with the add-on's zip. A full release counts before a pre-release (a
     * pre-release is taken only when there is no full one); of the same kind the later published one wins.
     */
    fun newest(entry: CatalogEntry, releases: List<ReleaseInfo>): Found? {
        val hits = releases.mapNotNull { r ->
            val a = r.assets.firstOrNull { versionOf(entry, it.name) != null } ?: return@mapNotNull null
            Found(r, a, versionOf(entry, a.name)!!)
        }
        return hits.sortedWith(compareBy<Found> { it.release.prerelease }.thenByDescending { it.release.published }).firstOrNull()
    }

    /** True when version [latest] is newer than [have] (numbers compare as numbers, anything else only by difference). */
    fun isNewer(latest: String, have: String): Boolean {
        val a = latest.toIntOrNull()
        val b = have.toIntOrNull()
        return if (a != null && b != null) a > b else latest != have
    }

    /** The highest version of the add-on among kept files named [names]; null when none is. */
    fun newestKept(entry: CatalogEntry, names: List<String>): String? =
        names.mapNotNull { versionOf(entry, it) }.reduceOrNull { x, y -> if (isNewer(y, x)) y else x }

    /** What the card's main button does. */
    enum class Do { WAIT, OPEN_SAVED, GET, UPDATE, NOTHING }

    /** The state of one card: [latest] is the newest release's version (null: not known), [kept] the newest version kept on the phone. */
    class Plan(val doing: Do, val label: String, val status: String)

    /**
     * What a card offers. A copy on the phone is always usable (also with no internet); a newer release is offered as an
     * update, never installed by itself.
     */
    fun plan(latest: String?, kept: String?, checking: Boolean, offline: Boolean): Plan = when {
        latest != null && kept == null -> Plan(Do.GET, "GET $latest AND OPEN", "Newest release: $latest. Not on this phone yet.")
        latest != null && kept != null && isNewer(latest, kept) ->
            Plan(Do.UPDATE, "UPDATE TO $latest AND OPEN", "Newest release: $latest. On this phone: $kept.")
        latest != null -> Plan(Do.OPEN_SAVED, "OPEN", "Newest release: $latest, on this phone.")
        kept != null && checking -> Plan(Do.OPEN_SAVED, "OPEN", "Checking GitHub… On this phone: $kept.")
        kept != null && offline -> Plan(Do.OPEN_SAVED, "OPEN", "GitHub could not be reached. On this phone: $kept.")
        kept != null -> Plan(Do.OPEN_SAVED, "OPEN", "No release of it found on GitHub. On this phone: $kept.")
        checking -> Plan(Do.WAIT, "CHECKING GITHUB…", "Looking for the newest release…")
        offline -> Plan(Do.NOTHING, "NO INTERNET", "GitHub could not be reached, and there is no copy on this phone.")
        else -> Plan(Do.NOTHING, "NOT FOUND", "No release of it was found on GitHub.")
    }

    /** What to tell about a release's notes on the card: the first lines, cut at a sensible length. */
    fun notesDigest(notes: String, max: Int = 500): String {
        val t = notes.replace("\r\n", "\n").replace('\r', '\n').trim().replace(Regex("\n{3,}"), "\n\n")
        if (t.length <= max) return t
        val cut = t.take(max)
        val at = maxOf(cut.lastIndexOf('\n'), cut.lastIndexOf(". ") + 1, cut.lastIndexOf(' '))
        return (if (at > max / 2) cut.take(at) else cut).trimEnd() + " …"
    }
}
