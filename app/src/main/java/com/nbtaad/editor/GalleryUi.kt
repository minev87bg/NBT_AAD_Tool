package com.nbtaad.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nbtaad.editor.experimental.ExperimentalController
import com.nbtaad.editor.experimental.MdPicture
import com.nbtaad.editor.experimental.Pictures
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.net.Uri
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.VideoView

// =========================================================================
// Text files (logs, notes) in a window
// =========================================================================

/**
 * A log, note or text in a small window in the middle of the screen (at most 70% of its width and height): small
 * monospace text, lines not wrapped, scrolling down and sideways with a scrollbar for each direction, and COPY at the
 * top right to paste it somewhere else (into an AI chat, say). The file is read into memory only; nothing is saved
 * on the phone.
 */
@Composable
internal fun TextFileDialog(doc: ExperimentalController.TextDoc, showCopy: Boolean = true, onClose: () -> Unit) {
    val cfg = LocalConfiguration.current
    val density = LocalDensity.current
    val maxW = (cfg.screenWidthDp * 0.7f).dp
    val maxH = (cfg.screenHeightDp * 0.7f).dp
    val clip = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var copied by remember { mutableStateOf("") }
    // a very long single line is cut for the display only (COPY still copies everything that was read)
    val lines = remember(doc) {
        doc.text.lines().map { l -> l.replace("\t", "    ").let { if (it.length > 4000) it.take(4000) + "…" else it } }
    }
    // shown in pieces of 40 lines, so a long log scrolls smoothly
    val chunks = remember(lines) { lines.chunked(40).map { it.joinToString("\n") } }
    // lines are not wrapped: the text is as wide as its widest line, and the whole text scrolls sideways together
    val style = remember { TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp) }
    val measurer = rememberTextMeasurer()
    val textW = remember(lines) {
        val widest = lines.maxByOrNull { it.length } ?: ""
        with(density) { measurer.measure(widest, style, softWrap = false).size.width.toDp() }
    }
    // the scrollbars have strips of their own at the right and bottom edge; the text never goes under them
    val contentW = maxOf(maxW - BAR_SIDE, textW + 28.dp).coerceAtMost(with(density) { 200000f.toDp() })
    val down = rememberLazyListState()
    val across = rememberScrollState()
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.width(maxW).heightIn(max = maxH),
            shape = RoundedCornerShape(14.dp),
            tonalElevation = 6.dp,
        ) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(doc.name) }
                            withStyle(SpanStyle(fontSize = 10.sp, color = muted)) {
                                append("  ·  ${lines.size} line${if (lines.size == 1) "" else "s"}")
                            }
                        },
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (showCopy) TextButton(
                        onClick = {
                            copied = try {
                                clip.setText(AnnotatedString(doc.text))
                                "COPIED"
                            } catch (e: Exception) {
                                "TOO BIG"
                            }
                            scope.launch {
                                delay(1800)
                                copied = ""
                            }
                        },
                    ) { Text(copied.ifEmpty { "COPY" }, fontSize = 12.sp) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Box(Modifier.weight(1f, fill = false)) {
                    Box(Modifier.padding(end = BAR_SIDE, bottom = BAR_BOTTOM).horizontalScroll(across)) {
                        LazyColumn(
                            Modifier.width(contentW),
                            state = down,
                            contentPadding = PaddingValues(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                        ) {
                            if (chunks.isEmpty() || doc.text.isBlank()) {
                                item { Text("(the file is empty)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                            items(chunks) { c ->
                                Text(c, style = style, softWrap = false)
                            }
                            if (doc.cut) {
                                item {
                                    Text(
                                        "Only the first 800 KB are shown and copied. Open the file in the browser for the rest.",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 8.dp).widthIn(max = maxW - BAR_SIDE - 28.dp),
                                    )
                                }
                            }
                        }
                    }
                    // the scrollbars: drag one, or touch it, to go to a place in the file
                    Box(Modifier.matchParentSize()) {
                        DownScrollbar(down, Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(BAR_SIDE).padding(bottom = BAR_BOTTOM))
                        AcrossScrollbar(across, Modifier.align(Alignment.BottomStart).fillMaxWidth().height(BAR_BOTTOM).padding(end = BAR_SIDE))
                    }
                }
            }
        }
    }
}

// the strips along the right and bottom edge of the text window that belong to the scrollbars
private val BAR_SIDE = 24.dp
private val BAR_BOTTOM = 14.dp

