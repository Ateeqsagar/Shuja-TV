package com.nova.tv

import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
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
import coil.Coil
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

val Bg = Color(0xFF141414)
val Panel = Color(0xFF1F1F1F)
val Red = Color(0xFFE50914)
val Dim = Color(0xFFB3B3B3)
val Light = Color(0xFFE5E5E5)
val KeyBg = Color(0xFF333333)
val RedBrush: Brush = SolidColor(Red)

/** A titled group of items: a category, or a special row such as Continue Watching. cont is set only for Continue Watching. */
class Section(val title: String, val items: List<Item>, val cont: List<Hist>? = null)

/** Focus can race the first layout of a new window; retry briefly instead of crashing. */
suspend fun FocusRequester.grab() {
    repeat(6) {
        try {
            requestFocus()
            return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            delay(80)
        }
    }
}

@Composable
fun NovaApp(vm: Vm) {
    Box(Modifier.fillMaxSize().background(Bg)) {
        if (vm.account == null) {
            LoginScreen(vm)
        } else {
            Home(vm)
            if (vm.settingsOpen) SettingsScreen(vm)
            if (vm.detail != null) SeriesScreen(vm)
            vm.playing?.let { p -> PlayerScreen(p.first, p.second, vm) { vm.stop() } }
        }
        vm.pinReq?.let { r -> PinDialog(r.create, r.title, { vm.submitPin(it) }) { vm.cancelPin() } }
        vm.menu?.let { m -> MenuDialog(vm, m) }
    }
}

@Composable
fun FullDialog(onClose: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Box(Modifier.fillMaxSize().background(Bg)) { content() }
    }
}

@Composable
fun Field(
    value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier,
    password: Boolean = false, ime: ImeAction = ImeAction.Next, onGo: (() -> Unit)? = null
) {
    var f by remember { mutableStateOf(false) }
    BasicTextField(
        value = value, onValueChange = onChange, singleLine = true,
        textStyle = TextStyle(color = Color.White, fontSize = 18.sp),
        cursorBrush = SolidColor(Color.White),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(imeAction = ime),
        keyboardActions = KeyboardActions(onSearch = { onGo?.invoke() }, onDone = { onGo?.invoke() }),
        modifier = modifier.onFocusChanged { f = it.hasFocus },
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(KeyBg)
                    .border(2.dp, if (f) Color.White else Color.Transparent, RoundedCornerShape(6.dp)).padding(14.dp)
            ) {
                if (value.isEmpty()) Text(hint, color = Dim, fontSize = 18.sp, maxLines = 1)
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
    val s by animateFloatAsState(if (f) 1.05f else 1f, tween(100), label = "s")
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
                if (create) (if (first == null) title else "Confirm your PIN") else title,
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
            LaunchedEffect(Unit) { fr.grab() }
        }
    }
}

