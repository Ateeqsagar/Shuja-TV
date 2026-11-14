package com.nova.tv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

val Bg = Color(0xFF141414)
val Panel = Color(0xFF1F1F1F)
val Red = Color(0xFFE50914)
val Dim = Color(0xFFB3B3B3)
val Light = Color(0xFFE5E5E5)
val KeyBg = Color(0xFF333333)
val RedBrush: Brush = SolidColor(Red)

@Composable
fun NovaApp(vm: Vm) {
    Box(Modifier.fillMaxSize().background(Bg)) {
        val p = vm.playing
        when {
            p != null -> PlayerScreen(p.first, p.second, vm) { vm.stop() }
            vm.account == null -> LoginScreen(vm)
            else -> Home(vm)
        }
        vm.pinReq?.let { r -> PinDialog(r.create, r.title, { vm.submitPin(it) }) { vm.cancelPin() } }
        vm.menu?.let { m -> MenuDialog(vm, m) }
    }
}

@Composable
fun Field(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier, password: Boolean = false) {
    var f by remember { mutableStateOf(false) }
    BasicTextField(
        value = value, onValueChange = onChange, singleLine = true,
        textStyle = TextStyle(color = Color.White, fontSize = 18.sp),
        cursorBrush = SolidColor(Color.White),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        modifier = modifier.onFocusChanged { f = it.hasFocus },
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(KeyBg)
                    .border(2.dp, if (f) Color.White else Color.Transparent, RoundedCornerShape(6.dp)).padding(14.dp)
            ) {
                if (value.isEmpty()) Text(hint, color = Dim, fontSize = 18.sp)
                inner()
            }
        }
    )
}

@Composable
fun Btn(
    text: String, modifier: Modifier = Modifier, fill: Color = Red, textColor: Color = Color.White,
    fontSize: TextUnit = 20.sp, hPad: Dp = 28.dp, onClick: () -> Unit
) {
    var f by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (f) 1.06f else 1f, tween(120), label = "s")
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier.graphicsLayer { scaleX = s; scaleY = s }.clip(shape).background(fill)
            .border(3.dp, if (f) Color.White else Color.Transparent, shape)
            .onFocusChanged { f = it.hasFocus }.clickable(onClick = onClick).padding(horizontal = hPad, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = textColor, fontSize = fontSize, fontWeight = FontWeight.Bold, maxLines = 1) }
}

@Composable
fun NavTab(text: String, selected: Boolean, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).background(if (f) Color.White else Color.Transparent)
            .onFocusChanged { f = it.hasFocus }.clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text, fontSize = 18.sp, fontWeight = if (selected || f) FontWeight.Bold else FontWeight.Normal,
            color = if (f) Color.Black else if (selected) Color.White else Dim, maxLines = 1
        )
    }
}

@Composable
fun Tag(text: String, color: Color) {
    Text(
        text, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(3.dp)).background(color).padding(horizontal = 5.dp, vertical = 1.dp)
    )
}

// ---------------- PIN + action dialogs ----------------

@Composable
fun PinDialog(create: Boolean, title: String, onSubmit: (String) -> String?, onDismiss: () -> Unit) {
    var entry by remember { mutableStateOf("") }
    var first by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    val fr = remember { FocusRequester() }

    fun push(d: String) {
        if (entry.length >= 4) return
        entry += d
        if (entry.length == 4) {
            val p = entry
            if (create) {
                val f = first
                if (f == null) { first = p; entry = ""; msg = null }
                else if (f == p) { onSubmit(p) }
                else { first = null; entry = ""; msg = "PINs didn't match. Try again." }
            } else {
                msg = onSubmit(p)
                entry = ""
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(360.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF181818))
                .border(1.dp, KeyBg, RoundedCornerShape(10.dp)).padding(28.dp)
                .onPreviewKeyEvent { e ->
                    val c = e.utf16CodePoint
                    if (e.type == KeyEventType.KeyDown && c in '0'.code..'9'.code) { push((c - '0'.code).toString()); true } else false
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                if (create) (if (first == null) "Create a 4-digit PIN" else "Confirm your PIN") else title,
                color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(4) { i -> Box(Modifier.size(16.dp).clip(CircleShape).background(if (i < entry.length) Color.White else Color(0xFF555555))) }
            }
            Text(msg ?: " ", color = Color(0xFFE87C03), fontSize = 14.sp)
            val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "Del", "0", "Cancel")
            keys.chunked(3).forEachIndexed { r, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEachIndexed { c, k ->
                        Btn(
                            k, Modifier.width(92.dp).then(if (r == 0 && c == 0) Modifier.focusRequester(fr) else Modifier),
                            fill = KeyBg, fontSize = if (k.length > 1) 16.sp else 22.sp, hPad = 4.dp
                        ) {
                            when (k) { "Del" -> entry = entry.dropLast(1); "Cancel" -> onDismiss(); else -> push(k) }
                        }
                    }
                }
            }
            LaunchedEffect(Unit) { fr.requestFocus() }
        }
    }
}

