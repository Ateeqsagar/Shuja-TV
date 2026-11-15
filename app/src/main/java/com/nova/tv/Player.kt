package com.nova.tv

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.LruCache
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private fun fmt(ms: Long): String {
    val s = ms / 1000; val h = s / 3600; val m = s % 3600 / 60; val x = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, x) else "%02d:%02d".format(m, x)
}

/** True when a preview picture is (almost) completely black, so it is not worth showing. */
private fun isMostlyBlack(b: Bitmap): Boolean {
    return try {
        var sum = 0L; var n = 0
        val sx = (b.width / 8).coerceAtLeast(1); val sy = (b.height / 8).coerceAtLeast(1)
        var y = sy / 2
        while (y < b.height) {
            var x = sx / 2
            while (x < b.width) {
                val c = b.getPixel(x, y)
                sum += ((c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)) / 3
                n++
                x += sx
            }
            y += sy
        }
        n > 0 && sum / n < 10
    } catch (e: Exception) { false }
}

/** One preview picture from the stream at the given time. Returns null if the server or format does not allow it. */
private fun grabFrame(holder: Array<MediaMetadataRetriever?>, url: String, ua: String, ms: Long): Bitmap? {
    return try {
        var r = holder[0]
        if (r == null) {
            r = MediaMetadataRetriever()
            val headers = HashMap<String, String>()
            headers["User-Agent"] = ua
            r.setDataSource(url, headers)
            holder[0] = r
        }
        val bmp = (if (Build.VERSION.SDK_INT >= 27) {
            r.getScaledFrameAtTime(ms * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180)
        } else {
            r.getFrameAtTime(ms * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }) ?: return null
        val w = 320
        val out = if (bmp.width > w) {
            val h = (bmp.height * w / bmp.width).coerceAtLeast(1)
            Bitmap.createScaledBitmap(bmp, w, h, true)
        } else bmp
        if (isMostlyBlack(out)) null else out
    } catch (e: Exception) {
        null
    }
}

/**
 * Buffers are capped by size (not only by time) so a 4K stream can never eat the whole heap
 * on a TV with limited RAM.
 */
private fun buildPlayer(ctx: Context, cfg: Cfg, ua: String): ExoPlayer {
    val b = when (cfg.buffer) {
        0 -> intArrayOf(2500, 12000, 1000, 1500)
        2 -> intArrayOf(15000, 60000, 2500, 5000)
        else -> intArrayOf(5000, 30000, 1500, 3000)
    }
    val load = DefaultLoadControl.Builder().setBufferDurationsMs(b[0], b[1], b[2], b[3]).build()
    val rf = DefaultRenderersFactory(ctx).setEnableDecoderFallback(true)
    val http = DefaultHttpDataSource.Factory().setUserAgent(ua)
        .setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(10000).setReadTimeoutMs(15000)
    val p = ExoPlayer.Builder(ctx, rf).setLoadControl(load)
        .setMediaSourceFactory(DefaultMediaSourceFactory(http))
        .setAudioAttributes(AudioAttributes.DEFAULT, true)
        .setHandleAudioBecomingNoisy(true).build()
    p.setSeekParameters(SeekParameters.CLOSEST_SYNC)
    var tp = p.trackSelectionParameters.buildUpon()
    if (cfg.quality == 1) tp = tp.setMaxVideoSize(1920, 1080)
    else if (cfg.quality == 2) tp = tp.setMaxVideoSize(1280, 720)
    val lang = AUDIO_CODES.getOrElse(cfg.audio) { "" }
    if (lang.isNotEmpty()) tp = tp.setPreferredAudioLanguage(lang)
    if (!cfg.subs) tp = tp.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
    p.trackSelectionParameters = tp.build()
    return p
}

/** Full-screen window on top of the app, so the lists underneath keep their scroll position and focus. */
@Composable
fun PlayerScreen(list: List<Item>, start: Int, vm: Vm, onExit: () -> Unit) {
    Dialog(
        onDismissRequest = onExit,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        PlayerBody(list, start, vm, onExit)
    }
}

@Composable
private fun PlayerBody(list: List<Item>, start: Int, vm: Vm, onExit: () -> Unit) {
    val ctx = LocalContext.current
    val tv = rememberIsTv()
    var isPlay by remember { mutableStateOf(true) }
    var menuOpen by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }
    val okLong = remember { booleanArrayOf(false) }
    var idx by remember { mutableIntStateOf(start.coerceIn(0, list.lastIndex)) }
    val item = list[idx]
    val cur by rememberUpdatedState(item)
    var buffering by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var show by remember { mutableStateOf(true) }
    var ping by remember { mutableIntStateOf(0) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var tries by remember { mutableIntStateOf(0) }
    var firstStart by remember { mutableStateOf(true) }
    val fr = remember { FocusRequester() }
    val cfg = vm.cfg
    var scrub by remember { mutableStateOf<Long?>(null) }
    var thumb by remember { mutableStateOf<Bitmap?>(null) }
    // After a jump is applied, the seek card stays up until the picture is back, so the screen never shows plain black.
    var hold by remember { mutableStateOf<Long?>(null) }
    var fails by remember { mutableIntStateOf(0) }
    val holder = remember { arrayOfNulls<MediaMetadataRetriever>(1) }
    // Preview pictures already fetched (one per 2 seconds of video), so repeated jumps show instantly.
    val cache = remember { LruCache<Long, Bitmap>(48) }
    var scrubDir by remember { mutableIntStateOf(1) }
    val lock = remember { Mutex() }
    val fwdMs = SEEK_STEPS[cfg.seekF.coerceIn(0, SEEK_STEPS.lastIndex)] * 1000L
    val backMs = SEEK_STEPS[cfg.seekB.coerceIn(0, SEEK_STEPS.lastIndex)] * 1000L

    val player = remember { buildPlayer(ctx, vm.cfg, vm.ua()) }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(s: Int) {
                buffering = s == Player.STATE_BUFFERING
                if (s == Player.STATE_READY) { error = null; tries = 0; hold = null; thumb = null }
                if (s == Player.STATE_ENDED && !cur.live && vm.cfg.autoNext && idx + 1 < list.size) idx += 1
            }
            override fun onPlayWhenReadyChanged(w: Boolean, r: Int) { isPlay = w }
            override fun onPlayerError(e: PlaybackException) {
                tries++
                val c = cur
                val preferTs = vm.cfg.liveFormat == 1
                if (tries > 3) { error = "Can't play this stream (${e.errorCodeName})"; return }
                val u = if (c.live && tries == 1) vm.urlOf(c, !preferTs) else vm.urlOf(c, preferTs)
                player.setMediaItem(MediaItem.fromUri(u))
                player.prepare()
            }
        }
        player.addListener(l)
        onDispose {
            try {
                if (!cur.live) vm.savePos(cur, player.currentPosition, player.duration.coerceAtLeast(0L))
            } catch (e: Exception) { }
            player.removeListener(l)
            player.release()
            CoroutineScope(Dispatchers.IO).launch {
                lock.withLock {
                    try { holder[0]?.release() } catch (e: Exception) { }
                    holder[0] = null
                }
            }
        }
    }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP) player.pause() else if (e == Lifecycle.Event.ON_START) player.play()
        }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }

    LaunchedEffect(idx) {
        tries = 0; error = null; buffering = true; ping++
        val u = vm.urlOf(item, vm.cfg.liveFormat == 1)
        val at = if (firstStart) vm.takeStartAt().coerceAtLeast(0L) else 0L
        firstStart = false
        if (at > 0) player.setMediaItem(MediaItem.fromUri(u), at) else player.setMediaItem(MediaItem.fromUri(u))
        player.prepare()
        player.playWhenReady = true
    }
    LaunchedEffect(idx) {
        if (!item.live) {
            while (true) {
                delay(10_000)
                vm.savePos(item, player.currentPosition, player.duration.coerceAtLeast(0L))
            }
        }
    }
    LaunchedEffect(idx) {
        scrub = null; thumb = null; hold = null; fails = 0
        cache.evictAll()
        lock.withLock {
            withContext(Dispatchers.IO) {
                try { holder[0]?.release() } catch (e: Exception) { }
                holder[0] = null
            }
        }
        // Open the preview source right away so the first jump is already fast.
        if (!item.live && cfg.preview) {
            delay(1500)
            lock.withLock {
                withContext(Dispatchers.IO) { grabFrame(holder, vm.urlOf(item), vm.ua(), 0L) }
            }
        }
    }
    // Seek is applied a moment after the last press, so several presses add up and the preview can follow.
    LaunchedEffect(scrub) {
        val t = scrub
        if (t != null) {
            delay(700)
            player.seekTo(t)
            pos = t
            hold = t
            scrub = null
        }
    }
    LaunchedEffect(hold) {
        if (hold != null) { delay(3000); hold = null; thumb = null }
    }
    // Picture for the jump target: from the cache at once, otherwise fetched right away (nearest cached one shown meanwhile).
    LaunchedEffect(scrub) {
        val t = scrub
        if (t != null && cfg.preview && fails < 8 && !item.live) {
            val key = t / 2000L
            val hit = cache.get(key)
            if (hit != null) {
                thumb = hit
            } else {
                var near: Bitmap? = null
                for (d in 1..6) {
                    near = cache.get(key - d) ?: cache.get(key + d)
                    if (near != null) break
                }
                if (near != null) thumb = near
                delay(50)
                val bmp = lock.withLock {
                    withContext(Dispatchers.IO) { grabFrame(holder, vm.urlOf(item), vm.ua(), t) }
                }
                if (bmp != null) { cache.put(key, bmp); thumb = bmp; fails = 0 } else fails++
            }
        }
    }
    // While jumping, pictures for the next few steps in the same direction are fetched in the background.
    LaunchedEffect(scrub, hold) {
        val t = scrub ?: hold
        if (t != null && cfg.preview && fails < 8 && !item.live) {
            delay(250)
            val total = player.duration.coerceAtLeast(0L)
            val step = if (scrubDir > 0) fwdMs else backMs
            for (k in 1..6) {
                val pt = (t + scrubDir * step * k).coerceIn(0L, if (total > 0L) total else Long.MAX_VALUE)
                val key = pt / 2000L
                if (cache.get(key) == null) {
                    val b = lock.withLock {
                        withContext(Dispatchers.IO) { grabFrame(holder, vm.urlOf(item), vm.ua(), pt) }
                    }
                    if (b != null) cache.put(key, b)
                }
            }
        }
    }
    LaunchedEffect(ping) { show = true; delay(4000); show = false }
    LaunchedEffect(show, idx) {
        while (show && !item.live) { pos = player.currentPosition; dur = player.duration.coerceAtLeast(0L); delay(500) }
    }
    LaunchedEffect(Unit) { fr.grab() }

    val epg = vm.epgMap[item.id]
    val prog = epg?.now(vm.now)
    val nxt = epg?.next(vm.now)
    LaunchedEffect(idx) {
        vm.loadEpg(list[idx])
        if (list.size > 1) {
            vm.loadEpg(list[(idx + 1) % list.size])
            vm.loadEpg(list[(idx - 1 + list.size) % list.size])
        }
    }
    LaunchedEffect(vm.now / 60_000) { vm.loadEpg(cur) }

    fun step(d: Int): Int {
        var i = idx
        repeat(list.size) {
            i = (i + d + list.size) % list.size
            val c = list[i]
            if (vm.unlocked || (!vm.isLocked(c) && !vm.isHidden(c))) return i
        }
        return idx
    }

    fun seekBy(dir: Int) {
        if (cur.live) return
        val d = player.duration.coerceAtLeast(0L)
        val base = scrub ?: player.currentPosition
        scrubDir = dir
        scrub = (base + dir * (if (dir > 0) fwdMs else backMs)).coerceIn(0L, if (d > 0L) d else Long.MAX_VALUE)
        ping++
    }

    fun okPress() {
        val t = scrub
        if (t != null) {
            player.seekTo(t)
            pos = t
            hold = t
            scrub = null
        } else if (!cur.live) {
            player.playWhenReady = !player.playWhenReady
        }
        ping++
    }

    LaunchedEffect(menuOpen) { if (!menuOpen) fr.grab() }
    val showNow by rememberUpdatedState(show)
    val mode = when (cfg.aspect) {
        1 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        2 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(fr).focusable()
            // Touch: tap shows or hides the controls; double-tap the left or right half to jump back or forward.
            .pointerInput(idx) {
                detectTapGestures(
                    onTap = { val shown = showNow; if (shown) show = false; if (!shown) ping++ },
                    onDoubleTap = { o -> if (!cur.live) seekBy(if (o.x > size.width / 2f) 1 else -1) }
                )
            }
            .onPreviewKeyEvent { e ->
            val k = e.key
            val dpad = k == Key.DirectionUp || k == Key.DirectionDown || k == Key.DirectionLeft ||
                k == Key.DirectionRight || k == Key.DirectionCenter || k == Key.Enter || k == Key.NumPadEnter
            val isOk = k == Key.DirectionCenter || k == Key.Enter || k == Key.NumPadEnter
            if (isOk) {
                if (e.type == KeyEventType.KeyDown) {
                    okLong[0] = e.nativeKeyEvent.repeatCount > 0
                    return@onPreviewKeyEvent true
                }
                if (e.type == KeyEventType.KeyUp) {
                    val wasLong = okLong[0]
                    okLong[0] = false
                    if (wasLong) { menuOpen = true; ping++ } else okPress()
                    return@onPreviewKeyEvent true
                }
            }
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent dpad
            when (k) {
                Key.DirectionUp, Key.ChannelUp -> { if (item.live) idx = step(1); ping++; true }
                Key.DirectionDown, Key.ChannelDown -> { if (item.live) idx = step(-1); ping++; true }
                Key.DirectionRight -> {
                    if (!item.live) {
                        val d = player.duration.coerceAtLeast(0L)
                        val base = scrub ?: player.currentPosition
                        scrubDir = 1
                        scrub = (base + fwdMs).coerceIn(0L, if (d > 0L) d else Long.MAX_VALUE)
                    }
                    ping++; true
                }
                Key.DirectionLeft -> {
                    if (!item.live) {
                        val base = scrub ?: player.currentPosition
                        scrubDir = -1
                        scrub = (base - backMs).coerceAtLeast(0L)
                    }
                    ping++; true
                }
                Key.MediaPlayPause -> { okPress(); true }
                Key.Menu -> { menuOpen = true; ping++; true }
                else -> false
            }
        }
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                PlayerView(it).apply {
                    this.player = player
                    useController = false
                    isFocusable = false
                    isFocusableInTouchMode = false
                    keepScreenOn = true
                    setKeepContentOnPlayerReset(true)
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { v -> v.resizeMode = mode }
        )

        if (buffering && error == null) CircularProgressIndicator(Modifier.align(Alignment.Center).size(56.dp), color = Red, strokeWidth = 5.dp)
        error?.let { Text(it, color = Red, fontSize = 22.sp, textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center)) }

        val sc = scrub ?: hold
        if (sc != null && !item.live) {
            val total = player.duration.coerceAtLeast(0L)
            val pf = if (total > 0L) (sc.toFloat() / total).coerceIn(0f, 1f) else 0f
            val edge = pf.coerceIn(0.05f, 0.80f)
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 48.dp, end = 48.dp, bottom = 175.dp)) {
                Spacer(Modifier.weight(edge))
                SeekCard(thumb, sc, player.currentPosition, total, scrub == null)
                Spacer(Modifier.weight(1f - edge))
            }
        }

        if (menuOpen) {
            PlayerSettings(
                player = player, vm = vm, live = item.live, title = item.name, speed = speed,
                onSpeed = { v -> speed = v; player.playbackParameters = PlaybackParameters(v) },
                onClose = { menuOpen = false; ping++ }
            )
        }

        if (!tv) {
            AnimatedVisibility(show, Modifier.align(Alignment.Center), enter = fadeIn(), exit = fadeOut()) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!item.live) {
                        TouchBtn("«  ${backMs / 1000}s", 60) { seekBy(-1) }
                        TouchBtn(if (isPlay) "❚❚" else "▶", 80) { player.playWhenReady = !player.playWhenReady; ping++ }
                        TouchBtn("${fwdMs / 1000}s  »", 60) { seekBy(1) }
                    } else {
                        TouchBtn("‹  Previous", 60) { idx = step(-1); ping++ }
                        TouchBtn("Next  ›", 60) { idx = step(1); ping++ }
                    }
                }
            }
            AnimatedVisibility(show, Modifier.align(Alignment.TopEnd).padding(20.dp), enter = fadeIn(), exit = fadeOut()) {
                TouchBtn("✕", 48) { onExit() }
            }
        }

        AnimatedVisibility(show, Modifier.align(Alignment.BottomCenter), enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut()) {
            Row(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(.92f))))
                    .padding(horizontal = 48.dp, vertical = 36.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (item.icon != null) {
                    AsyncImage(model = item.icon, contentDescription = null, contentScale = ContentScale.Fit,
                        modifier = Modifier.size(84.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(.08f)))
                    Spacer(Modifier.width(20.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(item.name, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                    if (item.live) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("LIVE", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Black,
                                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Red).padding(horizontal = 8.dp, vertical = 2.dp))
                            Text("   ${idx + 1} / ${list.size}", color = Dim, fontSize = 16.sp)
                        }
                        if (prog != null) {
                            Spacer(Modifier.height(6.dp))
                            Text("Now: ${prog.title}", color = Color.White, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (prog.desc.isNotBlank()) Text(prog.desc, color = Dim, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val pf = ((vm.now - prog.start * 1000f) / ((prog.end - prog.start) * 1000f)).coerceIn(0f, 1f)
                            Spacer(Modifier.height(4.dp))
                            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(.2f))) {
                                Box(Modifier.fillMaxWidth(pf).fillMaxHeight().background(RedBrush))
                            }
                            nxt?.let { Text("Next: ${it.title}", color = Dim, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    } else {
                        val shownPos = scrub ?: pos
                        val frac = if (dur > 0) (shownPos.toFloat() / dur).coerceIn(0f, 1f) else 0f
                        // Touch: tap or drag on the bar to jump to that point.
                        Box(
                            Modifier.fillMaxWidth().height(26.dp)
                                .pointerInput(dur) {
                                    detectTapGestures { o -> if (dur > 0L) { scrubDir = if (o.x / size.width * dur > pos) 1 else -1; scrub = (o.x / size.width * dur).toLong().coerceIn(0L, dur); ping++ } }
                                }
                                .pointerInput(dur) {
                                    detectHorizontalDragGestures { change, _ ->
                                        if (dur > 0L) {
                                            val t = (change.position.x / size.width * dur).toLong().coerceIn(0L, dur)
                                            scrubDir = if (t > pos) 1 else -1
                                            scrub = t; ping++
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(.2f))) {
                                Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(RedBrush))
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Text("${fmt(shownPos)} / ${fmt(dur)}", color = Dim, fontSize = 16.sp)
                    }
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(.14f))
                            .border(1.dp, Color.White.copy(.3f), RoundedCornerShape(50))
                            .clickable { menuOpen = true; ping++ }.padding(horizontal = 16.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        GearIcon(Modifier.size(22.dp))
                        Text("Settings", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                    if (tv) Text(
                        if (item.live) "▲ ▼  channel     Hold OK  settings" else "◀ ${backMs / 1000}s   ${fwdMs / 1000}s ▶     OK  pause     Hold OK  settings",
                        color = Dim, fontSize = 14.sp
                    )
                }
            }
        }
    }
}

/** Preview card shown while jumping: picture (when available), how far, where to, and a position bar. */
@Composable
private fun SeekCard(thumb: Bitmap?, target: Long, current: Long, total: Long, landing: Boolean) {
    val delta = (target - current) / 1000L
    val fwd = delta >= 0L
    val a = if (delta < 0L) -delta else delta
    val dText = if (a >= 60L) "${a / 60}m ${a % 60}s" else "${a}s"
    val accent = if (fwd) Color(0xFF46D369) else Color(0xFFE87C03)
    val frac = if (total > 0L) (target.toFloat() / total).coerceIn(0f, 1f) else 0f
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.width(264.dp).clip(shape).background(Color(0xE6101010)).border(1.dp, Color.White.copy(.18f), shape).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF2A1220), Color(0xFF14090F))))
        ) {
            if (thumb != null) {
                Image(bitmap = thumb.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (fwd) "»" else "«", color = accent, fontSize = 48.sp, fontWeight = FontWeight.Black)
                    Text(if (landing) "Jumping…" else if (fwd) "Skipping ahead" else "Going back", color = Dim, fontSize = 14.sp)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (landing) "Jumping to" else if (fwd) "»  +$dText" else "«  -$dText", color = if (landing) Color.White else accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(fmt(target), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(.2f))) {
            Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(accent))
        }
        if (total > 0L) {
            Spacer(Modifier.height(4.dp))
            Text(fmt(total), color = Dim, fontSize = 12.sp, modifier = Modifier.align(Alignment.End))
        }
    }
}

