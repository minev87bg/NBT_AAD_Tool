package com.nbtaad.editor.experimental

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nbtaad.editor.addon.AddOnAi
import com.nbtaad.editor.addon.AddOns
import com.nbtaad.editor.addon.Recognition
import com.nbtaad.editor.addon.Survey
import com.nbtaad.editor.addon.ZipSurvey
import com.nbtaad.editor.data.CommunityPrefs
import com.nbtaad.editor.install.SourceFile
import com.nbtaad.editor.log.AppLog
import com.nbtaad.editor.net.Online
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * The state of the community browser: the project's issues and pull requests, how they are filtered and sorted, which of
 * them the person follows and which share files, the one that is open, its comments, its digest, and the download of a
 * file that someone shared.
 */
class ExperimentalController(
    private val ctx: Context,
    private val scope: CoroutineScope,
    /** Is this setting name in the list of settings (or already among the person's own)? */
    private val isKnownSetting: (String) -> Boolean,
    /** A shared file arrived: it goes on to the installer's page. */
    private val onFile: (SourceFile, Topic, FileLink) -> Unit,
) {
    enum class Tab { ISSUES, PRS }

    var topics by mutableStateOf<List<Topic>?>(null); private set
    var loading by mutableStateOf(false); private set
    var message by mutableStateOf(""); private set
    var error by mutableStateOf(false); private set
    var remaining by mutableStateOf<Int?>(null); private set

    private val prefs = CommunityPrefs(ctx)

    var tab by mutableStateOf(Tab.ISSUES)
    var show by mutableStateOf(Show.OPEN)
    var sortBy by mutableStateOf(prefs.sort()); private set

    /** The numbers of the pages the person follows (the star), kept on the phone. */
    var following by mutableStateOf(prefs.following()); private set

    var scanNote by mutableStateOf(""); private set
    var scanned by mutableStateOf<Map<Int, List<FileLink>>>(emptyMap()); private set

    var selected by mutableStateOf<Topic?>(null); private set
    var comments by mutableStateOf<List<Comment>?>(null); private set
    var commentsBusy by mutableStateOf(false); private set
    var commentsMessage by mutableStateOf(""); private set
    var summary by mutableStateOf<Summary?>(null); private set

    // the AI summary: written by Claude with the person's own Anthropic key
    private val keys = AiKeyStore(ctx)
    var aiKeySet by mutableStateOf(keys.get() != null); private set
    var aiKeyDialog by mutableStateOf(false); private set
    var aiKeyNote by mutableStateOf(""); private set
    var aiBusy by mutableStateOf(false); private set
    var aiText by mutableStateOf<String?>(null); private set
    var aiMessage by mutableStateOf(""); private set

    var downloadBusy by mutableStateOf(false); private set
    var downloadMessage by mutableStateOf(""); private set
    var downloadError by mutableStateOf(false); private set

    /** What happened when a setting was offered for "Additional options". */
    var settingNote by mutableStateOf("")

    /** A text file (log, note) shown in a window. */
    class TextDoc(val name: String, val url: String, val text: String, val cut: Boolean)

    /** The pictures of one post, shown one by one; [index] is the one that was tapped. */
    class Gallery(val title: String, val pictures: List<MdPicture>, val index: Int)

    var textDoc by mutableStateOf<TextDoc?>(null); private set
    var textBusy by mutableStateOf(false); private set
    var textMessage by mutableStateOf(""); private set

    /** The address of the file that VIEW was last pressed for: the busy sign and the message belong under its buttons. */
    var textFor by mutableStateOf(""); private set

    /** The address of the file that GET FOR INSTALL was last pressed for. */
    var downloadFor by mutableStateOf(""); private set

    /** What LOOK INSIDE found in a shared file: the files in it, what the app makes of them, and the AI's reading of it. */
    class Inside(val link: FileLink, val topic: Topic, val file: SourceFile, val survey: Survey, val recognition: Recognition)

    var inside by mutableStateOf<Inside?>(null); private set
    var insideBusy by mutableStateOf(false); private set
    var insideMessage by mutableStateOf(""); private set
    var insideError by mutableStateOf(false); private set

    /** The address of the file that LOOK INSIDE was last pressed for. */
    var insideFor by mutableStateOf(""); private set
    var insideAi by mutableStateOf<String?>(null); private set
    var insideAiBusy by mutableStateOf(false); private set
    var insideAiMessage by mutableStateOf(""); private set

    // files whose name does not say what they are: the first bytes were looked at, true when they are readable text
    private val looked = mutableStateMapOf<String, Boolean>()
    private val lookFailed: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val looking: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val lookGate = Semaphore(3)
    var gallery by mutableStateOf<Gallery?>(null); private set

    private val commentCache = HashMap<Int, List<Comment>>()

    fun visible(): List<Topic> =
        TopicView.shown(topics ?: emptyList(), tab == Tab.PRS, show, following, sortBy)

    fun count(tab: Tab, show: Show): Int =
        TopicView.count(topics ?: emptyList(), tab == Tab.PRS, show, following)

    /** Followed pages that are not in the loaded list (older than the newest 300). */
    fun missingFollowed(): Int = topics?.let { TopicView.missing(it, following) } ?: 0

    fun isFollowing(number: Int): Boolean = number in following

    /** The star: follows a page, or stops following it. */
    fun toggleFollow(t: Topic) {
        following = if (t.number in following) following - t.number else following + t.number
        prefs.saveFollowing(following)
        AppLog.i("Experimental", "#${t.number} ${if (t.number in following) "is followed" else "is not followed any more"}")
    }

    /** Orders the list now; REFRESH then loads the list in that order too. */
    fun sort(by: SortBy) {
        sortBy = by
        prefs.saveSort(by)
    }

    fun isKnown(key: String) = isKnownSetting(key)

    fun open() {
        if (topics == null && !loading) refresh()
    }

    private fun fail(prefix: String, e: Exception) {
        AppLog.w("Experimental", "$prefix: ${e.javaClass.simpleName}: ${e.message}")
        error = true
        message = if (e is IssuesApi.RateLimited) e.message ?: "" else "$prefix: ${Online.describe(e)}"
    }

    /** Loads the list in the chosen order, then looks through it and the newest comments for files. One button for both. */
    fun refresh() {
        if (loading) return
        loading = true
        error = false
        scanNote = ""
        message = "Loading from GitHub…"
        scope.launch(Dispatchers.IO) {
            try {
                val r = IssuesApi.topics(ctx, sortBy)
                topics = r.value
                remaining = r.remaining
                commentCache.clear()
                message = "Looking for files in the posts…"
                scanFiles(r.value)
                val at = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
                message = "${r.value.size} pages loaded at $at" + (remaining?.let { ", $it requests left this hour" } ?: "")
                AppLog.i("Experimental", message)
            } catch (e: Exception) {
                fail("Could not load the list", e)
            } finally {
                loading = false
            }
        }
    }

    /**
     * Looks through the first posts and the newest comments of the project for links to files. The pages that share
     * some are marked in the list. A failure here leaves the list as it is and says so under it.
     */
    private fun scanFiles(list: List<Topic>) {
        try {
            val c = IssuesApi.recentComments(ctx)
            remaining = c.remaining ?: remaining
            val found = LinkedHashMap<Int, MutableList<FileLink>>()
            for (t in list) {
                val f = Links.files(t.body, t.author, "the first post")
                if (f.isNotEmpty()) found.getOrPut(t.number) { ArrayList() } += f
            }
            for (cm in c.value) {
                val f = Links.files(cm.body, cm.author, "a comment on ${cm.created.take(10)}")
                if (f.isNotEmpty() && cm.topic > 0) found.getOrPut(cm.topic) { ArrayList() } += f
            }
            scanned = found.mapValues { (_, v) -> v.distinctBy { it.url } }
            scanNote = if (found.isEmpty()) "No page shares a file." else "${found.size} page${if (found.size == 1) "" else "s"} share files (marked)."
            if (c.value.size >= 300) scanNote += " Older comments were not looked at."
            AppLog.i("Experimental", "Looked at the first posts and the newest ${c.value.size} comments: $scanNote")
        } catch (e: Exception) {
            AppLog.w("Experimental", "Scan failed: ${e.javaClass.simpleName}: ${e.message}")
            scanNote = if (e is IssuesApi.RateLimited) e.message ?: "" else "Could not look for files: ${Online.describe(e)}"
        }
    }

    fun select(t: Topic) {
        selected = t
        summary = null
        downloadMessage = ""
        downloadError = false
        settingNote = ""
        commentsMessage = ""
        aiText = null
        aiMessage = ""
        textDoc = null
        textMessage = ""
        textFor = ""
        downloadFor = ""
        gallery = null
        val cached = commentCache[t.number]
        when {
            cached != null -> comments = cached
            t.comments == 0 -> comments = emptyList()
            else -> {
                comments = null
                commentsBusy = true
                scope.launch(Dispatchers.IO) {
                    try {
                        val r = IssuesApi.comments(ctx, t.number)
                        remaining = r.remaining ?: remaining
                        commentCache[t.number] = r.value
                        if (selected?.number == t.number) comments = r.value
                    } catch (e: Exception) {
                        AppLog.w("Experimental", "Comments of #${t.number} failed: ${e.javaClass.simpleName}: ${e.message}")
                        if (selected?.number == t.number) {
                            commentsMessage = if (e is IssuesApi.RateLimited) e.message ?: "" else "Could not load the comments: ${Online.describe(e)}"
                            comments = emptyList()
                        }
                    } finally {
                        commentsBusy = false
                    }
                }
            }
        }
    }

    fun back() {
        selected = null
        comments = null
        summary = null
        aiText = null
        aiMessage = ""
        textDoc = null
        gallery = null
    }

    /**
     * Can this file be read in the app's text window? True or false, or null while the app is still looking into a file
     * whose name does not tell.
     */
    fun readable(link: FileLink): Boolean? = when (link.look) {
        Look.TEXT -> true
        Look.BINARY -> false
        Look.UNKNOWN -> looked[link.url]
    }

    /**
     * Looks into the files whose name does not say what they are (a few KB of each, nothing is saved), so that VIEW
     * is offered for every file that holds readable text. A file that could not be looked into counts as not readable
     * until the page is opened again.
     */
    fun checkFiles(files: List<FileLink>) {
        for (f in files) {
            if (f.look != Look.UNKNOWN) continue
            if (looked.containsKey(f.url) && f.url !in lookFailed) continue
            if (!looking.add(f.url)) continue
            scope.launch(Dispatchers.IO) {
                try {
                    val ok = lookGate.withPermit { IssuesApi.looksReadable(ctx, f) }
                    lookFailed.remove(f.url)
                    looked[f.url] = ok
                } catch (e: Exception) {
                    AppLog.w("Experimental", "Could not look into ${f.name}: ${e.javaClass.simpleName}: ${e.message}")
                    lookFailed.add(f.url)
                    looked[f.url] = false
                } finally {
                    looking.remove(f.url)
                }
            }
        }
    }

    /** Opens a log, note or text in a window: it is read into memory, nothing is saved on the phone. */
    fun viewText(link: FileLink) {
        if (textBusy || readable(link) != true) return
        textBusy = true
        textFor = link.url
        textMessage = ""
        scope.launch(Dispatchers.IO) {
            try {
                val r = IssuesApi.text(ctx, link)
                textDoc = TextDoc(link.name, link.url, r.text, r.cut)
            } catch (e: Exception) {
                AppLog.w("Experimental", "Reading ${link.name} failed: ${e.javaClass.simpleName}: ${e.message}")
                textMessage = "Could not open ${link.name}: ${Online.describe(e)}"
            } finally {
                textBusy = false
            }
        }
    }

    fun closeText() {
        textDoc = null
    }

    fun openGallery(title: String, pictures: List<MdPicture>, index: Int) {
        if (pictures.isEmpty()) return
        gallery = Gallery(title, pictures, index.coerceIn(0, pictures.size - 1))
    }

    fun closeGallery() {
        gallery = null
    }

    /** The button: needs the key first (the dialog opens when there is none), then asks Claude. */
    fun summarizeWithAi() {
        val t = selected ?: return
        if (aiBusy) return
        val key = keys.get()
        if (key == null) {
            openKeyDialog()
            return
        }
        if (commentsBusy) {
            aiMessage = "The comments are still loading. Try again in a moment."
            return
        }
        val list = comments ?: emptyList()
        aiBusy = true
        aiMessage = ""
        scope.launch(Dispatchers.IO) {
            try {
                val text = AiClient.summarize(ctx, key, t, list)
                if (selected?.number == t.number) aiText = text
            } catch (e: Exception) {
                AppLog.w("AI", "Summary failed: ${e.javaClass.simpleName}: ${e.message}")
                if (selected?.number == t.number) aiMessage = Online.describe(e)
            } finally {
                aiBusy = false
            }
        }
    }

    fun hideAi() {
        aiText = null
        aiMessage = ""
    }

    fun openKeyDialog() {
        aiKeyNote = ""
        aiKeyDialog = true
    }

    fun closeKeyDialog() {
        aiKeyDialog = false
        aiKeyNote = ""
    }

    /** Keeps the key when it looks like one. The answer says whether the dialog may close. */
    fun saveKey(raw: String): Boolean {
        if (!AiLogic.looksLikeKey(raw)) {
            aiKeyNote = "That does not look like an Anthropic key. It starts with sk-ant- and has no spaces."
            return false
        }
        keys.set(raw)
        aiKeySet = true
        aiMessage = ""
        closeKeyDialog()
        AppLog.i("AI", "An Anthropic key was saved on this phone")
        return true
    }

    fun removeKey() {
        keys.clear()
        aiKeySet = false
        aiText = null
        closeKeyDialog()
        AppLog.i("AI", "The Anthropic key was removed")
    }

    /** Shows or hides the digest of the open page. */
    fun toggleSummary() {
        val t = selected ?: return
        summary = if (summary != null) null else Digest.of(t, comments ?: emptyList())
    }

    fun download(link: FileLink) {
        val t = selected ?: return
        if (downloadBusy || !link.installable) return
        downloadBusy = true
        downloadFor = link.url
        downloadError = false
        downloadMessage = "Downloading ${link.name}…"
        scope.launch(Dispatchers.IO) {
            try {
                val f = IssuesApi.download(ctx, link) { downloadMessage = "Downloading ${link.name}… ${it / 1024} KB" }
                downloadMessage = ""
                onFile(f, t, link)
            } catch (e: Exception) {
                AppLog.e("Experimental", "Download of ${link.name} failed", e)
                downloadError = true
                downloadMessage = "Could not get ${link.name}: ${Online.describe(e)}"
            } finally {
                downloadBusy = false
            }
        }
    }

    /**
     * LOOK INSIDE: downloads the file into memory (nothing is saved), lists what is in it and says what the app makes of
     * it. Nothing is installed and nothing is sent anywhere.
     */
    fun lookInside(link: FileLink) {
        val t = selected ?: return
        if (insideBusy || link.kind != FileKind.BUILD) return
        insideBusy = true
        insideFor = link.url
        insideError = false
        insideMessage = "Downloading ${link.name}…"
        scope.launch(Dispatchers.IO) {
            try {
                val f = IssuesApi.download(ctx, link) { insideMessage = "Downloading ${link.name}… ${it / 1024} KB" }
                insideMessage = "Looking inside…"
                val survey = ZipSurvey.survey(f.name, f.bytes)
                val rec = AddOns.recognize(survey)
                AppLog.i(
                    "AddOn",
                    "Looked inside ${link.name} of #${t.number}: ${survey.entries.size} files, ${rec.match}" +
                        (rec.addOn?.let { " ($it)" } ?: "") + (if (rec.installable) ", installable" else "") +
                        (survey.sums?.let { ", checksums ${if (it.allMatch) "match" else "DIFFER"}" } ?: ""),
                )
                insideAi = null
                insideAiMessage = ""
                inside = Inside(link, t, f, survey, rec)
                insideMessage = ""
            } catch (e: Exception) {
                AppLog.e("Experimental", "Looking inside ${link.name} failed", e)
                insideError = true
                insideMessage = "Could not look inside ${link.name}: ${Online.describe(e)}"
            } finally {
                insideBusy = false
            }
        }
    }

    /** GET FOR INSTALL from the window: the file that was looked at goes to the installer, without a second download. */
    fun installInside() {
        val ins = inside ?: return
        if (!ins.recognition.installable) return
        closeInside()
        AppLog.i("Experimental", "Taking ${ins.link.name} (already downloaded) to the installer")
        scope.launch(Dispatchers.IO) { onFile(ins.file, ins.topic, ins.link) }
    }

    fun closeInside() {
        inside = null
        insideAi = null
        insideAiMessage = ""
    }

    /**
     * The AI check: Claude reads the post, the file list, the scripts and the README of a linked GitHub project, and says
     * whether the app can apply the file. It only describes; nothing it writes is run.
     */
    fun askAboutInside() {
        val ins = inside ?: return
        if (insideAiBusy) return
        val key = keys.get()
        if (key == null) {
            openKeyDialog()
            return
        }
        if (commentsBusy) {
            insideAiMessage = "The comments are still loading. Try again in a moment."
            return
        }
        val list = if (selected?.number == ins.topic.number) comments ?: emptyList() else emptyList()
        insideAiBusy = true
        insideAiMessage = ""
        scope.launch(Dispatchers.IO) {
            try {
                val repos = AddOnAi.repoLinks(AddOnAi.postTexts(ins.topic, list))
                val readmes = repos.mapNotNull { (o, r) -> IssuesApi.readme(ctx, o, r)?.let { "$o/$r" to it } }
                val message = AddOnAi.userMessage(ins.topic, list, ins.survey, ins.recognition, readmes)
                AppLog.i(
                    "AI",
                    "Asking ${AiLogic.MODEL} about ${ins.link.name}: ${message.length} characters, ${readmes.size} README of ${repos.size} linked project(s)",
                )
                val text = AiClient.send(ctx, key, AddOnAi.requestJson(message), "File check")
                if (inside === ins) insideAi = text
            } catch (e: Exception) {
                AppLog.w("AI", "File check failed: ${e.javaClass.simpleName}: ${e.message}")
                if (inside === ins) insideAiMessage = Online.describe(e)
            } finally {
                insideAiBusy = false
            }
        }
    }
}