@Composable
fun MenuDialog(vm: Vm, m: MenuTarget) {
    val fr = remember { FocusRequester() }
    val ik = vm.itemKey(m.item)
    val ck = vm.catKey(m.item.live, m.catId)
    val noun = if (m.item.live) "channel" else "title"
    Dialog(onDismissRequest = { vm.closeMenu() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(420.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF181818))
                .border(1.dp, KeyBg, RoundedCornerShape(10.dp)).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(m.item.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Btn(if (ik in vm.lockedKeys) "Unlock $noun (PIN)" else "Lock $noun", Modifier.fillMaxWidth().focusRequester(fr), fill = KeyBg) { vm.toggleLock(ik) }
            Btn(if (ik in vm.hiddenKeys) "Unhide $noun" else "Hide $noun", Modifier.fillMaxWidth(), fill = KeyBg) { vm.toggleHide(ik) }
            Btn(if (ck in vm.lockedKeys) "Unlock category: ${m.catName}" else "Lock category: ${m.catName}", Modifier.fillMaxWidth(), fill = KeyBg, fontSize = 18.sp) { vm.toggleLock(ck) }
            Btn(if (ck in vm.hiddenKeys) "Unhide category: ${m.catName}" else "Hide category: ${m.catName}", Modifier.fillMaxWidth(), fill = KeyBg, fontSize = 18.sp) { vm.toggleHide(ck) }
            Btn("Close", Modifier.fillMaxWidth(), fill = Color(0xFF222222)) { vm.closeMenu() }
            LaunchedEffect(Unit) { fr.requestFocus() }
        }
    }
}

// ---------------- screens ----------------

@Composable
fun LoginScreen(vm: Vm) {
    var host by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize()) {
        Text("SHUJA TV", color = Red, fontSize = 36.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp, modifier = Modifier.padding(48.dp))
        Column(
            Modifier.align(Alignment.Center).width(480.dp).clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(.75f)).padding(48.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Sign In", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Field(host, { host = it }, "Server  (http://host:port)")
            Field(user, { user = it }, "Username")
            Field(pass, { pass = it }, "Password", password = true)
            vm.error?.let { Text(it, color = Color(0xFFE87C03), fontSize = 16.sp) }
            Btn(if (vm.busy) "Connecting…" else "Sign In", Modifier.fillMaxWidth()) {
                if (!vm.busy && host.isNotBlank() && user.isNotBlank()) vm.login(Account(host.trim(), user.trim(), pass))
            }
            Text("Use the Xtream Codes details from your provider.", color = Dim, fontSize = 14.sp)
        }
    }
}

@Composable
fun TopBar(vm: Vm) {
    Row(
        Modifier.fillMaxWidth().background(Bg).padding(horizontal = 48.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("SHUJA TV", color = Red, fontSize = 30.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
        Spacer(Modifier.width(20.dp))
        NavTab("Live TV", vm.live) { vm.setLive(true) }
        NavTab("Movies", !vm.live) { vm.setLive(false) }
        Spacer(Modifier.weight(1f))
        Field(vm.query, { vm.query = it }, "Search", Modifier.width(220.dp))
        NavTab(if (vm.unlocked) "Lock now" else "Unlock", vm.unlocked) { vm.toggleUnlock() }
        NavTab("Sign out", false) { vm.logout() }
    }
}

@Composable
fun Home(vm: Vm) {
    val q = vm.query
    val rows = remember(vm.rows, vm.hiddenKeys, vm.unlocked) {
        vm.rows.mapNotNull { (c, l) -> vm.visible(l).takeIf { it.isNotEmpty() }?.let { c to it } }
    }
    val results = remember(vm.all, q, vm.hiddenKeys, vm.unlocked) {
        if (q.isBlank()) emptyList() else vm.visible(vm.all.filter { it.name.contains(q, true) }).take(300)
    }
    val names = remember(vm.rows) { vm.rows.associate { it.first.id to it.first.name } }
    Column(Modifier.fillMaxSize()) {
        TopBar(vm)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (q.isNotBlank()) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(if (vm.live) 200.dp else 150.dp),
                    contentPadding = PaddingValues(horizontal = 48.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    itemsIndexed(results) { i, item ->
                        PosterCard(vm, item, names[item.cat] ?: "Category", fill = true) { vm.play(results, i) }
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    val firstRow = rows.firstOrNull()
                    val hero = firstRow?.second?.let { l ->
                        l.firstOrNull { it.icon != null && !vm.isLocked(it) } ?: l.firstOrNull { !vm.isLocked(it) }
                    }
                    if (hero != null && firstRow != null) {
                        item { Hero(vm, hero, firstRow.first.name) { vm.play(firstRow.second, firstRow.second.indexOf(hero)) } }
                    }
                    items(rows) { (c, list) -> ShelfRow(vm, c.name, list) }
                }
            }
            if (vm.busy) Text("Loading…", color = Light, fontSize = 22.sp, modifier = Modifier.align(Alignment.Center))
            vm.error?.let { Text(it, color = Red, fontSize = 18.sp, textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center)) }
        }
    }
}

