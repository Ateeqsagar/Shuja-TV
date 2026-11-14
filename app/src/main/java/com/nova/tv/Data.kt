package com.nova.tv

import android.app.Application
import android.content.Context
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID

data class Account(val host: String, val user: String, val pass: String)
data class Category(val id: String, val name: String)
data class Item(val id: String, val name: String, val icon: String?, val url: String, val live: Boolean, val cat: String)

/** One programme. start/end are epoch seconds. */
data class Prog(val title: String, val start: Long, val end: Long)
data class Epg(val list: List<Prog>, val at: Long) {
    fun now(t: Long): Prog? = list.firstOrNull { t >= it.start * 1000 && t < it.end * 1000 }
    fun next(t: Long): Prog? = list.firstOrNull { it.start * 1000 > t }
}

class PinReq(val title: String, val create: Boolean, val onOk: () -> Unit)
class MenuTarget(val item: Item, val catId: String, val catName: String)

class Xtream(private val a: Account) {
    private val base = a.host.trim().let { if (it.startsWith("http")) it else "http://$it" }.trimEnd('/')
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private suspend fun call(q: String): String = withContext(Dispatchers.IO) {
        val c = URL("$base/player_api.php?username=${enc(a.user)}&password=${enc(a.pass)}$q")
            .openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 40_000
        c.setRequestProperty("User-Agent", "ShujaTV/1.0")
        try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
    }

    suspend fun login() {
        val ui = JSONObject(call("")).optJSONObject("user_info") ?: error("Invalid server response")
        if (ui.optInt("auth") != 1) error("Wrong username or password")
        if (ui.optString("status").equals("Expired", true)) error("Subscription expired")
    }

    suspend fun categories(live: Boolean): List<Category> {
        val arr = JSONArray(call("&action=get_${if (live) "live" else "vod"}_categories"))
        return List(arr.length()) {
            val o = arr.getJSONObject(it)
            Category(o.optString("category_id"), o.optString("category_name"))
        }
    }

    suspend fun items(live: Boolean, cat: String): List<Item> {
        val k = if (live) "live" else "vod"
        val raw = call("&action=get_${k}_streams" + if (cat.isEmpty()) "" else "&category_id=$cat")
        return withContext(Dispatchers.Default) {
            val arr = JSONArray(raw)
            List(arr.length()) {
                val o = arr.getJSONObject(it)
                val id = o.optString("stream_id")
                val icon = o.optString("stream_icon").takeIf { s -> s.isNotBlank() && s != "null" }
                val url = if (live) "$base/live/${a.user}/${a.pass}/$id.m3u8"
                else "$base/movie/${a.user}/${a.pass}/$id.${o.optString("container_extension").ifBlank { "mp4" }}"
                Item(id, o.optString("name"), icon, url, live, o.optString("category_id"))
            }
        }
    }

    /** Now + next programmes for one channel (small, fast request). */
    suspend fun shortEpg(id: String): List<Prog> = try {
        val arr = JSONObject(call("&action=get_short_epg&stream_id=$id&limit=3")).optJSONArray("epg_listings")
        if (arr == null) emptyList() else List(arr.length()) { arr.getJSONObject(it) }.mapNotNull { o ->
            val s = o.optString("start_timestamp").toLongOrNull()
            val e = o.optString("stop_timestamp").toLongOrNull()
            if (s == null || e == null || e <= s) null else Prog(decode(o.optString("title")), s, e)
        }.sortedBy { it.start }
    } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }

    private fun decode(s: String): String = try {
        val d = String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8)
        if (d.isNotBlank() && !d.contains('\uFFFD') && d.none { it.isISOControl() && it != '\n' && it != '\t' }) d else s
    } catch (e: Exception) { s }
}

/** PIN + lock/hide lists, kept in their own prefs so signing out never clears them. */
class Vault(ctx: Context) {
    private val sp = ctx.getSharedPreferences("nova_vault", Context.MODE_PRIVATE)
    private fun hash(pin: String, salt: String) =
        MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray()).joinToString("") { "%02x".format(it) }
    fun hasPin() = sp.contains("h")
    fun setPin(pin: String) {
        val salt = UUID.randomUUID().toString()
        sp.edit().putString("s", salt).putString("h", hash(pin, salt)).apply()
    }
    fun check(pin: String) = sp.getString("h", null) == hash(pin, sp.getString("s", "") ?: "")
    fun get(name: String): Set<String> = sp.getStringSet(name, emptySet())?.toSet() ?: emptySet()
    fun put(name: String, v: Set<String>) { sp.edit().putStringSet(name, HashSet(v)).apply() }
}

class Vm(app: Application) : AndroidViewModel(app) {
    private val sp = app.getSharedPreferences("nova", 0)
    private val vault = Vault(app)
    private var api: Xtream? = null
    private var job: Job? = null
    private val cache = HashMap<Boolean, Pair<List<Pair<Category, List<Item>>>, List<Item>>>()

    var account by mutableStateOf<Account?>(null); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var live by mutableStateOf(true); private set
    var rows by mutableStateOf(emptyList<Pair<Category, List<Item>>>()); private set
    var all by mutableStateOf(emptyList<Item>()); private set
    var query by mutableStateOf("")
    var playing by mutableStateOf<Pair<List<Item>, Int>?>(null); private set

    // ---- parental lock ----
    var lockedKeys by mutableStateOf(vault.get("lock")); private set
    var hiddenKeys by mutableStateOf(vault.get("hide")); private set
    var unlocked by mutableStateOf(false); private set
    var pinReq by mutableStateOf<PinReq?>(null); private set
    var menu by mutableStateOf<MenuTarget?>(null); private set
    private var fails = 0
    private var blockedUntil = 0L

