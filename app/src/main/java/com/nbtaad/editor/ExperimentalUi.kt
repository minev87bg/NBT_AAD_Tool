package com.nbtaad.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nbtaad.editor.addon.Recognition
import com.nbtaad.editor.experimental.Comment
import com.nbtaad.editor.experimental.Contributors
import com.nbtaad.editor.experimental.ExperimentalController
import com.nbtaad.editor.experimental.FileKind
import com.nbtaad.editor.experimental.FileLink
import com.nbtaad.editor.experimental.Mentions
import com.nbtaad.editor.experimental.Show
import com.nbtaad.editor.experimental.SortBy
import com.nbtaad.editor.experimental.Markdown
import com.nbtaad.editor.experimental.Topic
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The Experimental page: the project's issues and pull requests, and what people share in them. */
@Composable
fun ExperimentalScreen(vm: MainViewModel) {
    val exp = vm.exp
    val open = exp.selected
    BackHandler { if (open != null) exp.back() else vm.backToHub() }
    ExperimentalTheme { if (open == null) TopicList(vm) else TopicDetail(vm, open) }
}

/** The amber of the EXPERIMENTAL tile's icon on the choice page: the accent of this whole page. */
internal val ExperimentalAmber = Color(0xFFE9A33A)

/**
 * The page's buttons, chips, links and progress bars take the page's amber instead of the app's green, so it is
 * recognised as the page of the amber EXPERIMENTAL tile. On the light theme the text uses a deeper amber, which stays
 * readable on the cream background.
 */