/** Round on-screen button for touch screens. */
@Composable
private fun TouchBtn(label: String, size: Int, onClick: () -> Unit) {
    Box(
        Modifier.height(size.dp).widthIn(min = size.dp).clip(RoundedCornerShape(50))
            .background(Color.Black.copy(.55f)).border(1.dp, Color.White.copy(.3f), RoundedCornerShape(50))
            .clickable(onClick = onClick).padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center
    ) { Text(label, color = Color.White, fontSize = if (size >= 80) 24.sp else 16.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
}

// ---------------- player settings popup ----------------

private class TrackOpt(val group: Tracks.Group, val index: Int, val name: String, val selected: Boolean)

private fun trackName(f: Format, n: Int, audio: Boolean): String {
    val lang = f.language?.takeIf { it.isNotBlank() && it != "und" }?.let { code ->
        val nm = java.util.Locale(code).displayLanguage
        if (nm.isNotBlank()) nm.replaceFirstChar { it.uppercase() } else code
    }
    val base = f.label?.takeIf { it.isNotBlank() } ?: lang ?: "Track ${n + 1}"
    val ch = if (audio) when (f.channelCount) { 1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> null } else null
    return if (ch != null) "$base  ·  $ch" else base
}

private fun trackList(t: Tracks, type: Int): List<TrackOpt> {
    val out = ArrayList<TrackOpt>()
    for (g in t.groups) {
        if (g.type != type) continue
        for (i in 0 until g.length) {
            if (!g.isTrackSupported(i)) continue
            out.add(TrackOpt(g, i, trackName(g.getTrackFormat(i), out.size, type == C.TRACK_TYPE_AUDIO), g.isTrackSelected(i)))
        }
    }
    return out
}

private val ASPECT_NAMES = listOf("Fit  (original ratio)", "Fill  (stretch to screen)", "Zoom  (crop the edges)")
private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
private fun speedName(v: Float) = if (v == 1f) "Normal" else (if (v == v.toInt().toFloat()) "${v.toInt()}x" else "${v}x")

@Composable
private fun GearIcon(modifier: Modifier = Modifier, tint: Color = Color.White) {
    Canvas(modifier) {
        val r = size.minDimension / 2f
        val w = r * 0.24f
        drawCircle(tint, radius = r * 0.5f, style = Stroke(w))
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            val dx = Math.cos(a).toFloat()
            val dy = Math.sin(a).toFloat()
            drawLine(
                tint,
                Offset(center.x + dx * r * 0.66f, center.y + dy * r * 0.66f),
                Offset(center.x + dx * r * 0.94f, center.y + dy * r * 0.94f),
                strokeWidth = w * 1.25f, cap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun OptRow(label: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (f) Color.White else Color(0xFF242424))
            .onFocusChanged { f = it.hasFocus }.clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(label, color = if (f) Color.Black else Color.White, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (value.isNotEmpty()) Text(value, color = if (f) Color(0xFF333333) else Dim, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** One popup for everything about the picture and sound: aspect ratio, audio track, subtitles and speed. */
@Composable
private fun PlayerSettings(
    player: ExoPlayer, vm: Vm, live: Boolean, title: String, speed: Float,
    onSpeed: (Float) -> Unit, onClose: () -> Unit
) {
    var page by remember { mutableIntStateOf(0) }
    var tick by remember { mutableIntStateOf(0) }
    var subsOn by remember { mutableStateOf(!player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) }
    val fr = remember { FocusRequester() }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) { tick++ }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    val tracks = remember(tick) { player.currentTracks }
    val audio = remember(tracks) { trackList(tracks, C.TRACK_TYPE_AUDIO) }
    val text = remember(tracks) { trackList(tracks, C.TRACK_TYPE_TEXT) }
    val aspect = vm.cfg.aspect.coerceIn(0, 2)

    fun pick(o: TrackOpt) {
        var b = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(o.group.mediaTrackGroup, listOf(o.index)))
        if (o.group.type == C.TRACK_TYPE_TEXT) b = b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        player.trackSelectionParameters = b.build()
        if (o.group.type == C.TRACK_TYPE_TEXT) {
            subsOn = true
            if (!vm.cfg.subs) vm.updateCfg(vm.cfg.copy(subs = true))
        }
    }
    fun subsOff() {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        subsOn = false
        if (vm.cfg.subs) vm.updateCfg(vm.cfg.copy(subs = false))
    }

    val selIdx = when (page) {
        1 -> aspect
        2 -> audio.indexOfFirst { it.selected }.coerceAtLeast(0)
        3 -> if (!subsOn) 0 else (text.indexOfFirst { it.selected } + 1).coerceAtLeast(0)
        4 -> SPEEDS.indexOf(speed).coerceAtLeast(0)
        else -> 0
    }
    val listState = remember(page) { LazyListState((selIdx - 1).coerceAtLeast(0)) }
    LaunchedEffect(page) { delay(80); fr.grab() }

    Dialog(
        onDismissRequest = { if (page != 0) page = 0 else onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            Modifier.width(480.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF181818))
                .border(1.dp, KeyBg, RoundedCornerShape(16.dp)).padding(26.dp).safeDpad(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GearIcon(Modifier.size(24.dp), Red)
                Column(Modifier.weight(1f)) {
                    Text(
                        when (page) { 1 -> "Aspect ratio"; 2 -> "Audio"; 3 -> "Subtitles"; 4 -> "Playback speed"; else -> "Player settings" },
                        color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold
                    )
                    if (page == 0) Text(title, color = Dim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            LazyColumn(Modifier.heightIn(max = 300.dp), state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (page == 0) {
                    item {
                        OptRow("Aspect ratio", ASPECT_NAMES[aspect].substringBefore("  "), Modifier.focusRequester(fr)) { page = 1 }
                    }
                    item {
                        val cur = audio.firstOrNull { it.selected }
                        OptRow("Audio", if (audio.isEmpty()) "Default" else (cur?.name ?: "Auto")) { page = 2 }
                    }
                    item {
                        val cur = text.firstOrNull { it.selected }
                        OptRow("Subtitles", if (!subsOn) "Off" else (cur?.name ?: if (text.isEmpty()) "None available" else "On")) { page = 3 }
                    }
                    if (!live) item { OptRow("Playback speed", speedName(speed)) { page = 4 } }
                    item { OptRow("Close", "") { onClose() } }
                } else {
                    item { OptRow("‹  Back", "") { page = 0 } }
                    when (page) {
                        1 -> itemsIndexed(ASPECT_NAMES) { i, n ->
                            OptRow(n, if (i == aspect) "✓" else "", if (i == selIdx) Modifier.focusRequester(fr) else Modifier) {
                                vm.updateCfg(vm.cfg.copy(aspect = i))
                            }
                        }
                        2 -> if (audio.isEmpty()) {
                            item { Text("No other audio tracks in this stream.", color = Dim, fontSize = 16.sp, modifier = Modifier.padding(8.dp)) }
                        } else itemsIndexed(audio) { i, o ->
                            OptRow(o.name, if (o.selected) "✓" else "", if (i == selIdx) Modifier.focusRequester(fr) else Modifier) { pick(o) }
                        }
                        3 -> {
                            item {
                                OptRow("Off", if (!subsOn) "✓" else "", if (selIdx == 0) Modifier.focusRequester(fr) else Modifier) { subsOff() }
                            }
                            if (text.isEmpty()) {
                                item { Text("No subtitles in this stream.", color = Dim, fontSize = 16.sp, modifier = Modifier.padding(8.dp)) }
                            } else itemsIndexed(text) { i, o ->
                                OptRow(o.name, if (subsOn && o.selected) "✓" else "", if (i + 1 == selIdx) Modifier.focusRequester(fr) else Modifier) { pick(o) }
                            }
                        }
                        4 -> itemsIndexed(SPEEDS) { i, v ->
                            OptRow(speedName(v), if (v == speed) "✓" else "", if (i == selIdx) Modifier.focusRequester(fr) else Modifier) { onSpeed(v) }
                        }
                        else -> {}
                    }
                }
            }
        }
    }
}