@Composable
fun Hero(vm: Vm, item: Item, label: String, onPlay: () -> Unit) {
    val prog = if (item.live) vm.epgMap[item.id]?.now(vm.now) else null
    LaunchedEffect(item.id, vm.now / 60_000) { vm.loadEpg(item) }
    Box(Modifier.fillMaxWidth().height(320.dp)) {
        if (item.icon != null) {
            AsyncImage(
                model = item.icon, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(.5f).padding(32.dp)
            )
        }
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(Bg, Bg.copy(.9f), Color.Transparent))))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(90.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Bg))))
        Column(
            Modifier.align(Alignment.CenterStart).padding(start = 48.dp).fillMaxWidth(.5f),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(label.uppercase(), color = Red, fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp, maxLines = 1)
            Text(item.name, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
            prog?.let { Text("Now: ${it.title}", color = Light, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Btn("▶  Play", fill = Color.White, textColor = Color.Black, onClick = onPlay)
        }
    }
}

@Composable
fun ShelfRow(vm: Vm, title: String, list: List<Item>) {
    Column {
        Text(title, color = Light, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 48.dp, top = 8.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(list) { i, item -> PosterCard(vm, item, title) { vm.play(list, i) } }
        }
    }
}

/** OK = play. Long-press OK (or the Menu key) = lock / hide menu. */
@Composable
fun PosterCard(vm: Vm, item: Item, catName: String, fill: Boolean = false, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val live = item.live
    val s by animateFloatAsState(if (f) 1.12f else 1f, tween(150), label = "s")
    val shape = RoundedCornerShape(6.dp)
    val locked = vm.isLocked(item)
    val hidden = vm.isHidden(item)
    val mask = locked && !vm.unlocked
    val handled = remember { booleanArrayOf(false) }
    val prog = if (live && !mask) vm.epgMap[item.id]?.now(vm.now) else null
    if (live && !mask) {
        LaunchedEffect(item.id, vm.now / 60_000) { delay(150); vm.loadEpg(item) }
    }
    val size = if (fill) Modifier.fillMaxWidth() else Modifier.width(if (live) 220.dp else 150.dp)
    Box(
        size.aspectRatio(if (live) 16f / 9f else 2f / 3f)
            .zIndex(if (f) 1f else 0f).graphicsLayer { scaleX = s; scaleY = s }
            .clip(shape).background(Panel)
            .border(3.dp, if (f) Color.White else Color.Transparent, shape)
            .onFocusChanged { f = it.hasFocus }
            .onPreviewKeyEvent { e ->
                val k = e.key
                val center = k == Key.DirectionCenter || k == Key.Enter || k == Key.NumPadEnter
                when {
                    e.type == KeyEventType.KeyDown && k == Key.Menu -> { vm.openMenu(item, catName); true }
                    center && e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount > 0 -> {
                        if (!handled[0]) { handled[0] = true; vm.openMenu(item, catName) }
                        true
                    }
                    center && e.type == KeyEventType.KeyUp && handled[0] -> { handled[0] = false; true }
                    else -> false
                }
            }
            .clickable(onClick = onClick)
    ) {
        if (mask) {
            Text("LOCKED", color = Red, fontSize = 16.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp, modifier = Modifier.align(Alignment.Center))
        } else if (item.icon != null) {
            AsyncImage(
                model = item.icon, contentDescription = null,
                contentScale = if (live) ContentScale.Fit else ContentScale.Crop,
                modifier = Modifier.fillMaxSize().then(if (live) Modifier.padding(14.dp) else Modifier)
            )
        } else {
            Text(item.name.take(1).uppercase(), color = Red, fontSize = 36.sp, fontWeight = FontWeight.Black, modifier = Modifier.align(Alignment.Center))
        }
        if ((locked && !mask) || hidden) {
            Row(Modifier.align(Alignment.TopEnd).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (locked && !mask) Tag("LOCKED", Red)
                if (hidden) Tag("HIDDEN", Color(0xFF555555))
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(.85f)))).padding(8.dp)
        ) {
            Text(item.name, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (prog != null) {
                Text(prog.title, color = Dim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val frac = ((vm.now - prog.start * 1000f) / ((prog.end - prog.start) * 1000f)).coerceIn(0f, 1f)
                Box(Modifier.padding(top = 3.dp).fillMaxWidth().height(3.dp).background(Color.White.copy(.25f))) {
                    Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(Red))
                }
            }
        }
    }
}