@Composable
fun MenuDialog(vm: Vm, m: MenuTarget) {
    val fr = remember { FocusRequester() }
    val ik = vm.itemKey(m.item)
    val ck = vm.catKey(m.item.type, m.catId)
    val noun = when (m.item.type) {
        T_LIVE -> "channel"
        T_MOVIE -> "movie"
        else -> "series"
    }
    Dialog(onDismissRequest = { vm.closeMenu() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(440.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF181818))
                .border(1.dp, KeyBg, RoundedCornerShape(10.dp)).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(m.item.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Btn(if (ik in vm.favKeys) "Remove from favorites" else "Add to favorites", Modifier.fillMaxWidth().focusRequester(fr), fill = KeyBg) { vm.toggleFav(m.item) }
            Btn(if (ik in vm.lockedKeys) "Unlock $noun (PIN)" else "Lock $noun", Modifier.fillMaxWidth(), fill = KeyBg) { vm.toggleLock(ik) }
            Btn(if (ik in vm.hiddenKeys) "Unhide $noun" else "Hide $noun", Modifier.fillMaxWidth(), fill = KeyBg) { vm.toggleHide(ik) }
            Btn(if (ck in vm.lockedKeys) "Unlock category: ${m.catName}" else "Lock category: ${m.catName}", Modifier.fillMaxWidth(), fill = KeyBg, fontSize = 18.sp) { vm.toggleLock(ck) }
            Btn(if (ck in vm.hiddenKeys) "Unhide category: ${m.catName}" else "Hide category: ${m.catName}", Modifier.fillMaxWidth(), fill = KeyBg, fontSize = 18.sp) { vm.toggleHide(ck) }
            Btn("Close", Modifier.fillMaxWidth(), fill = Color(0xFF222222)) { vm.closeMenu() }
            LaunchedEffect(Unit) { fr.grab() }
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
        Image(painter = painterResource(R.drawable.logo), contentDescription = null, modifier = Modifier.padding(32.dp).size(96.dp))
        Column(
            Modifier.align(Alignment.Center).width(480.dp).clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(.75f)).padding(48.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Sign In", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Field(host, { host = it }, "Server  (http://host:port)")
            Field(user, { user = it }, "Username")
            Field(pass, { pass = it }, "Password", password = true, ime = ImeAction.Done)
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
    val fm = LocalFocusManager.current
    Row(
        Modifier.fillMaxWidth().background(Bg).padding(horizontal = 48.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("SHUJA TV", color = Red, fontSize = 28.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
        Spacer(Modifier.width(16.dp))
        NavTab("Live TV", vm.tab == 0) { vm.selectTab(0) }
        NavTab("Movies", vm.tab == 1) { vm.selectTab(1) }
        NavTab("Series", vm.tab == 2) { vm.selectTab(2) }
        Spacer(Modifier.weight(1f))
        NavTab("Settings", false) { vm.settingsOpen = true }
        Spacer(Modifier.width(8.dp))
        Field(
            vm.query, { vm.query = it }, "Search all", Modifier.width(260.dp),
            ime = ImeAction.Search, onGo = { fm.moveFocus(FocusDirection.Down) }
        )
    }
}

@Composable
fun Home(vm: Vm) {
    val tab = vm.tab
    val cfg = vm.cfg
    val q = vm.query
    val lib = vm.libs[tab]
    LaunchedEffect(tab) { vm.ensure(tab) }
    BackHandler(enabled = q.isNotEmpty()) { vm.query = "" }

    var res by remember { mutableStateOf<SearchRes?>(null) }
    val libCount = vm.libs.size
    LaunchedEffect(q, libCount, vm.hiddenKeys, vm.unlocked, cfg.hideAdult) {
        if (q.isBlank()) {
            res = null
        } else {
            delay(300)
            res = withContext(Dispatchers.Default) { vm.search(q) }
        }
    }

    val rows = remember(lib, vm.hiddenKeys, vm.unlocked, cfg.hideAdult, cfg.sort) {
        if (lib == null) emptyList<Pair<Category, List<Item>>>() else vm.rowsFor(lib, tab)
    }
    val favs = remember(lib, vm.favKeys, vm.hiddenKeys, vm.unlocked, cfg.hideAdult) {
        if (lib == null) emptyList<Item>() else vm.favItems(lib)
    }
    val cont = remember(vm.hist, tab, cfg.contRow, vm.hiddenKeys, vm.unlocked) { vm.contFor(tab) }
    val recent = remember(lib, tab, cfg.recentRow, vm.hiddenKeys, vm.unlocked, cfg.hideAdult) {
        if (lib == null || tab == T_LIVE) emptyList<Item>() else vm.recentFor(lib)
    }
    val sections = remember(cont, recent, favs, rows) {
        val out = ArrayList<Section>()
        if (cont.isNotEmpty()) out.add(Section("Continue Watching", cont.map { vm.contItem(it) }, cont))
        if (recent.isNotEmpty()) out.add(Section("Recently Added", recent))
        if (favs.isNotEmpty()) out.add(Section("Favorites", favs))
        for ((c, l) in rows) out.add(Section(c.name, l))
        out
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(vm)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (q.isNotBlank()) {
                SearchResults(vm, res, q)
            } else if (lib != null) {
                key(tab, cfg.layout) {
                    if (cfg.layout == 1) GridLayout(vm, sections)
                    else Shelves(vm, sections, rows.firstOrNull())
                }
            } else {
                val err = vm.errs[tab]
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (err != null) {
                        Text(err, color = Color(0xFFE87C03), fontSize = 18.sp, textAlign = TextAlign.Center)
                        Btn("Retry") { vm.reload(tab) }
                    } else {
                        Text("Loading…", color = Light, fontSize = 22.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun Shelves(vm: Vm, sections: List<Section>, heroSrc: Pair<Category, List<Item>>?) {
    if (sections.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Nothing to show here.", color = Dim, fontSize = 20.sp)
        }
        return
    }
    val heroList = if (vm.cfg.hero) heroSrc else null
    val heroItem = heroList?.second?.firstOrNull { it.icon != null && !vm.isLocked(it) }
    LazyColumn(Modifier.fillMaxSize()) {
        if (heroList != null && heroItem != null) {
            item { Hero(heroItem, heroList.first.name) { vm.open(heroList.second, heroList.second.indexOf(heroItem)) } }
        }
        items(sections) { sec -> ShelfRow(vm, sec.title, sec.items, sec.cont) }
    }
}

@Composable
fun CatRow(title: String, count: Int, selected: Boolean, onFocus: () -> Unit, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
            .background(if (f) Color.White else if (selected) KeyBg else Color.Transparent)
            .onFocusChanged { f = it.hasFocus; if (it.hasFocus) onFocus() }
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, color = if (f) Color.Black else if (selected) Color.White else Dim, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text("$count", color = if (f) Color(0xFF333333) else Dim, fontSize = 13.sp, maxLines = 1)
    }
}

/** Category list on the left, poster grid on the right. */
@Composable
fun GridLayout(vm: Vm, sections: List<Section>) {
    if (sections.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Nothing to show here.", color = Dim, fontSize = 20.sp)
        }
        return
    }
    val fm = LocalFocusManager.current
    var sel by remember { mutableIntStateOf(0) }
    val cur = sel.coerceIn(0, sections.lastIndex)
    val sec = sections[cur]
    val live = vm.tab == T_LIVE
    val k = when (vm.cfg.cardSize) {
        0 -> 0.85f
        2 -> 1.25f
        else -> 1f
    }
    Row(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.width(290.dp).fillMaxHeight().padding(start = 32.dp, top = 8.dp, end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            itemsIndexed(sections) { i, s ->
                CatRow(s.title, s.items.size, i == cur, { sel = i }) { fm.moveFocus(FocusDirection.Right) }
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive((if (live) 220.dp else 156.dp) * k),
            modifier = Modifier.weight(1f).fillMaxHeight(),
            contentPadding = PaddingValues(start = 16.dp, end = 32.dp, top = 12.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            gridItemsIndexed(sec.items) { i, item ->
                PosterCard(vm, item) {
                    val c = sec.cont
                    if (c != null) vm.resumeHist(c[i]) else vm.open(sec.items, i)
                }
            }
        }
    }
}

@Composable
fun SearchResults(vm: Vm, res: SearchRes?, q: String) {
    if (res == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Searching…", color = Light, fontSize = 22.sp) }
        return
    }
    val total = res.live.size + res.movies.size + res.series.size
    if (total == 0) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                if (vm.loadingTypes.isNotEmpty()) "No matches yet. Still loading content…" else "No results for \"$q\"",
                color = Dim, fontSize = 20.sp
            )
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        if (res.live.isNotEmpty()) item { ShelfRow(vm, "Live TV  (${res.live.size})", res.live) }
        if (res.movies.isNotEmpty()) item { ShelfRow(vm, "Movies  (${res.movies.size})", res.movies) }
        if (res.series.isNotEmpty()) item { ShelfRow(vm, "Series  (${res.series.size})", res.series) }
        if (vm.loadingTypes.isNotEmpty()) item {
            Text("Still loading more content…", color = Dim, fontSize = 14.sp, modifier = Modifier.padding(start = 48.dp, bottom = 16.dp))
        }
    }
}

@Composable
fun Hero(item: Item, label: String, onPlay: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(260.dp)) {
        if (item.icon != null) {
            AsyncImage(
                model = item.icon, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(.5f).padding(24.dp)
            )
        }
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(Bg, Bg.copy(.9f), Color.Transparent))))
        Column(
            Modifier.align(Alignment.CenterStart).padding(start = 48.dp).fillMaxWidth(.5f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(label.uppercase(), color = Red, fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp, maxLines = 1)
            Text(item.name, color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Btn(if (item.type == T_SERIES) "Open" else "▶  Play", fill = Color.White, textColor = Color.Black, onClick = onPlay)
        }
    }
}

@Composable
fun ShelfRow(vm: Vm, title: String, list: List<Item>, cont: List<Hist>? = null) {
    Column {
        Text(title, color = Light, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 48.dp, end = 48.dp, top = 8.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(list) { i, item ->
                PosterCard(vm, item) { if (cont != null) vm.resumeHist(cont[i]) else vm.open(list, i) }
            }
        }
    }
}

/** OK = play / open. Long-press OK (or the Menu key) = favorites, lock and hide menu. */
@Composable
fun PosterCard(vm: Vm, item: Item, onClick: () -> Unit) {
    val cfg = vm.cfg
    var f by remember { mutableStateOf(false) }
    val live = item.live
    val s by animateFloatAsState(if (f && cfg.anim) 1.08f else 1f, tween(110), label = "s")
    val shape = RoundedCornerShape(6.dp)
    val k = when (cfg.cardSize) {
        0 -> 0.85f
        2 -> 1.25f
        else -> 1f
    }
    val showLabel = cfg.titles || item.icon == null || f
    val locked = vm.isLocked(item)
    val hidden = vm.isHidden(item)
    val mask = locked && !vm.unlocked
    val handled = remember { booleanArrayOf(false) }
    val showEpg = live && cfg.epg && !mask
    val prog = if (showEpg) vm.epgMap[item.id]?.now(vm.now) else null
    if (showEpg) {
        LaunchedEffect(f) { if (f) { delay(500); vm.loadEpg(item) } }
    }
    Box(
        Modifier.width((if (live) 200.dp else 140.dp) * k)
            .aspectRatio(if (live) 16f / 9f else 2f / 3f)
            .zIndex(if (f) 1f else 0f).graphicsLayer { scaleX = s; scaleY = s }
            .clip(shape).background(Panel)
            .border(3.dp, if (f) Color.White else Color.Transparent, shape)
            .onFocusChanged { f = it.hasFocus }
            .onPreviewKeyEvent { e ->
                val k = e.key
                val center = k == Key.DirectionCenter || k == Key.Enter || k == Key.NumPadEnter
                when {
                    e.type == KeyEventType.KeyDown && k == Key.Menu -> { vm.openMenu(item); true }
                    center && e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount > 0 -> {
                        if (!handled[0]) { handled[0] = true; vm.openMenu(item) }
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
        val fav = vm.favKeys.isNotEmpty() && vm.itemKey(item) in vm.favKeys
        if ((locked && !mask) || hidden || fav) {
            Row(Modifier.align(Alignment.TopEnd).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (fav) Tag("FAV", Color(0xFFB8860B))
                if (locked && !mask) Tag("LOCKED", Red)
                if (hidden) Tag("HIDDEN", Color(0xFF555555))
            }
        }
        if (showLabel) Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Color.Black.copy(.72f)).padding(horizontal = 8.dp, vertical = 5.dp)
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

// ---------------- series ----------------

@Composable
fun EpRow(vm: Vm, ep: Item, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)
    val resume = vm.resumeAt(ep) > 0
    Row(
        modifier.fillMaxWidth().clip(shape).background(if (f) Color.White else Panel)
            .onFocusChanged { f = it.hasFocus }.clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(Modifier.width(128.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(4.dp)).background(Color(0xFF2A2A2A))) {
            if (ep.icon != null) AsyncImage(model = ep.icon, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Text(ep.name, color = if (f) Color.Black else Color.White, fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (resume) Tag("RESUME", Red)
    }
}

@Composable
fun SeriesScreen(vm: Vm) {
    val d = vm.detail ?: return
    val info = vm.detailInfo
    val err = vm.detailErr
    var season by remember(info) { mutableIntStateOf(info?.eps?.keys?.firstOrNull() ?: 0) }
    val allEps = remember(info) { info?.eps?.values?.flatten() ?: emptyList<Item>() }
    val fr = remember { FocusRequester() }
    val back = remember { FocusRequester() }
    FullDialog({ vm.closeDetail() }) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            Column(Modifier.width(250.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (d.icon != null) {
                    AsyncImage(
                        model = d.icon, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(Panel)
                    )
                }
                Text(d.name, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                val plot = info?.plot
                if (!plot.isNullOrBlank()) Text(plot, color = Dim, fontSize = 14.sp, maxLines = 7, overflow = TextOverflow.Ellipsis)
                Btn("Back", Modifier.focusRequester(back), fill = KeyBg, fontSize = 18.sp) { vm.closeDetail() }
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    info == null -> {
                        if (err != null) Text(err, color = Color(0xFFE87C03), fontSize = 18.sp)
                        else Text("Loading episodes…", color = Light, fontSize = 20.sp)
                    }
                    info.eps.isEmpty() -> Text("No episodes found for this series.", color = Dim, fontSize = 18.sp)
                    else -> {
                        val seasons = info.eps.keys.toList()
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(seasons) { sn ->
                                NavTab(if (sn == 0) "Specials" else "Season $sn", sn == season) { season = sn }
                            }
                        }
                        val eps = info.eps[season] ?: emptyList()
                        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsIndexed(eps) { i, ep ->
                                EpRow(vm, ep, if (i == 0) Modifier.focusRequester(fr) else Modifier) {
                                    vm.play(allEps, allEps.indexOf(ep))
                                }
                            }
                        }
                    }
                }
            }
        }
        LaunchedEffect(info, season) {
            if (info != null && info.eps.isNotEmpty()) fr.grab() else back.grab()
        }
    }
}

// ---------------- settings ----------------

@Composable
fun SectionTitle(title: String) {
    Text(
        title.uppercase(), color = Red, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
        modifier = Modifier.padding(top = 18.dp, bottom = 2.dp)
    )
}

@Composable
fun SettingRow(label: String, value: String, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if (f) Color.White else Panel)
            .onFocusChanged { f = it.hasFocus }.clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(label, color = if (f) Color.Black else Color.White, fontSize = 18.sp, maxLines = 1, modifier = Modifier.weight(1f))
        Text(value, color = if (f) Color(0xFF333333) else Dim, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun onOff(b: Boolean) = if (b) "On" else "Off"
private fun pick(list: List<String>, i: Int) = list[i.coerceIn(0, list.lastIndex)]

private val FORMATS = listOf("HLS (m3u8)", "MPEG-TS")
private val QUALITY = listOf("Auto", "Up to 1080p", "Up to 720p")
private val BUFFERS = listOf("Small (fast zapping)", "Medium", "Large (stable)")
private val ASPECTS = listOf("Fit", "Fill", "Zoom")
private val AGENTS = listOf("Default", "VLC", "Chrome", "IPTV Smarters")
private val STARTS = listOf("Live TV", "Movies", "Series")
private val SORTS = listOf("Provider order", "A to Z")
private val LAYOUTS = listOf("Rows", "Category list and grid")
private val CARDS = listOf("Small", "Medium", "Large")

@Composable
fun SettingsScreen(vm: Vm) {
    val c = vm.cfg
    val ctx = LocalContext.current
    var showLog by remember { mutableStateOf(false) }
    val fr = remember { FocusRequester() }
    fun toast(s: String) { Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show() }

    FullDialog({ vm.settingsOpen = false }) {
        Column(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Settings", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Btn("Close", Modifier.focusRequester(fr), fill = KeyBg, fontSize = 18.sp) { vm.settingsOpen = false }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item { SectionTitle("Account") }
                item { SettingRow("Server", vm.account?.host ?: "") {} }
                item { SettingRow("Username", vm.account?.user ?: "") {} }
                item { SettingRow("Password", "****") {} }
                item { SettingRow("Subscription", vm.info.status.ifBlank { "Active" }) {} }
                item { SettingRow("Expires", vm.info.exp.ifBlank { "Unknown" }) {} }
                item { SettingRow("Connections", "${vm.info.activeCons.ifBlank { "0" }} of ${vm.info.maxCons.ifBlank { "?" }} in use") {} }
                item { SettingRow("Refresh all content", "Reload lists") { vm.refreshAll(); toast("Reloading content") } }
                item { SettingRow("Sign out", "") { vm.logout() } }

                item { SectionTitle("Playback") }
                item { SettingRow("Live stream format", pick(FORMATS, c.liveFormat)) { vm.updateCfg(c.copy(liveFormat = (c.liveFormat + 1) % FORMATS.size)) } }
                item { SettingRow("Max video quality", pick(QUALITY, c.quality)) { vm.updateCfg(c.copy(quality = (c.quality + 1) % QUALITY.size)) } }
                item { SettingRow("Buffer size", pick(BUFFERS, c.buffer)) { vm.updateCfg(c.copy(buffer = (c.buffer + 1) % BUFFERS.size)) } }
                item { SettingRow("Aspect ratio", pick(ASPECTS, c.aspect)) { vm.updateCfg(c.copy(aspect = (c.aspect + 1) % ASPECTS.size)) } }
                item { SettingRow("Audio language", pick(AUDIO_NAMES, c.audio)) { vm.updateCfg(c.copy(audio = (c.audio + 1) % AUDIO_NAMES.size)) } }
                item { SettingRow("Subtitles", onOff(c.subs)) { vm.updateCfg(c.copy(subs = !c.subs)) } }
                item { SettingRow("Resume where you left off", onOff(c.resume)) { vm.updateCfg(c.copy(resume = !c.resume)) } }
                item { SettingRow("Autoplay next episode", onOff(c.autoNext)) { vm.updateCfg(c.copy(autoNext = !c.autoNext)) } }
                item { SettingRow("Connect as (user agent)", pick(AGENTS, c.ua)) { vm.updateCfg(c.copy(ua = (c.ua + 1) % AGENTS.size)) } }

                item { SectionTitle("Layout") }
                item { SettingRow("Home layout", pick(LAYOUTS, c.layout)) { vm.updateCfg(c.copy(layout = (c.layout + 1) % LAYOUTS.size)) } }
                item { SettingRow("Card size", pick(CARDS, c.cardSize)) { vm.updateCfg(c.copy(cardSize = (c.cardSize + 1) % CARDS.size)) } }
                item { SettingRow("Card titles", if (c.titles) "Always" else "Only when focused") { vm.updateCfg(c.copy(titles = !c.titles)) } }
                item { SettingRow("Continue watching row", onOff(c.contRow)) { vm.updateCfg(c.copy(contRow = !c.contRow)) } }
                item { SettingRow("Recently added row", onOff(c.recentRow)) { vm.updateCfg(c.copy(recentRow = !c.recentRow)) } }
                item { SectionTitle("Interface") }
                item { SettingRow("Start on", pick(STARTS, c.startTab)) { vm.updateCfg(c.copy(startTab = (c.startTab + 1) % STARTS.size)) } }
                item { SettingRow("Sort lists", pick(SORTS, c.sort)) { vm.updateCfg(c.copy(sort = (c.sort + 1) % SORTS.size)) } }
                item { SettingRow("Banner at top of lists", onOff(c.hero)) { vm.updateCfg(c.copy(hero = !c.hero)) } }
                item { SettingRow("Programme guide on channels", onOff(c.epg)) { vm.updateCfg(c.copy(epg = !c.epg)) } }
                item { SettingRow("Focus zoom animation", onOff(c.anim)) { vm.updateCfg(c.copy(anim = !c.anim)) } }
                item { SettingRow("Hide adult categories", onOff(c.hideAdult)) { vm.updateCfg(c.copy(hideAdult = !c.hideAdult)) } }

                item { SectionTitle("Parental control and favorites") }
                item { SettingRow("Locked and hidden items", if (vm.unlocked) "Unlocked (tap to lock)" else "Locked (tap to unlock)") { vm.toggleUnlock() } }
                item { SettingRow("Change PIN", "") { vm.changePin() } }
                item { SettingRow("Remove all locks", "${vm.lockedKeys.size} locked") { vm.clearLocks() } }
                item { SettingRow("Unhide everything", "${vm.hiddenKeys.size} hidden") { vm.clearHidden() } }
                item { SettingRow("Clear favorites", "${vm.favKeys.size} saved") { vm.clearFavs(); toast("Favorites cleared") } }

                item { SectionTitle("Storage and diagnostics") }
                item {
                    SettingRow("Clear image cache", "") {
                        try {
                            val l = Coil.imageLoader(ctx)
                            l.memoryCache?.clear()
                            l.diskCache?.clear()
                            toast("Image cache cleared")
                        } catch (e: Exception) { toast("Could not clear cache") }
                    }
                }
                item { SettingRow("Clear resume history", "") { vm.clearResume(); toast("Resume history cleared") } }
                item { SettingRow("Last crash log", "View") { showLog = true } }
                item {
                    val rt = Runtime.getRuntime()
                    val used = (rt.totalMemory() - rt.freeMemory()) / 1048576
                    val max = rt.maxMemory() / 1048576
                    SettingRow("About", "Shuja TV $APP_VERSION  |  ${Build.MODEL}  |  Android ${Build.VERSION.RELEASE}  |  ${used}/${max} MB") {}
                }
            }
        }
        LaunchedEffect(Unit) { fr.grab() }
        if (showLog) {
            val log = ctx.getSharedPreferences("nova_crash", 0).getString("log", null) ?: "No crash recorded."
            FullDialog({ showLog = false }) {
                Column(Modifier.fillMaxSize().padding(48.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Last crash log", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text(log, color = Dim, fontSize = 13.sp, maxLines = 28, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Btn("Close", fill = KeyBg) { showLog = false }
                }
            }
        }
    }
}
