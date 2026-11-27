package com.nbtaad.editor.install

/** One file attached to a GitHub release. */
data class AssetInfo(val name: String, val size: Long, val url: String, val sha256: String?)

/** One release of the NBT-AAD project as GitHub lists it. */
data class ReleaseInfo(
    val tag: String,
    val title: String,
    val prerelease: Boolean,
    val published: String,
    val notes: String,
    val pageUrl: String,
    val assets: List<AssetInfo>,
)

/** Which of a release's files the installer needs. Kept free of Android classes so the build's tests can run it. */
object ReleaseLogic {
    const val OWNER = "Sq00ky"
    const val REPO = "Supra-iDrive6-Android-Auto"
    const val LIST_URL = "https://api.github.com/repos/$OWNER/$REPO/releases?per_page=30"
    const val PAGE_URL = "https://github.com/$OWNER/$REPO/releases"

    /** The project's own rolling tag for its main installer build (it moves this tag to each new version instead of
     * tagging a fresh one - see ReleasesApi.releaseByTag for why that needs fetching on its own). */
    const val ROLLING_TAG = "Installer"

    /** The plain list, newest first by when GitHub says it was published - not necessarily the order the list API
     * itself returned it in (see ROLLING_TAG), and not relied on by [installable] or anything else here. */
    fun newestFirst(releases: List<ReleaseInfo>): List<ReleaseInfo> = releases.sortedByDescending { it.published }

    /** A release's files that can be part of a build: the build's own files and zip archives (not .nbtpkg or records). */
    fun wanted(r: ReleaseInfo): List<AssetInfo> =
        r.assets.filter { a ->
            Names.canonical(a.name) != null || Names.isContainer(a.name) || a.name.endsWith(".zip", ignoreCase = true)
        }

    /** True when the release has anything to install. */
    fun installable(r: ReleaseInfo): Boolean = wanted(r).isNotEmpty()

    /** Total bytes the app would download for the release. */
    fun downloadSize(r: ReleaseInfo): Long = wanted(r).sumOf { maxOf(it.size, 0L) }

    /** "2026-05-12T10:11:12Z" to "2026-05-12". */
    fun day(iso: String): String = iso.take(10)

    /**
     * What the (i) of a release shows: the facts of the release, then the notes its author wrote, broken into lines
     * of at most [width] characters (words are not cut, so a long link stays whole). [whenText] is the publishing
     * time as the screen shows it.
     */
    fun infoText(r: ReleaseInfo, whenText: String, width: Int = 44): String {
        val out = StringBuilder()
        out.append(r.tag).append('\n')
        if (r.title.isNotBlank() && r.title != r.tag) out.append(r.title).append('\n')
        out.append('\n')
        out.append("Published: ").append(whenText).append('\n')
        if (r.prerelease) out.append("Pre-release: yes\n")
        val files = wanted(r)
        if (files.isEmpty()) {
            out.append("Files to install: none\n")
        } else {
            out.append("Files to install (").append(mb(downloadSize(r))).append("):\n")
            for (f in files) out.append("  ").append(f.name).append("  ").append(if (f.size >= 0) mb(f.size) else "?").append('\n')
        }
        if (r.pageUrl.isNotBlank()) out.append("Page: ").append(r.pageUrl).append('\n')
        out.append("\nRelease notes\n-------------\n")
        val notes = r.notes.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (notes.isEmpty()) out.append("(none written)") else out.append(wrap(notes, width).replace(Regex("\n{3,}"), "\n\n"))
        return out.toString()
    }

    /** Text broken into lines of at most [width] characters at spaces; a list item's next lines are indented under it. */
    fun wrap(text: String, width: Int): String =
        text.lines().joinToString("\n") { line ->
            val body = line.trimEnd().replace("\t", "    ")
            if (body.length <= width) return@joinToString body
            val lead = body.takeWhile { it == ' ' }.length
            val item = body.drop(lead).let { it.startsWith("- ") || it.startsWith("* ") }
            val indent = " ".repeat(lead + if (item) 2 else 0)
            val rows = ArrayList<String>()
            var row = StringBuilder(" ".repeat(lead))
            for (word in body.trim().split(' ').filter { it.isNotEmpty() }) {
                if (row.isNotBlank() && row.length + 1 + word.length > width) {
                    rows += row.toString().trimEnd()
                    row = StringBuilder(indent)
                }
                if (row.isNotBlank()) row.append(' ')
                row.append(word)
            }
            rows += row.toString().trimEnd()
            rows.joinToString("\n")
        }

    fun mb(bytes: Long): String =
        if (bytes < 1024 * 1024) "${maxOf(1L, bytes / 1024)} KB" else "%.1f MB".format(bytes / 1024.0 / 1024.0)
}