// the size of what is shown in the list, worked out from the items on the screen (the pieces of a file are all alike)
private class ListSize(val piece: Float, val content: Float, val viewport: Float)

private fun listSize(state: LazyListState): ListSize? {
    val info = state.layoutInfo
    val shown = info.visibleItemsInfo
    if (info.totalItemsCount == 0 || shown.isEmpty()) return null
    val piece = shown.maxOf { it.size }.toFloat()
    val content = piece * info.totalItemsCount
    val viewport = info.viewportSize.height.toFloat()
    return if (content <= viewport + 1f) null else ListSize(piece, content, viewport)
}

/** The top and the height of the thumb on a track [track] long. */
private fun listThumb(state: LazyListState, m: ListSize, track: Float, minThumb: Float): Pair<Float, Float> {
    val h = (track * m.viewport / m.content).coerceIn(minOf(minThumb, track), track)
    val frac = when {
        !state.canScrollBackward -> 0f
        !state.canScrollForward -> 1f
        else -> ((state.firstVisibleItemIndex * m.piece + state.firstVisibleItemScrollOffset) / (m.content - m.viewport)).coerceIn(0f, 1f)
    }
    return (frac * (track - h)) to h
}

/** The scrollbar at the right edge of the text. */
@Composable
private fun DownScrollbar(state: LazyListState, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val scale = LocalDensity.current.density
    val color = MaterialTheme.colorScheme.onSurface
    var track by remember { mutableFloatStateOf(0f) }
    var active by remember { mutableStateOf(false) }
    fun jump(y: Float) {
        val m = listSize(state) ?: return
        val (_, h) = listThumb(state, m, track, 36f * scale)
        if (track - h < 1f) return
        val frac = ((y - h / 2f) / (track - h)).coerceIn(0f, 1f)
        val pos = frac * (m.content - m.viewport)
        val index = (pos / m.piece).toInt().coerceIn(0, state.layoutInfo.totalItemsCount - 1)
        val offset = (pos - index * m.piece).toInt().coerceAtLeast(0)
        scope.launch { state.scrollToItem(index, offset) }
    }
    Box(
        modifier
            .onSizeChanged { track = it.height.toFloat() }
            .pointerInput(Unit) { detectTapGestures { jump(it.y) } }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = {
                        active = true
                        jump(it.y)
                    },
                    onDragEnd = { active = false },
                    onDragCancel = { active = false },
                    onVerticalDrag = { change, _ ->
                        change.consume()
                        jump(change.position.y)
                    },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val m = listSize(state) ?: return@Canvas
            val (top, h) = listThumb(state, m, size.height, 36.dp.toPx())
            val w = (if (active) 6.dp else 4.dp).toPx()
            drawRoundRect(
                color.copy(alpha = if (active) 0.75f else 0.4f),
                topLeft = Offset(size.width - w - 3.dp.toPx(), top),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2f),
            )
        }
    }
}

/** The scrollbar along the bottom edge of the text: the text is not wrapped, so long lines are reached by it. */
@Composable
private fun AcrossScrollbar(state: ScrollState, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val scale = LocalDensity.current.density
    val color = MaterialTheme.colorScheme.onSurface
    var track by remember { mutableFloatStateOf(0f) }
    var active by remember { mutableStateOf(false) }
    fun jump(x: Float) {
        val max = state.maxValue
        if (max <= 0 || max == Int.MAX_VALUE || track <= 0f) return
        val w = (track * track / (track + max)).coerceIn(minOf(36f * scale, track), track)
        if (track - w < 1f) return
        val frac = ((x - w / 2f) / (track - w)).coerceIn(0f, 1f)
        scope.launch { state.scrollTo((frac * max).toInt()) }
    }
    Box(
        modifier
            .onSizeChanged { track = it.width.toFloat() }
            .pointerInput(Unit) { detectTapGestures { jump(it.x) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        active = true
                        jump(it.x)
                    },
                    onDragEnd = { active = false },
                    onDragCancel = { active = false },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        jump(change.position.x)
                    },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val max = state.maxValue
            if (max <= 0 || max == Int.MAX_VALUE) return@Canvas
            val w = (size.width * size.width / (size.width + max)).coerceIn(minOf(36.dp.toPx(), size.width), size.width)
            val left = state.value.toFloat() / max * (size.width - w)
            val t = (if (active) 6.dp else 4.dp).toPx()
            drawRoundRect(
                color.copy(alpha = if (active) 0.75f else 0.4f),
                topLeft = Offset(left, size.height - t - 3.dp.toPx()),
                size = Size(w, t),
                cornerRadius = CornerRadius(t / 2f),
            )
        }
    }
}