    // ---- EPG ----
    var now by mutableLongStateOf(System.currentTimeMillis()); private set
    val epgMap = mutableStateMapOf<String, Epg>()
    private val inflight = HashSet<String>()
    private val gate = Semaphore(6)

    init {
        viewModelScope.launch { while (true) { delay(30_000); now = System.currentTimeMillis() } }
        val h = sp.getString("h", null)
        if (h != null) login(Account(h, sp.getString("u", "") ?: "", sp.getString("p", "") ?: ""))
    }

    private fun run(block: suspend () -> Unit) {
        job?.cancel()
        job = viewModelScope.launch {
            busy = true; error = null
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Network error" }
            finally { busy = false }
        }
    }

    fun login(a: Account) = run {
        val x = Xtream(a)
        x.login()
        api = x; account = a
        sp.edit().putString("h", a.host).putString("u", a.user).putString("p", a.pass).apply()
        refresh()
    }

    private suspend fun refresh() {
        val x = api ?: return
        query = ""
        cache[live]?.let { rows = it.first; all = it.second; return }
        rows = emptyList(); all = emptyList()
        val cats = x.categories(live)
        val items = x.items(live, "")
        val grouped = withContext(Dispatchers.Default) { items.groupBy { it.cat } }
        var r = cats.mapNotNull { c -> grouped[c.id]?.takeIf { it.isNotEmpty() }?.let { c to it } }
        if (r.isEmpty() && items.isNotEmpty()) r = listOf(Category("", "All") to items)
        rows = r; all = items
        cache[live] = r to items
    }

    fun setLive(v: Boolean) {
        if (v == live) return
        live = v
        run { refresh() }
    }

    fun logout() {
        job?.cancel()
        sp.edit().clear().apply()
        api = null; account = null; cache.clear(); rows = emptyList(); all = emptyList(); error = null
        playing = null; unlocked = false; epgMap.clear()
    }

    // ---- playback ----
    fun play(list: List<Item>, i: Int) {
        val item = list[i]
        if (!unlocked && isLocked(item)) askPin("Enter PIN to watch") { playing = list to i } else playing = list to i
    }
    fun stop() { playing = null }

    // ---- lock / hide ----
    fun itemKey(i: Item) = "i:${i.live}:${i.id}"
    fun catKey(live: Boolean, id: String) = "c:$live:$id"
    fun isLocked(i: Item) = itemKey(i) in lockedKeys || catKey(i.live, i.cat) in lockedKeys
    fun isHidden(i: Item) = itemKey(i) in hiddenKeys || catKey(i.live, i.cat) in hiddenKeys
    fun visible(list: List<Item>) = if (unlocked) list else list.filter { !isHidden(it) }

    fun askPin(title: String, onOk: () -> Unit) { pinReq = PinReq(title, !vault.hasPin(), onOk) }
    fun cancelPin() { pinReq = null }

    /** Returns null on success, or an error message to show. */
    fun submitPin(p: String): String? {
        val r = pinReq ?: return null
        if (r.create) vault.setPin(p) else {
            val t = System.currentTimeMillis()
            if (t < blockedUntil) return "Too many tries. Wait ${(blockedUntil - t) / 1000 + 1}s"
            if (!vault.check(p)) {
                fails++
                if (fails >= 5) { fails = 0; blockedUntil = t + 30_000 }
                return "Wrong PIN"
            }
            fails = 0
        }
        pinReq = null
        r.onOk()
        return null
    }

    fun toggleUnlock() { if (unlocked) unlocked = false else askPin("Enter PIN to unlock") { unlocked = true } }
    fun relock() { unlocked = false }

    fun openMenu(i: Item, catName: String) { menu = MenuTarget(i, i.cat, catName) }
    fun closeMenu() { menu = null }

    private fun guard(block: () -> Unit) { if (unlocked) block() else askPin("Enter PIN") { block() } }
    private fun withPin(block: () -> Unit) { if (vault.hasPin()) block() else askPin("Create a 4-digit PIN") { block() } }

    fun toggleLock(key: String) {
        menu = null
        if (key in lockedKeys) guard { setKey("lock", key, false) } else withPin { setKey("lock", key, true) }
    }
    fun toggleHide(key: String) {
        menu = null
        if (key in hiddenKeys) guard { setKey("hide", key, false) } else withPin { setKey("hide", key, true) }
    }
    private fun setKey(name: String, key: String, on: Boolean) {
        val cur = if (name == "lock") lockedKeys else hiddenKeys
        val nv = if (on) cur + key else cur - key
        vault.put(name, nv)
        if (name == "lock") lockedKeys = nv else hiddenKeys = nv
    }

    // ---- EPG: lazy, cached until the current programme ends, 6 requests in parallel ----
    suspend fun loadEpg(item: Item) {
        val x = api ?: return
        if (!item.live) return
        val t = System.currentTimeMillis()
        val cur = epgMap[item.id]
        if (cur != null && fresh(cur, t)) return
        if (!inflight.add(item.id)) return
        try {
            gate.withPermit { epgMap[item.id] = Epg(x.shortEpg(item.id), System.currentTimeMillis()) }
        } finally { inflight.remove(item.id) }
    }
    private fun fresh(e: Epg, t: Long): Boolean = when {
        e.now(t) != null -> true
        e.list.isEmpty() -> t - e.at < 10 * 60_000
        else -> t - e.at < 2 * 60_000
    }
}