@Composable
internal fun ExperimentalTheme(content: @Composable () -> Unit) {
    val base = MaterialTheme.colorScheme
    val dark = base.background.luminance() < 0.5f
    val scheme = if (dark) {
        base.copy(
            primary = ExperimentalAmber, onPrimary = Color(0xFF0D1117),
            primaryContainer = Color(0xFF3B2B10), onPrimaryContainer = Color(0xFFFFE2A8),
            secondaryContainer = Color(0xFF3B2B10), onSecondaryContainer = Color(0xFFFFE2A8),
            surfaceTint = ExperimentalAmber,
        )
    } else {
        base.copy(
            primary = Color(0xFFA66300), onPrimary = Color.White,
            primaryContainer = Color(0xFFFFE7BD), onPrimaryContainer = Color(0xFF4A2E00),
            secondaryContainer = Color(0xFFFFE7BD), onSecondaryContainer = Color(0xFF4A2E00),
            surfaceTint = Color(0xFFA66300),
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private val ThanksBlueDark = Color(0xFF58A6FF)
private val ThanksBlueLight = Color(0xFF0969DA)

/** The blue of the main contributors' names (the page's amber is taken by the pages that share files). */
@Composable
private fun thanksBlue(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) ThanksBlueDark else ThanksBlueLight

/** "author  ·  rest": the name of a main contributor is bold, blue, on a tag of its own; anybody else's is plain. */
@Composable
private fun byLine(author: String, rest: String): AnnotatedString {
    val blue = thanksBlue()
    val main = Contributors.isMain(author)
    return buildAnnotatedString {
        if (main) {
            withStyle(SpanStyle(color = blue, fontWeight = FontWeight.Bold, background = blue.copy(alpha = 0.16f))) {
                append(" $author ")
            }
        } else {
            append(author)
        }
        append(rest)
    }
}

/** The faint blue under a main contributor's post, on top of what the surface is. */
@Composable
private fun thanksTint(): Color =
    thanksBlue().copy(alpha = 0.09f).compositeOver(MaterialTheme.colorScheme.surface)

private const val LIST_INFO =
    "What people report, test and suggest in Sq00ky's project, read from GitHub without signing in (GitHub allows 60 " +
        "requests an hour that way; the line under the buttons says how many are left).\n\n" +
        "REFRESH loads the list in the chosen order and looks through the first posts and the newest comments for " +
        "links to files. Pages that share files are marked FILES: a test build can be taken to the installer, a log " +
        "can be read here.\n\n" +
        "The names of Sq00ky, mhmm-ok and tom-pryor, who build the project, are lit up in blue, with a blue frame " +
        "around their pages and comments, so that what they write is easy to find. Thank you, all three.\n\n" +
        "The star follows a page. FOLLOWING lists just those pages, open or closed.\n\n" +
        "Open a page and press PREPARE LOGS to take part: the app guides you through a test and sends its logs to " +
        "that page.\n\n" +
        "Files shared in a discussion come from other people. The app can check what a file is, not whether it is safe."

@Composable
private fun Pill(text: String, color: Color, solid: Boolean = false) {
    Box(
        Modifier.clip(CircleShape).background(if (solid) color else color.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (solid) Color(0xFF0D1117) else color,
            letterSpacing = 0.5.sp,
        )
    }
}

@Composable
private fun TopicList(vm: MainViewModel) {
    val exp = vm.exp
    var showInfo by remember { mutableStateOf(false) }
    val shown = exp.visible()
    val following = exp.show == Show.FOLLOWING
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 56.dp, bottom = 24.dp),
    ) {
        item(key = "head") {
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Browse community", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Box {
                        InfoDot { showInfo = true }
                        if (showInfo) InfoBalloon("Browse community", LIST_INFO, mono = false) { showInfo = false }
                    }
                }
                Text(
                    "Issues and pull requests of Sq00ky's project",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // the followed pages are shown together, issues and pull requests, so the two tabs rest meanwhile
                    FilterChip(
                        selected = !following && exp.tab == ExperimentalController.Tab.ISSUES,
                        enabled = !following,
                        onClick = { exp.tab = ExperimentalController.Tab.ISSUES },
                        label = {
                            Text(
                                "Issues" + if (following) "" else " ${exp.count(ExperimentalController.Tab.ISSUES, exp.show)}",
                                fontSize = 12.sp,
                            )
                        },
                    )
                    FilterChip(
                        selected = !following && exp.tab == ExperimentalController.Tab.PRS,
                        enabled = !following,
                        onClick = { exp.tab = ExperimentalController.Tab.PRS },
                        label = {
                            Text(
                                "Pull requests" + if (following) "" else " ${exp.count(ExperimentalController.Tab.PRS, exp.show)}",
                                fontSize = 12.sp,
                            )
                        },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = exp.show == Show.OPEN,
                        onClick = { exp.show = Show.OPEN },
                        label = { Text("Open", fontSize = 12.sp) },
                    )
                    FilterChip(
                        selected = exp.show == Show.CLOSED,
                        onClick = { exp.show = Show.CLOSED },
                        label = { Text("Closed", fontSize = 12.sp) },
                    )
                    FilterChip(
                        selected = following,
                        onClick = { exp.show = Show.FOLLOWING },
                        label = { Text("★ Following ${exp.count(ExperimentalController.Tab.ISSUES, Show.FOLLOWING)}", fontSize = 12.sp) },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SortMenu(exp)
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = { exp.refresh() }, enabled = !exp.loading) { Text("REFRESH", fontSize = 12.sp) }
                }
                if (exp.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
                if (exp.message.isNotEmpty()) {
                    Text(exp.message, fontSize = 12.sp, color = if (exp.error) Red else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (exp.scanNote.isNotEmpty()) {
                    Text(exp.scanNote, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (following && exp.missingFollowed() > 0) {
                    Text(
                        "${exp.missingFollowed()} followed page${if (exp.missingFollowed() == 1) " is" else "s are"} older than the " +
                            "300 newest that are loaded, so not listed.",
                        fontSize = 12.sp,
                        color = Amber,
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        if (exp.topics != null && shown.isEmpty()) {
            item(key = "none") {
                Text(
                    if (following) "No page is followed yet. Tap the star on a page to follow it." else "Nothing here.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(shown, key = { it.number }) { t -> TopicRow(exp, t, compact = following) }
    }
}

/** "Sort: Recent activity ▾": the order of the list, next to the button that loads it. */
@Composable
private fun SortMenu(exp: ExperimentalController) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("Sort: ${exp.sortBy.label} ▾", fontSize = 12.sp) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (by in SortBy.entries) {
                DropdownMenuItem(text = { Text(by.label) }, onClick = { exp.sort(by); open = false })
            }
        }
    }
}

/** The star of a page: filled when the person follows it. */
@Composable
private fun StarButton(on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (on) "★" else "☆",
            fontSize = 22.sp,
            color = if (on) ExperimentalAmber else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One page in the list. [compact]: the followed pages, close together, one line for the title. */
@Composable
private fun TopicRow(exp: ExperimentalController, t: Topic, compact: Boolean) {
    val files = exp.scanned[t.number]
    val hasFiles = !files.isNullOrEmpty()
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val soft = MaterialTheme.colorScheme.onSurfaceVariant
    val main = Contributors.isMain(t.author)
    val blue = thanksBlue()
    val blueTint = thanksTint()
    // a page that shares files stands out: a thick outline in the page's amber, a faint amber tint, a solid stamp;
    // a page of a main contributor gets a thin blue outline and a faint blue tint (files come first)
    OutlinedCard(
        onClick = { exp.select(t) },
        modifier = Modifier.fillMaxWidth().padding(vertical = if (compact) 2.dp else 4.dp),
        colors = when {
            hasFiles -> CardDefaults.outlinedCardColors(
                containerColor = ExperimentalAmber.copy(alpha = if (dark) 0.11f else 0.16f).compositeOver(MaterialTheme.colorScheme.surface),
            )
            main -> CardDefaults.outlinedCardColors(containerColor = blueTint)
            else -> CardDefaults.outlinedCardColors()
        },
        border = when {
            hasFiles -> BorderStroke(2.5.dp, ExperimentalAmber)
            main -> BorderStroke(1.5.dp, blue.copy(alpha = 0.75f))
            else -> CardDefaults.outlinedCardBorder()
        },
    ) {
        Row(
            Modifier.padding(start = 12.dp, top = if (compact) 4.dp else 6.dp, bottom = if (compact) 4.dp else 6.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    (if (t.isPr && compact) "PR " else "") + "#${t.number}  ${t.title}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = if (compact) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (compact) {
                    Text(
                        byLine(t.author, "  ·  ${TimeText.full(t.updated)}" + if (!t.open) "  ·  closed" else ""),
                        fontSize = 11.sp,
                        color = soft,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        byLine(
                            t.author,
                            buildString {
                                append("  ·  ${t.comments} comment${if (t.comments == 1) "" else "s"}")
                                if (t.merged) append("  ·  merged") else if (t.draft) append("  ·  draft")
                            },
                        ),
                        fontSize = 12.sp,
                        color = soft,
                    )
                    Text(
                        if (exp.sortBy == SortBy.OPENED) "Opened ${TimeText.full(t.created)}" else "Updated ${TimeText.full(t.updated)}",
                        fontSize = 12.sp,
                        color = soft,
                    )
                }
            }
            if (!files.isNullOrEmpty()) {
                Spacer(Modifier.padding(horizontal = 4.dp))
                Pill("FILES ${files.size}", ExperimentalAmber, solid = true)
            }
            StarButton(exp.isFollowing(t.number)) { exp.toggleFollow(t) }
        }
    }
}

internal fun openLink(handler: androidx.compose.ui.platform.UriHandler, url: String) {
    try {
        handler.openUri(url)
    } catch (_: Exception) {
        // no browser to open it
    }
}

@Composable
private fun TopicDetail(vm: MainViewModel, t: Topic) {
    val exp = vm.exp
    val uri = LocalUriHandler.current
    val comments = exp.comments
    val files = remember(t, comments) { com.nbtaad.editor.experimental.Digest.filesOf(t, comments ?: emptyList()) }
    val settings = remember(t, comments) {
        Mentions.settings(listOf(t.body) + (comments ?: emptyList()).map { it.body })
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // the post VIEW POST jumped to is tinted for a moment, so the eye finds where it landed
    var flash by remember(t) { mutableIntStateOf(-1) }
    LaunchedEffect(flash) {
        if (flash >= 0) {
            delay(2200)
            flash = -1
        }
    }
    // files whose name does not say what they are are looked into, so VIEW is offered for every readable one
    LaunchedEffect(files) { exp.checkFiles(files) }
    val fileActions: @Composable (FileLink) -> Unit = { f -> FileActions(exp, f, onOpen = { openLink(uri, it) }) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 56.dp, bottom = 88.dp),
        ) {
            item(key = "head") {
                Column(Modifier.fillMaxWidth()) {
                    TextButton(onClick = { exp.back() }) { Text("‹  Back to the list", fontSize = 12.sp) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Pill(if (t.isPr) "PULL REQUEST" else "ISSUE", MaterialTheme.colorScheme.primary)
                            Pill(
                                when {
                                    t.merged -> "MERGED"
                                    t.open -> if (t.draft) "DRAFT" else "OPEN"
                                    else -> "CLOSED"
                                },
                                if (t.open) Green else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            for (l in t.labels.take(3)) Pill(l.uppercase(), Amber)
                        }
                        StarButton(exp.isFollowing(t.number)) { exp.toggleFollow(t) }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("#${t.number}  ${t.title}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Last activity ${TimeText.full(t.updated)}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { openLink(uri, t.url) }) { Text("OPEN ON GITHUB", fontSize = 12.sp) }
                        Spacer(Modifier.padding(horizontal = 4.dp))
                        OutlinedButton(onClick = { exp.toggleSummary() }, enabled = !exp.commentsBusy) {
                            Text(if (exp.summary == null) "QUICK SUMMARY" else "HIDE SUMMARY", fontSize = 12.sp)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { exp.summarizeWithAi() }, enabled = !exp.aiBusy && !exp.commentsBusy) {
                            Text(
                                when {
                                    exp.aiBusy -> "WRITING…"
                                    exp.aiText != null -> "SUMMARIZE AGAIN"
                                    else -> "SUMMARIZE WITH AI"
                                },
                                fontSize = 12.sp,
                            )
                        }
                        Spacer(Modifier.padding(horizontal = 2.dp))
                        TextButton(onClick = { exp.openKeyDialog() }) {
                            Text(if (exp.aiKeySet) "AI key" else "Set AI key", fontSize = 12.sp)
                        }
                    }
                    if (exp.aiBusy) {
                        Text("Claude is reading the page…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
                    }
                    if (exp.aiMessage.isNotEmpty()) Text(exp.aiMessage, fontSize = 12.sp, color = Red)
                    exp.aiText?.let { AiCard(it) { exp.hideAi() } }
                    if (exp.commentsBusy) {
                        Text("Loading the comments…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
                    }
                    if (exp.commentsMessage.isNotEmpty()) Text(exp.commentsMessage, fontSize = 12.sp, color = Red)
                    exp.summary?.let { s -> SummaryCard(s.lines, s.links) { openLink(uri, it) } }
                    if (files.isNotEmpty()) {
                        FilesCard(exp, files, onOpen = { openLink(uri, it) }) { post ->
                            // the first post is the second item of the page (the head is the first), comment n the (n + 1)th
                            val last = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                            scope.launch {
                                listState.animateScrollToItem((if (post == 0) 1 else 1 + post).coerceAtMost(last))
                                flash = post
                            }
                        }
                    }
                    if (settings.isNotEmpty()) SettingsCard(vm, settings)
                    Spacer(Modifier.height(10.dp))
                }
            }
            item(key = "post") {
                val body = remember(t.body) { Markdown.parse(t.body) }
                val bodyPics = remember(body) { Markdown.pictures(body) }
                Column(
                    Modifier.fillMaxWidth().then(
                        if (flash == 0) Modifier.background(ExperimentalAmber.copy(alpha = 0.14f), RoundedCornerShape(8.dp))
                        else if (Contributors.isMain(t.author)) Modifier.background(thanksTint(), RoundedCornerShape(8.dp))
                        else Modifier
                    ),
                ) {
                    Text(
                        byLine(t.author, "  ·  opened ${TimeText.full(t.created)}"),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    if (body.isEmpty()) {
                        Text("(no text)", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        MarkdownView(body, fileActions = fileActions)
                    }
                    if (bodyPics.isNotEmpty()) {
                        PictureGrid(bodyPics, Modifier.padding(top = 10.dp)) { i ->
                            exp.openGallery("First post  ·  ${t.author}  ·  ${TimeText.stamp(t.created)}", bodyPics, i)
                        }
                    }
                }
                if (!comments.isNullOrEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Text("${comments.size} comment${if (comments.size == 1) "" else "s"}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            itemsIndexed(comments ?: emptyList()) { i, c -> CommentCard(exp, c, flash == i + 1, fileActions) }
        }
        // always in reach, however far the page is scrolled: the way to take part
        Surface(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            tonalElevation = 3.dp,
            shadowElevation = 6.dp,
        ) {
            Button(
                onClick = { vm.wizard.askMode = true },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) { Text("PREPARE LOGS") }
        }
    }
    if (vm.wizard.askMode) PrepareChoiceDialog(vm, t)
    exp.inside?.let { InsideDialog(exp, it) }
    if (exp.aiKeyDialog) AiKeyDialog(exp)
    exp.textDoc?.let { doc -> TextFileDialog(doc) { exp.closeText() } }
    exp.gallery?.let { g -> GalleryDialog(g) { exp.closeGallery() } }
}

@Composable
private fun AiCard(text: String, onHide: () -> Unit) {
    var showInfo by remember { mutableStateOf(false) }
    val blocks = remember(text) { Markdown.parse(text) }
    OutlinedCard(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("AI summary", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Box {
                    InfoDot { showInfo = true }
                    if (showInfo) {
                        InfoBalloon(
                            "AI summary",
                            "Written by Claude (${com.nbtaad.editor.experimental.AiLogic.MODEL}, Anthropic) from the text of this " +
                                "page: the first post and the comments. It can make mistakes or leave things out, so read the page " +
                                "itself when it matters. The text of the page was sent to Anthropic with your key.",
                            mono = false,
                        ) { showInfo = false }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            MarkdownView(blocks)
            Text(
                "Written by Claude from the text of this page. It can make mistakes.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            TextButton(onClick = onHide, contentPadding = PaddingValues(0.dp)) { Text("Hide", fontSize = 12.sp) }
        }
    }
}

@Composable
private fun AiKeyDialog(exp: ExperimentalController) {
    var text by remember { mutableStateOf("") }
    val uri = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = { exp.closeKeyDialog() },
        title = { Text("AI summary") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "SUMMARIZE WITH AI sends the text of the open page (title, first post and comments, all public on " +
                        "GitHub) to Claude, an AI model from Anthropic, which writes a short summary. That needs your own " +
                        "Anthropic API key.",
                    fontSize = 13.sp,
                )
                Text(
                    "To get one: sign in at console.anthropic.com, add a little credit and create a key under API keys. " +
                        "Anthropic bills each summary to your account; a page usually costs about a cent or less, and the " +
                        "console shows your usage.",
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
                TextButton(
                    onClick = { openLink(uri, "https://console.anthropic.com/settings/keys") },
                    contentPadding = PaddingValues(0.dp),
                ) { Text("Open console.anthropic.com", fontSize = 12.sp) }
                Text(
                    "The key stays on this phone, in the app's private storage, and goes only to api.anthropic.com. " +
                        "It is never written to the app log.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(if (exp.aiKeySet) "New key (a key is saved)" else "API key (sk-ant-…)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
                if (exp.aiKeyNote.isNotEmpty()) Text(exp.aiKeyNote, fontSize = 12.sp, color = Red, modifier = Modifier.padding(top = 4.dp))
            }
        },
        confirmButton = { TextButton(onClick = { exp.saveKey(text) }) { Text("SAVE KEY") } },
        dismissButton = {
            Row {
                if (exp.aiKeySet) TextButton(onClick = { exp.removeKey() }) { Text("REMOVE KEY") }
                TextButton(onClick = { exp.closeKeyDialog() }) { Text("CANCEL") }
            }
        },
    )
}

@Composable
private fun SummaryCard(lines: List<String>, links: List<String>, onLink: (String) -> Unit) {
    var showInfo by remember { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("In short", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Box {
                    InfoDot { showInfo = true }
                    if (showInfo) {
                        InfoBalloon(
                            "In short",
                            "A digest made on the phone from the words of the page: who wrote it, what it says first, " +
                                "the latest comment, the settings and files it names, and its links. It is not an AI " +
                                "summary (for that, use SUMMARIZE WITH AI), so read the page itself when it matters.",
                            mono = false,
                        ) { showInfo = false }
                    }
                }
            }
            for (l in lines) Text(l, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            if (links.isNotEmpty()) {
                Text("Links", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                for (u in links) {
                    TextButton(onClick = { onLink(u) }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)) {
                        Text(
                            u.removePrefix("https://"),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** The files of the page, short: the name and what can be done with the file. [onPost] jumps to the post that shared it. */
@Composable
private fun FilesCard(exp: ExperimentalController, files: List<FileLink>, onOpen: (String) -> Unit, onPost: (Int) -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().padding(top = 10.dp), border = BorderStroke(2.dp, ExperimentalAmber)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text("Files shared on this page", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "From other people. The app can check what a file is, not whether it is safe.",
                fontSize = 11.sp,
                color = Amber,
            )
            for (f in files) {
                Text(
                    f.name,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp, bottom = 3.dp),
                )
                FileActions(exp, f, onOpen, onPost = { onPost(f.post) })
            }
        }
    }
}

/**
 * The buttons of one file, chosen by what the file is: VIEW for readable text (also for a file whose name does not say,
 * once the app has looked into it), LOOK INSIDE for a part of a build (and TRY TO INSTALL after that scan found it
 * installable, with the scan's verdict under the buttons), OPEN IN BROWSER for the rest. In the list of
 * files [onPost] is set and adds VIEW POST; under a link in a post the link itself opens the browser, so that button is
 * shown there only for a file nothing else can be done with.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FileActions(exp: ExperimentalController, f: FileLink, onOpen: (String) -> Unit, onPost: (() -> Unit)? = null) {
    val readable = exp.readable(f)
    val scan = exp.scans[f.url]
    Column {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (readable) {
                true -> MiniButton(if (exp.textBusy && exp.textFor == f.url) "OPENING…" else "VIEW", enabled = !exp.textBusy) {
                    exp.viewText(f)
                }
                null -> Text(
                    "looking into the file…",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 7.dp),
                )
                false -> {}
            }
            if (f.kind == FileKind.BUILD) {
                MiniButton(if (exp.insideBusy && exp.insideFor == f.url) "LOOKING…" else "LOOK INSIDE", enabled = !exp.insideBusy) {
                    exp.lookInside(f)
                }
            }
            // only after the app has looked inside and found the files installable; the file is not promised to work
            if (scan?.installable == true) {
                MiniButton(
                    if (exp.downloadBusy && exp.downloadFor == f.url) "GETTING…" else "TRY TO INSTALL",
                    enabled = !exp.downloadBusy && !exp.insideBusy,
                ) { exp.tryInstall(f) }
            }
            if (onPost != null || (readable == false && !f.installable)) {
                MiniButton("OPEN IN BROWSER") { onOpen(f.url) }
            }
            if (onPost != null) MiniButton("VIEW POST", onClick = onPost)
        }
        scan?.let {
            Text(
                it.verdict,
                fontSize = 11.sp,
                color = when (it.level) {
                    Recognition.Level.READY -> Green
                    Recognition.Level.NOT_HERE -> Amber
                    Recognition.Level.NO -> Red
                },
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (exp.textFor == f.url) {
            if (exp.textBusy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
            if (exp.textMessage.isNotEmpty()) Text(exp.textMessage, fontSize = 12.sp, color = Red)
        }
        if (exp.insideFor == f.url) {
            if (exp.insideBusy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
            if (exp.insideMessage.isNotEmpty()) {
                Text(exp.insideMessage, fontSize = 12.sp, color = if (exp.insideError) Red else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (exp.downloadFor == f.url) {
            if (exp.downloadBusy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
            if (exp.downloadMessage.isNotEmpty()) {
                Text(exp.downloadMessage, fontSize = 12.sp, color = if (exp.downloadError) Red else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A small outlined button: low enough to sit under a line of text without pushing the lines apart. */
@Composable
private fun MiniButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val tint = MaterialTheme.colorScheme.primary.let { if (enabled) it else it.copy(alpha = 0.38f) }
    val shape = RoundedCornerShape(8.dp)
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = tint,
        maxLines = 1,
        modifier = Modifier.clip(shape).border(1.dp, tint.copy(alpha = 0.55f), shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    )
}

@Composable
private fun SettingsCard(vm: MainViewModel, settings: List<String>) {
    val exp = vm.exp
    OutlinedCard(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("Settings mentioned", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            for (k in settings.take(12)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(k, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                    if (exp.isKnown(k)) {
                        Text("in your list", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        TextButton(onClick = { vm.addSettingFromPage(k) }) { Text("ADD", fontSize = 12.sp) }
                    }
                }
            }
            if (exp.settingNote.isNotEmpty()) Text(exp.settingNote, fontSize = 12.sp, color = Green)
        }
    }
}

@Composable
private fun CommentCard(exp: ExperimentalController, c: Comment, flash: Boolean, fileActions: @Composable (FileLink) -> Unit) {
    var expanded by remember(c) { mutableStateOf(false) }
    val full = remember(c) { Markdown.parse(c.body) }
    val short = remember(full) { Markdown.shorten(full) }
    val pics = remember(full) { Markdown.pictures(full) }
    val main = Contributors.isMain(c.author)
    val blue = thanksBlue()
    val blueTint = thanksTint()
    OutlinedCard(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = if (main) CardDefaults.outlinedCardColors(containerColor = blueTint) else CardDefaults.outlinedCardColors(),
        border = when {
            flash -> BorderStroke(2.dp, ExperimentalAmber)
            main -> BorderStroke(1.5.dp, blue.copy(alpha = 0.75f))
            else -> CardDefaults.outlinedCardBorder()
        },
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                byLine(c.author, "  ·  ${TimeText.full(c.created)}"),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            if (full.isEmpty()) {
                Text("(no text)", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                MarkdownView(if (expanded || !short.second) full else short.first, fileActions = fileActions)
            }
            if (pics.isNotEmpty()) {
                PictureGrid(pics, Modifier.padding(top = 8.dp)) { i ->
                    exp.openGallery("Comment  ·  ${c.author}  ·  ${TimeText.stamp(c.created)}", pics, i)
                }
            }
            if (short.second) {
                TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                    Text(if (expanded) "Show less" else "Show more", fontSize = 12.sp)
                }
            }
        }
    }
}