// =========================================================================
// Pictures: a grid under the post, and a gallery
// =========================================================================

/** A picture from a page, loaded into memory at the size that is needed. */
@Composable
internal fun PictureImage(url: String, maxSide: Int, modifier: Modifier, contentScale: ContentScale) {
    val ctx = LocalContext.current.applicationContext
    var bmp by remember(url, maxSide) { mutableStateOf<ImageBitmap?>(Pictures.cached(url, maxSide)?.asImageBitmap()) }
    var failed by remember(url, maxSide) { mutableStateOf(false) }
    LaunchedEffect(url, maxSide) {
        if (bmp == null) {
            val b = Pictures.load(ctx, url, maxSide)
            if (b != null) bmp = b.asImageBitmap() else failed = true
        }
    }
    val img = bmp
    var playing by remember(url) { mutableStateOf(false) }
    if (playing) VideoDialog(url) { playing = false }
    when {
        img != null -> Image(bitmap = img, contentDescription = null, modifier = modifier, contentScale = contentScale)
        // GitHub serves a video attached to a post at the same kind of address as a picture: it is played instead
        failed && Pictures.isVideo(url) -> Box(
            modifier.background(Color(0xFF14181F)).clickable { playing = true },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("▶", fontSize = 30.sp, color = Color.White)
                Text("PLAY VIDEO", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFE9A33A))
            }
        }
        failed -> Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                "Cannot show this picture here",
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(6.dp),
            )
        }
        else -> Box(modifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        }
    }
}

