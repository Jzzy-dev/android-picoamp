// The Sonos bridge, in the app: the same /sonos/... answers PicoAmp for Windows gives the page
// (src/sonos.c and msxlink.c there), so PicoAmp for the web's Sonos entry works without a PC.
//
//   /sonos/rooms[?fresh=1]          {"state":"done","error":"","rooms":[{"id","name","members"}]}
//   /sonos/now?id=RINCON_..         what the room plays
//   /sonos/cmd?id=..&c=toggle|play|pause|next|prev|seek&ms=..|shuffle&on=0/1|repeat&on=0/1/2
//   /sonos/vol?id=..&v=0..100&m=0/1
//   /sonos/art?id=..                its cover
//
// The speakers are found with an SSDP search (UDP multicast); one of them tells the rooms
// (ZoneGroupTopology GetZoneGroupState). A room is asked with SOAP on port 1400 (AVTransport,
// GroupRenderingControl), as the Sonos apps do in the house: no account, no key.
package nl.jzzy.picoamp

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.webkit.WebResourceResponse
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL

class SonosBridge(private val ctx: Context) {
    data class Room(val id: String, val name: String, val ip: String, val port: Int, val members: Int)

    @Volatile private var rooms: List<Room> = emptyList()
    @Volatile private var state = "idle"
    @Volatile private var error = ""
    private var searchedAt = 0L
    private val searchLock = Object()
    private val artUrl = HashMap<String, String>()

    fun handle(u: Uri): WebResourceResponse {
        val what = u.path?.removePrefix("/sonos/") ?: ""
        val id = u.getQueryParameter("id") ?: ""
        return try {
            when (what) {
                "rooms" -> { search(u.getQueryParameter("fresh") == "1"); json(roomsJson()) }
                "now" -> now(id)?.let { json(it) } ?: Relay.answer(404, "application/json", "{\"error\":\"no answer\"}".toByteArray())
                "cmd" -> ok(command(id, u.getQueryParameter("c") ?: "", (u.getQueryParameter("ms") ?: u.getQueryParameter("on") ?: "0").toIntOrNull() ?: 0))
                "vol" -> ok(volume(id, u.getQueryParameter("v")?.toIntOrNull(), u.getQueryParameter("m")?.toIntOrNull()))
                "art" -> art(id)
                else -> Relay.answer(404, "text/plain", "rooms now cmd vol art\n".toByteArray())
            }
        } catch (e: Exception) {
            Relay.answer(502, "text/plain", "${e.javaClass.simpleName}: ${e.message}\n".toByteArray())
        }
    }

    private fun json(o: JSONObject) = Relay.answer(200, "application/json; charset=utf-8", o.toString().toByteArray())
    private fun ok(b: Boolean) = if (b) Relay.answer(200, "text/plain", "ok\n".toByteArray())
                                 else Relay.answer(502, "text/plain", "the speaker said no\n".toByteArray())

    // ---------------------------------------------------------------- the rooms
    private fun roomsJson(): JSONObject {
        val a = JSONArray()
        for (r in rooms) a.put(JSONObject().put("id", r.id).put("name", r.name).put("members", r.members))
        return JSONObject().put("state", state).put("error", error).put("rooms", a)
    }

    private fun search(fresh: Boolean) = synchronized(searchLock) {
        if (!fresh && state == "done" && System.currentTimeMillis() - searchedAt < 60000) return@synchronized
        state = "loading"
        val cand = LinkedHashSet<Pair<String, Int>>()
        for (r in rooms) cand.add(r.ip to r.port)
        ssdp(cand)
        for ((ip, port) in cand) {
            val found = topology(ip, port)
            if (!found.isNullOrEmpty()) {
                rooms = found
                state = "done"
                error = ""
                searchedAt = System.currentTimeMillis()
                return@synchronized
            }
        }
        rooms = emptyList()
        state = "failed"
        error = if (cand.isEmpty()) "No Sonos speakers found" else "The Sonos speakers did not answer"
    }

