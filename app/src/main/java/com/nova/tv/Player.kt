package com.nova.tv

import androidx.activity.compose.BackHandler
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.AudioAttributes
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

@Composable
fun PlayerScreen(list: List<Item>, start: Int, vm: Vm, onExit: () -> Unit) {
    val ctx = LocalContext.current
    var idx by remember { mutableIntStateOf(start) }
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

    // Low-latency tuned ExoPlayer: small start buffer = fast zapping, HW decoding with software fallback.
    val player = remember {
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(4000, 30000, 1000, 2000)
            .setPrioritizeTimeOverSizeThresholds(true).build()
        val rf = DefaultRenderersFactory(ctx)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        val http = DefaultHttpDataSource.Factory().setUserAgent("ShujaTV/1.0")
            .setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(8000).setReadTimeoutMs(10000)
        ExoPlayer.Builder(ctx, rf).setLoadControl(load)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true).build().apply {
                trackSelectionParameters = trackSelectionParameters.buildUpon().setForceHighestSupportedBitrate(true).build()
            }
    }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(s: Int) {
                buffering = s == Player.STATE_BUFFERING
                if (s == Player.STATE_READY) { error = null; tries = 0 }
            }
            override fun onPlayerError(e: PlaybackException) {
                tries++
                val u = cur.url
                when {
                    tries == 1 && cur.live && u.endsWith(".m3u8") -> player.setMediaItem(MediaItem.fromUri(u.removeSuffix(".m3u8") + ".ts"))
                    tries <= 3 -> player.setMediaItem(MediaItem.fromUri(u))
                    else -> { error = "Can't play this stream (${e.errorCodeName})"; return }
                }
                player.prepare()
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release() }
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
        player.setMediaItem(MediaItem.fromUri(item.url))
        player.prepare()
        player.playWhenReady = true
    }
    LaunchedEffect(ping) { show = true; delay(4000); show = false }
    LaunchedEffect(show, idx) {
        while (show && !item.live) { pos = player.currentPosition; dur = player.duration.coerceAtLeast(0L); delay(500) }
    }
    LaunchedEffect(Unit) { fr.requestFocus() }
    BackHandler(onBack = onExit)

    val epg = vm.epgMap[item.id]
    val prog = epg?.now(vm.now)
    val nxt = epg?.next(vm.now)
    LaunchedEffect(idx) {
        vm.loadEpg(list[idx])
        vm.loadEpg(list[(idx + 1) % list.size])
        vm.loadEpg(list[(idx - 1 + list.size) % list.size])
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

    Box(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(fr).focusable().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val n = list.size
            when (e.key) {
                Key.DirectionUp, Key.ChannelUp -> { if (item.live) idx = step(1); ping++; true }
                Key.DirectionDown, Key.ChannelDown -> { if (item.live) idx = step(-1); ping++; true }
                Key.DirectionRight -> { if (!item.live) player.seekTo(player.currentPosition + 10_000); ping++; true }
                Key.DirectionLeft -> { if (!item.live) player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0L)); ping++; true }
                Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause -> {
                    if (!item.live) player.playWhenReady = !player.playWhenReady
                    ping++; true
                }
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
            }
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
                Text(if (item.live) "▲ ▼  change channel" else "◀ ▶  seek 10s     OK  pause", color = Dim, fontSize = 16.sp)
            }
        }
    }
}