/** The pictures of one post as small squares, three in a row; tapping one opens the gallery of this post. */
@Composable
internal fun PictureGrid(pictures: List<MdPicture>, modifier: Modifier = Modifier, onOpen: (Int) -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    val border = MaterialTheme.colorScheme.outline
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            if (pictures.size == 1) "1 picture" else "${pictures.size} pictures",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        pictures.chunked(3).forEachIndexed { row, group ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((col, pic) in group.withIndex()) {
                    Box(
                        Modifier.weight(1f).aspectRatio(1f).clip(shape).border(1.dp, border, shape)
                            .clickable { onOpen(row * 3 + col) },
                    ) {
                        PictureImage(pic.url, 360, Modifier.fillMaxSize(), ContentScale.Crop)
                    }
                }
                for (k in group.size until 3) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * The pictures of ONE post, full screen like a phone's gallery: swipe left and right for the previous and next one of
 * this post, pinch or double-tap to zoom (drag to move around while zoomed). The top bar says which post they belong to.
 */
@Composable
internal fun GalleryDialog(g: ExperimentalController.Gallery, onClose: () -> Unit) {
    val uri = LocalUriHandler.current
    val pager = rememberPagerState(initialPage = g.index) { g.pictures.size }
    var zoomed by remember { mutableStateOf(false) }
    LaunchedEffect(pager.currentPage) { zoomed = false }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black, contentColor = Color.White) {
            Box(Modifier.fillMaxSize()) {
                HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), userScrollEnabled = !zoomed) { page ->
                    ZoomablePicture(g.pictures[page].url, active = pager.currentPage == page) { z ->
                        if (pager.currentPage == page) zoomed = z
                    }
                }
                val current = g.pictures[pager.currentPage.coerceIn(0, g.pictures.size - 1)]
                Column(
                    Modifier.align(Alignment.TopStart).fillMaxWidth().background(Color.Black.copy(alpha = 0.55f))
                        .safeDrawingPadding().padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${pager.currentPage + 1} / ${g.pictures.size}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { openLink(uri, current.url) }) {
                            Text("OPEN IN BROWSER", fontSize = 11.sp, color = Color(0xFFE9A33A))
                        }
                        TextButton(onClick = onClose) { Text("CLOSE", fontSize = 12.sp, color = Color(0xFFE9A33A)) }
                    }
                    Text(g.title, fontSize = 12.sp, color = Color(0xFFD0D0D0))
                    if (current.alt.isNotBlank()) Text(current.alt, fontSize = 11.sp, color = Color(0xFF9E9E9E), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** One picture of the gallery: pinch to zoom, double-tap to zoom in or back, drag to move while zoomed. */
@Composable
private fun ZoomablePicture(url: String, active: Boolean, onZoomed: (Boolean) -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(active) {
        if (!active) {
            scale = 1f
            offset = Offset.Zero
        }
    }
    LaunchedEffect(scale) { onZoomed(scale > 1.01f) }
    Box(
        Modifier.fillMaxSize()
            .onSizeChanged { box = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1.01f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                // two fingers always zoom; one finger moves the picture only while it is zoomed in, so at normal size
                // a swipe goes on to the pager (previous / next picture)
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        if (fingers >= 2 || scale > 1.01f) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            if (zoom != 1f || pan != Offset.Zero) {
                                val newScale = (scale * zoom).coerceIn(1f, 6f)
                                val c = event.calculateCentroid(useCurrent = false) - Offset(box.width / 2f, box.height / 2f)
                                val ratio = newScale / scale
                                val maxX = box.width * (newScale - 1f) / 2f
                                val maxY = box.height * (newScale - 1f) / 2f
                                val raw = c + pan - (c - offset) * ratio
                                offset = if (newScale <= 1.01f) Offset.Zero else Offset(raw.x.coerceIn(-maxX, maxX), raw.y.coerceIn(-maxY, maxY))
                                scale = newScale
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        PictureImage(
            url, 3072,
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
            ContentScale.Fit,
        )
    }
}

// =========================================================================
// Video
// =========================================================================

/**
 * A video from a page, played in a window: it is loaded into the app's cache folder first (the phone's player needs a
 * file), then played with the phone's own video player. PAUSE / PLAY and the replay button are below the picture.
 */
@Composable
internal fun VideoDialog(url: String, onClose: () -> Unit) {
    val ctx = LocalContext.current.applicationContext
    val uri = LocalUriHandler.current
    var file by remember(url) { mutableStateOf<java.io.File?>(null) }
    var loaded by remember(url) { mutableStateOf(0L) }
    var error by remember(url) { mutableStateOf("") }
    var paused by remember(url) { mutableStateOf(false) }
    val player = remember(url) { mutableStateOf<VideoView?>(null) }
    LaunchedEffect(url) {
        try {
            file = Pictures.videoFile(ctx, url) { loaded = it }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            com.nbtaad.editor.log.AppLog.w("Pictures", "Video could not be loaded: ${e.javaClass.simpleName}: ${e.message}: $url")
            error = e.message ?: "The video could not be loaded."
        }
    }
    DisposableEffect(url) { onDispose { player.value?.stopPlayback() } }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black, contentColor = Color.White) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Video", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { openLink(uri, url) }) { Text("OPEN IN BROWSER", fontSize = 11.sp, color = Color(0xFFE9A33A)) }
                    TextButton(onClick = onClose) { Text("CLOSE", fontSize = 12.sp, color = Color(0xFFE9A33A)) }
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val f = file
                    when {
                        error.isNotEmpty() -> Text(error, fontSize = 13.sp, color = Color(0xFFFF8A80), modifier = Modifier.padding(24.dp), textAlign = TextAlign.Center)
                        f == null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                            Text(
                                if (loaded > 0) "Loading the video… ${loaded / 1024} KB" else "Loading the video…",
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 10.dp),
                            )
                        }
                        else -> AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { c ->
                                val frame = FrameLayout(c)
                                val vv = VideoView(c)
                                vv.setOnPreparedListener { it.isLooping = false; vv.start() }
                                vv.setOnErrorListener { _, _, _ ->
                                    error = "The phone cannot play this video. Open it in the browser instead."
                                    true
                                }
                                vv.setVideoURI(Uri.fromFile(f))
                                frame.addView(
                                    vv,
                                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER),
                                )
                                player.value = vv
                                frame
                            },
                        )
                    }
                }
                if (file != null && error.isEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.Center) {
                        TextButton(onClick = {
                            player.value?.let { if (paused) it.start() else it.pause() }
                            paused = !paused
                        }) { Text(if (paused) "PLAY" else "PAUSE", fontSize = 13.sp, color = Color(0xFFE9A33A)) }
                        TextButton(onClick = {
                            player.value?.let { it.seekTo(0); it.start() }
                            paused = false
                        }) { Text("FROM THE START", fontSize = 13.sp, color = Color(0xFFE9A33A)) }
                    }
                }
            }
        }
    }
}