    private fun ssdp(cand: MutableSet<Pair<String, Int>>) {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager?
        val lock = wifi?.createMulticastLock("picoamp-sonos")
        try {
            lock?.setReferenceCounted(false)
            lock?.acquire()
            DatagramSocket().use { s ->
                s.soTimeout = 200
                val msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\n" +
                           "ST: urn:schemas-upnp-org:device:ZonePlayer:1\r\n\r\n").toByteArray()
                val to = InetAddress.getByName("239.255.255.250")
                val t0 = System.currentTimeMillis()
                var sent = 0
                val buf = ByteArray(2048)
                while (System.currentTimeMillis() - t0 < 1500) {
                    if (sent < 2 && System.currentTimeMillis() - t0 >= sent * 400L) { s.send(DatagramPacket(msg, msg.size, to, 1900)); sent++ }
                    val p = DatagramPacket(buf, buf.size)
                    try { s.receive(p) } catch (e: SocketTimeoutException) { continue }
                    val text = String(p.data, 0, p.length)
                    Regex("(?im)^location:\\s*http://([^:/\\s]+):(\\d+)").find(text)?.let { cand.add(it.groupValues[1] to it.groupValues[2].toInt()) }
                }
            }
        } catch (e: Exception) {
            // (no network: no speakers)
        } finally {
            try { lock?.release() } catch (e: Exception) { }
        }
    }

    private fun topology(ip: String, port: Int): List<Room>? {
        val body = soap(ip, port, ZGT, "GetZoneGroupState", "") ?: return null
        val zgs = tag(body, "ZoneGroupState") ?: return null
        val out = ArrayList<Room>()
        for (g in Regex("<ZoneGroup\\s([^>]*)>(.*?)</ZoneGroup>", RegexOption.DOT_MATCHES_ALL).findAll(zgs)) {
            val coord = attr(g.groupValues[1], "Coordinator") ?: continue
            var visible = 0
            var lead: Room? = null
            for (m in Regex("<ZoneGroupMember\\s([^>]*?)/?>").findAll(g.groupValues[2])) {
                val a = m.groupValues[1]
                if (attr(a, "IsZoneBridge") == "1") continue
                if (attr(a, "Invisible") != "1") visible++
                if (attr(a, "UUID") == coord) {
                    val loc = Regex("http://([^:/]+):(\\d+)").find(attr(a, "Location") ?: "") ?: continue
                    lead = Room(coord, attr(a, "ZoneName") ?: "", loc.groupValues[1], loc.groupValues[2].toInt(), 1)
                }
            }
            val l = lead ?: continue
            if (l.name.isEmpty()) continue
            val n = if (visible > 0) visible else 1
            out.add(l.copy(name = if (n > 1) "${l.name} + ${n - 1}" else l.name, members = n))
        }
        return out.sortedBy { it.name.lowercase() }
    }

    private fun room(id: String): Room? {
        rooms.firstOrNull { it.id == id }?.let { return it }
        search(true)
        return rooms.firstOrNull { it.id == id }
    }

    // ---------------------------------------------------------------- one room
    private class Now(val room: Room, val state: String, val uri: String, val radio: Boolean, val title: String, val artist: String,
                      val album: String, val elapsedMs: Int, val totalMs: Int, val art: String, val mode: String, val volume: Int, val muted: Boolean)

    private fun ask(id: String, depth: Int = 0): Now? {
        val r = room(id) ?: return null
        val trans = soap(r.ip, r.port, AVT, "GetTransportInfo", INST) ?: return null
        val pos = soap(r.ip, r.port, AVT, "GetPositionInfo", INST) ?: return null
        val uri = tag(pos, "TrackURI") ?: ""
        if (uri.startsWith("x-rincon:") && depth == 0) {
            // the room joined another group: that group's leader
            return ask(uri.removePrefix("x-rincon:"), 1)
        }
        val didl = tag(pos, "TrackMetaData")?.takeIf { it != "NOT_IMPLEMENTED" } ?: ""
        var title = tag(didl, "title") ?: ""
        var artist = tag(didl, "creator") ?: tag(didl, "albumArtist") ?: ""
        var album = tag(didl, "album") ?: ""
        val sc = tag(didl, "streamContent") ?: ""
        var art = tag(didl, "albumArtURI") ?: ""
        var total = hms(tag(pos, "TrackDuration") ?: "")
        val rel = hms(tag(pos, "RelTime") ?: "")
        val radio = RADIO.any { uri.startsWith(it) } || (total <= 0 && sc.isNotEmpty())
        if (uri.startsWith("x-rincon-stream:")) { title = "Line-in"; artist = ""; album = "" }
        else if (uri.startsWith("x-sonos-htastream:")) { title = "TV"; artist = ""; album = "" }
        if (radio) {
            // a station: the song it plays ("Artist - Title", or "TYPE=SNG|TITLE t|ARTIST a|..."), its name
            val media = soap(r.ip, r.port, AVT, "GetMediaInfo", INST)
            val meta = media?.let { tag(it, "CurrentURIMetaData") } ?: ""
            val station = (tag(meta, "title") ?: "").ifEmpty { title }
            if (art.isEmpty()) art = tag(meta, "albumArtURI") ?: ""
            val song = if (sc.startsWith("ZPSTR_")) "" else sc
            when {
                song.contains("TITLE ") && song.contains('|') -> {
                    title = Regex("TITLE ([^|]*)").find(song)?.groupValues?.get(1) ?: ""
                    artist = Regex("ARTIST ([^|]*)").find(song)?.groupValues?.get(1) ?: ""
                }
                song.contains(" - ") -> { artist = song.substringBefore(" - "); title = song.substringAfter(" - ") }
                song.isNotEmpty() -> { title = song; artist = "" }
                else -> { title = station; artist = "" }
            }
            album = station
            total = 0
        }
        val st = when (tag(trans, "CurrentTransportState")) { "PLAYING", "TRANSITIONING" -> "playing"; "PAUSED_PLAYBACK" -> "paused"; else -> "stopped" }
        if (title.isEmpty() && st == "stopped") artist = "Nothing plays in ${r.name}"
        val mode = soap(r.ip, r.port, AVT, "GetTransportSettings", INST)?.let { tag(it, "PlayMode") } ?: "NORMAL"
        val vol = soap(r.ip, r.port, GRC, "GetGroupVolume", INST)?.let { tag(it, "CurrentVolume")?.toIntOrNull() } ?: -1
        val mute = soap(r.ip, r.port, GRC, "GetGroupMute", INST)?.let { tag(it, "CurrentMute") == "1" } ?: false
        if (art.startsWith("/")) art = "http://${r.ip}:${r.port}$art"
        synchronized(artUrl) { artUrl[id] = art }
        return Now(r, st, uri, radio, title, artist, album, if (rel > 0) rel * 1000 else 0, if (total > 0) total * 1000 else 0, art, mode, vol, mute)
    }

    private fun now(id: String): JSONObject? {
        val n = ask(id) ?: return null
        val canQueue = !n.radio && n.title.isNotEmpty()
        val repeat = when { n.mode.contains("REPEAT_ONE") -> 1; n.mode == "REPEAT_ALL" || n.mode == "SHUFFLE" -> 2; else -> 0 }
        return JSONObject()
            .put("state", n.state).put("app", "Sonos ${n.room.name}")
            .put("title", n.title).put("artist", n.artist).put("album", n.album)
            .put("elapsed_ms", n.elapsedMs).put("total_ms", n.totalMs)
            .put("can_seek", n.totalMs > 0 && !n.radio).put("can_next", canQueue).put("can_prev", canQueue)
            .put("can_shuffle", canQueue).put("can_repeat", canQueue)
            .put("shuffle", n.mode.startsWith("SHUFFLE")).put("repeat", repeat)
            .put("volume", n.volume).put("muted", n.muted)
            .put("art", n.art.hashCode().toLong() and 0x7fffffffL)
    }

    private fun command(id: String, c: String, arg: Int): Boolean {
        val n = ask(id) ?: return false
        val r = n.room
        fun avt(action: String, args: String = INST) = soap(r.ip, r.port, AVT, action, args) != null
        return when (c) {
            "toggle" -> if (n.state == "playing") avt("Pause") || avt("Stop") else avt("Play", "$INST<Speed>1</Speed>")
            "play" -> avt("Play", "$INST<Speed>1</Speed>")
            "pause" -> avt("Pause") || avt("Stop")          // (a station that cannot pause stops)
            "next" -> avt("Next")
            "prev" -> avt("Previous")
            "seek" -> {
                val s = (if (arg < 0) 0 else arg) / 1000
                avt("Seek", "$INST<Unit>REL_TIME</Unit><Target>%d:%02d:%02d</Target>".format(s / 3600, s / 60 % 60, s % 60))
            }
            "shuffle", "repeat" -> {
                // NP_REPEAT_xxx of the web: 0 off, 1 the track, 2 the list
                val sh = if (c == "shuffle") arg != 0 else n.mode.startsWith("SHUFFLE")
                val rp = if (c == "repeat") arg else when { n.mode.contains("REPEAT_ONE") -> 1; n.mode == "REPEAT_ALL" || n.mode == "SHUFFLE" -> 2; else -> 0 }
                val m = if (sh) arrayOf("SHUFFLE_NOREPEAT", "SHUFFLE_REPEAT_ONE", "SHUFFLE")[rp.coerceIn(0, 2)]
                        else arrayOf("NORMAL", "REPEAT_ONE", "REPEAT_ALL")[rp.coerceIn(0, 2)]
                avt("SetPlayMode", "$INST<NewPlayMode>$m</NewPlayMode>")
            }
            else -> false
        }
    }

    private fun volume(id: String, v: Int?, m: Int?): Boolean {
        val r = room(id) ?: return false
        var ok = v != null || m != null
        if (v != null) ok = soap(r.ip, r.port, GRC, "SetGroupVolume", "$INST<DesiredVolume>${v.coerceIn(0, 100)}</DesiredVolume>") != null && ok
        if (m != null) ok = soap(r.ip, r.port, GRC, "SetGroupMute", "$INST<DesiredMute>${if (m != 0) 1 else 0}</DesiredMute>") != null && ok
        return ok
    }

    private fun art(id: String): WebResourceResponse {
        var u = synchronized(artUrl) { artUrl[id] }
        if (u == null) { ask(id); u = synchronized(artUrl) { artUrl[id] } }
        if (u.isNullOrEmpty()) return Relay.answer(404, "text/plain", "no cover\n".toByteArray())
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 3000
        c.readTimeout = 6000
        if (c.responseCode != 200) { c.disconnect(); return Relay.answer(404, "text/plain", "no cover\n".toByteArray()) }
        val b = c.inputStream.use { it.readBytes() }
        val type = c.contentType ?: "image/jpeg"
        c.disconnect()
        return Relay.answer(200, type, b)
    }

    // ---------------------------------------------------------------- SOAP, XML
    private fun soap(ip: String, port: Int, sv: Int, action: String, args: String): String? {
        val urn = URNS[sv]
        val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                   "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:$action xmlns:u=\"$urn\">$args</u:$action></s:Body></s:Envelope>"
        return try {
            val c = URL("http://$ip:$port${PATHS[sv]}").openConnection() as HttpURLConnection
            c.connectTimeout = 2000
            c.readTimeout = 5000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            c.setRequestProperty("SOAPACTION", "\"$urn#$action\"")
            c.outputStream.use { it.write(body.toByteArray()) }
            if (c.responseCode != 200) { c.disconnect(); return null }
            val out = ByteArrayOutputStream()
            c.inputStream.use { it.copyTo(out) }
            c.disconnect()
            out.toString("UTF-8")
        } catch (e: Exception) {
            null
        }
    }

    private fun tag(xml: String, name: String): String? =
        Regex("<(?:[\\w-]+:)?$name(?:\\s[^>]*)?>(.*?)</(?:[\\w-]+:)?$name>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.let { unescape(it) }

    private fun attr(attrs: String, name: String): String? =
        Regex("(?:^|\\s)$name=\"([^\"]*)\"").find(attrs)?.groupValues?.get(1)?.let { unescape(it) }

    private fun unescape(s: String): String {
        if (!s.contains('&')) return s
        return Regex("&(lt|gt|amp|quot|apos|#\\d+|#x[0-9a-fA-F]+);").replace(s) { m ->
            when (val e = m.groupValues[1]) {
                "lt" -> "<"; "gt" -> ">"; "amp" -> "&"; "quot" -> "\""; "apos" -> "'"
                else -> {
                    val cp = if (e[1] == 'x' || e[1] == 'X') e.substring(2).toInt(16) else e.substring(1).toInt()
                    String(Character.toChars(cp))
                }
            }
        }
    }

    private fun hms(t: String): Int {
        val p = t.split(':')
        if (p.size != 3) return -1
        return try { p[0].toInt() * 3600 + p[1].toInt() * 60 + p[2].toInt() } catch (e: NumberFormatException) { -1 }
    }

    companion object {
        const val AVT = 0
        const val GRC = 1
        const val ZGT = 2
        const val INST = "<InstanceID>0</InstanceID>"
        val PATHS = arrayOf("/MediaRenderer/AVTransport/Control", "/MediaRenderer/GroupRenderingControl/Control", "/ZoneGroupTopology/Control")
        val URNS = arrayOf("urn:schemas-upnp-org:service:AVTransport:1", "urn:schemas-upnp-org:service:GroupRenderingControl:1",
                           "urn:schemas-upnp-org:service:ZoneGroupTopology:1")
        val RADIO = listOf("x-sonosapi-stream:", "x-sonosapi-radio:", "x-rincon-mp3radio:", "x-sonosapi-hls:", "aac:", "hls-radio:")
    }
}
