package com.nova.tv

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
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
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

private fun fmt(ms: Long): String {
    val s = ms / 1000; val h = s / 3600; val m = s % 3600 / 60; val x = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, x) else "%02d:%02d".format(m, x)
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
        PlayerBody(list, start, vm)
    }
}

@Composable
private fun PlayerBody(list: List<Item>, start: Int, vm: Vm) {
    val ctx = LocalContext.current
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
    val fr = remember { FocusRequester() }
    val cfg = vm.cfg

    val player = remember { buildPlayer(ctx, vm.cfg, vm.ua()) }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(s: Int) {
                buffering = s == Player.STATE_BUFFERING
                if (s == Player.STATE_READY) { error = null; tries = 0 }
                if (s == Player.STATE_ENDED && !cur.live && vm.cfg.autoNext && idx + 1 < list.size) idx += 1
            }
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
        val at = vm.resumeAt(item)
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

    val mode = when (cfg.aspect) {
        1 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        2 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(fr).focusable().onPreviewKeyEvent { e ->
            val k = e.key
            val dpad = k == Key.DirectionUp || k == Key.DirectionDown || k == Key.DirectionLeft ||
                k == Key.DirectionRight || k == Key.DirectionCenter || k == Key.Enter || k == Key.NumPadEnter
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent dpad
            when (k) {
                Key.DirectionUp, Key.ChannelUp -> { if (item.live) idx = step(1); ping++; true }
                Key.DirectionDown, Key.ChannelDown -> { if (item.live) idx = step(-1); ping++; true }
                Key.DirectionRight -> { if (!item.live) player.seekTo(player.currentPosition + vm.cfg.seekSeconds * 1000L); ping++; true }
                Key.DirectionLeft -> { if (!item.live) player.seekTo((player.currentPosition - vm.cfg.seekSeconds * 1000L).coerceAtLeast(0L)); ping++; true }
                Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause -> {
                    if (!item.live) player.playWhenReady = !player.playWhenReady
                    ping++; true
                }
                Key.Menu -> { vm.updateCfg(vm.cfg.copy(aspect = (vm.cfg.aspect + 1) % 3)); ping++; true }
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
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { v -> v.resizeMode = mode }
        )

        if (buffering && error == null) CircularProgressIndicator(Modifier.align(Alignment.Center).size(56.dp), color = Red, strokeWidth = 5.dp)
        error?.let { Text(it, color = Red, fontSize = 22.sp, textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center)) }

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
                            val pf = ((vm.now - prog.start * 1000f) / ((prog.end - prog.start) * 1000f)).coerceIn(0f, 1f)
                            Spacer(Modifier.height(4.dp))
                            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(.2f))) {
                                Box(Modifier.fillMaxWidth(pf).fillMaxHeight().background(RedBrush))
                            }
                            nxt?.let { Text("Next: ${it.title}", color = Dim, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    } else {
                        val frac = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
                        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(.2f))) {
                            Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(RedBrush))
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("${fmt(pos)} / ${fmt(dur)}", color = Dim, fontSize = 16.sp)
                    }
                }
                Text(
                    if (item.live) "▲ ▼  channel     MENU  aspect" else "◀ ▶  seek ${vm.cfg.seekSeconds}s     OK  pause     MENU  aspect",
                    color = Dim, fontSize = 16.sp
                )
            }
        }
    }
}
