package com.nbtaad.editor.install

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nbtaad.editor.addon.AddOnCatalog
import com.nbtaad.editor.addon.CatalogEntry
import com.nbtaad.editor.addon.ShelfItem
import com.nbtaad.editor.log.AppLog
import com.nbtaad.editor.net.Online
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The Add-ons page: follows the GitHub projects of the approved add-ons ([AddOnCatalog]). It reads their releases, and a
 * card's button takes the newest zip (or the copy kept on the phone) to the page of the add-on.
 */
class AddOnsController(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val inst: InstallController,
    /** Opens the add-on's page for a file (file, where it came from, the release's page, the shelf entry); false when the app cannot read it as an add-on. */
    private val openPage: (SourceFile, String, String?, String?) -> Boolean,
) {
    /** The releases of each followed project, by "owner/repo". */
    var releases by mutableStateOf<Map<String, List<ReleaseInfo>>>(emptyMap()); private set
    var loading by mutableStateOf(false); private set

    /** Why the last look at GitHub failed (empty when it did not). */
    var loadMessage by mutableStateOf(""); private set
    var checkedAt by mutableStateOf(0L); private set

    /** The card that is getting its zip now, and what is going on. */
    var busyId by mutableStateOf<String?>(null); private set
    var step by mutableStateOf(""); private set

    /** What went wrong (or what to know) on a card, by add-on id. */
    var messages by mutableStateOf<Map<String, String>>(emptyMap()); private set

    /** Looks at GitHub when the page is opened (once; REFRESH looks again). */
    fun visit() {
        if (checkedAt == 0L && !loading) refresh()
    }

    fun refresh() {
        if (loading) return
        loading = true
        loadMessage = ""
        scope.launch(Dispatchers.IO) {
            val got = LinkedHashMap(releases)
            var failure: String? = null
            for (p in AddOnCatalog.PROJECTS) {
                try {
                    got[p.name] = ReleasesApi.listFrom(ctx, p.listUrl)
                    AppLog.i("AddOns", "${p.name}: ${got[p.name]?.size} release(s)")
                } catch (e: Exception) {
                    AppLog.w("AddOns", "Could not read the releases of ${p.name}: ${e.javaClass.simpleName}: ${e.message}")
                    if (failure == null) failure = Online.describe(e)
                }
            }
            releases = got
            loadMessage = failure ?: ""
            checkedAt = System.currentTimeMillis()
            loading = false
        }
    }

    fun latest(entry: CatalogEntry): AddOnCatalog.Found? = releases[entry.project.name]?.let { AddOnCatalog.newest(entry, it) }

    /** The copies of the add-on kept on the phone. */
    fun kept(entry: CatalogEntry): List<ShelfItem> = inst.shelf.filter { it.summary == entry.shelfKind }

    fun keptVersion(entry: CatalogEntry): String? = AddOnCatalog.newestKept(entry, kept(entry).map { it.name })

    fun plan(entry: CatalogEntry): AddOnCatalog.Plan {
        val offline = !loading && checkedAt != 0L && releases[entry.project.name] == null
        return AddOnCatalog.plan(latest(entry)?.version, keptVersion(entry), checking = loading || checkedAt == 0L, offline = offline)
    }

    private fun say(id: String, text: String?) {
        messages = if (text.isNullOrEmpty()) messages - id else messages + (id to text)
    }

    /** The card's main button. */
    fun act(entry: CatalogEntry) {
        if (busyId != null) return
        when (plan(entry).doing) {
            AddOnCatalog.Do.OPEN_SAVED -> openKept(entry)
            AddOnCatalog.Do.GET, AddOnCatalog.Do.UPDATE -> get(entry)
            else -> Unit
        }
    }

    private fun openKept(entry: CatalogEntry) {
        val v = keptVersion(entry) ?: return
        val item = kept(entry).firstOrNull { AddOnCatalog.versionOf(entry, it.name) == v } ?: return
        say(entry.id, null)
        inst.useShelf(item)
    }

    /** Takes the newest zip (the copy kept on the phone when it is the same file, else a download), keeps it and opens the add-on's page. */
    private fun get(entry: CatalogEntry) {
        val f = latest(entry) ?: return
        busyId = entry.id
        say(entry.id, null)
        step = "Getting ${f.asset.name}…"
        scope.launch(Dispatchers.IO) {
            try {
                var file = inst.keptCopy(f.asset.url, f.asset.name, f.release.pageUrl)
                if (file != null) {
                    AppLog.i("AddOns", "${f.asset.name} is on the phone already, not downloaded again")
                } else {
                    val bytes = ReleasesApi.download(ctx, f.asset) { got -> step = "Downloading ${f.asset.name}… ${got / 1024} KB" }
                    file = SourceFile(f.asset.name, bytes)
                }
                val origin = "${entry.project.name} ${f.release.tag}"
                val item = inst.keepForLater(file, origin, f.release.pageUrl, f.asset.url)
                if (!openPage(file, origin, f.release.pageUrl, item?.id)) {
                    AppLog.w("AddOns", "${f.asset.name} from ${entry.project.name} is not read as ${entry.title}")
                    say(
                        entry.id,
                        "${f.asset.name} was downloaded, but the app does not read it as the ${entry.title}. The author may have " +
                            "changed the zip. Please tell the app's developer (the release page: ${f.release.pageUrl}).",
                    )
                }
            } catch (e: Exception) {
                AppLog.w("AddOns", "Getting ${f.asset.name} failed: ${e.javaClass.simpleName}: ${e.message}")
                say(entry.id, "Could not get ${f.asset.name}: ${Online.describe(e)}")
            } finally {
                busyId = null
                step = ""
            }
        }
    }
}
