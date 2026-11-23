// Pictures from the network, for PicoAmp's Pictures page (the TV has little room and no easy way in
// for files):
//
//   /net/servers[?fresh=1]          the media servers (DLNA, UPnP) in the network:
//                                   [{"src":"dlna:<description's URL>","name":".."}]
//   /net/list?src=..&path=a/b       a folder's folders and pictures, a line each: "d name" or "f name"
//   /net/file?src=..&path=a/b/c.jpg a picture's bytes
//
// src:  smb://[user[:password]@]host/share[/folder]   a Windows or NAS share (SMB 2/3; no user: guest)
//       dlna:<URL of the server's description>        a media server, browsed by its folders
// (PicoAmp for Windows serves its pictures itself: /pics/ on its MSX link's port.)
package nl.jzzy.picoamp

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.webkit.WebResourceResponse
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.util.EnumSet
import java.util.concurrent.TimeUnit

class NetPictures(private val ctx: Context) {
    fun handle(u: Uri): WebResourceResponse {
        val what = u.path?.removePrefix("/net/") ?: ""
        val src = u.getQueryParameter("src") ?: ""
        val path = (u.getQueryParameter("path") ?: "").trim('/')
        return try {
            when (what) {
                "servers" -> Relay.answer(200, "application/json; charset=utf-8", servers(u.getQueryParameter("fresh") == "1").toString().toByteArray())
                "list" -> Relay.answer(200, "text/plain; charset=utf-8", list(src, path).joinToString("") { "${if (it.second) 'd' else 'f'} ${it.first}\n" }.toByteArray())
                "file" -> {
                    val b = file(src, path)
                    Relay.answer(200, typeOf(path), b)
                }
                else -> Relay.answer(404, "text/plain", "servers list file\n".toByteArray())
            }
        } catch (e: Exception) {
            Relay.answer(502, "text/plain; charset=utf-8", (e.message ?: e.javaClass.simpleName).toByteArray())
        }
    }

    private fun list(src: String, path: String): List<Pair<String, Boolean>> = when {
        src.startsWith("smb://") -> smbList(src, path)
        src.startsWith("dlna:") -> dlnaList(src.removePrefix("dlna:"), path).map { it.name to it.dir }
        else -> throw IllegalArgumentException("Not a share or a media server")
    }

    private fun file(src: String, path: String): ByteArray = when {
        src.startsWith("smb://") -> smbFile(src, path)
        src.startsWith("dlna:") -> dlnaFile(src.removePrefix("dlna:"), path)
        else -> throw IllegalArgumentException("Not a share or a media server")
    }

    // ---------------------------------------------------------------- a Windows or NAS share (SMB)
    private class Smb(val conn: Connection, val session: Session, val share: DiskShare, val base: String)
    private val smbs = HashMap<String, Smb>()
    private val client by lazy { SMBClient(SmbConfig.builder().withTimeout(10, TimeUnit.SECONDS).withSoTimeout(20, TimeUnit.SECONDS).build()) }

    private fun smbOpen(src: String, fresh: Boolean = false): Smb = synchronized(smbs) {
        val had = smbs[src]
        if (had != null && !fresh && had.conn.isConnected) return had
        try { had?.conn?.close() } catch (e: Exception) { }
        smbs.remove(src)
        val u = Uri.parse(src)
        val host = u.host ?: throw IllegalArgumentException("No PC or NAS in the share's address")
        val parts = (u.path ?: "").trim('/').split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) throw IllegalArgumentException("No share in the address (\\\\PC\\share)")
        val ui = u.encodedUserInfo?.let { Uri.decode(it.substringBefore(':')) to Uri.decode(it.substringAfter(':', "")) }
        val auth = if (ui == null || ui.first.isEmpty()) AuthenticationContext.guest() else {
            // DOMAIN\user, DOMAIN;user or user
            val (dom, user) = ui.first.split('\\', ';').let { if (it.size > 1) it[0] to it[1] else null to it[0] }
            AuthenticationContext(user, ui.second.toCharArray(), dom)
        }
        val conn = if (u.port > 0) client.connect(host, u.port) else client.connect(host)
        try {
            val session = conn.authenticate(auth)
            val share = session.connectShare(parts[0]) as? DiskShare ?: throw IllegalArgumentException("${parts[0]} is not a folder share")
            Smb(conn, session, share, parts.drop(1).joinToString("\\")).also { smbs[src] = it }
        } catch (e: Exception) {
            try { conn.close() } catch (x: Exception) { }
            throw e
        }
    }

    private fun <T> smb(src: String, f: (Smb) -> T): T =
        try { f(smbOpen(src)) } catch (e: Exception) { f(smbOpen(src, true)) }      // (once more: the PC may have closed it)

    private fun smbPath(s: Smb, path: String): String =
        listOf(s.base, path.replace('/', '\\')).filter { it.isNotEmpty() }.joinToString("\\")

    private fun smbList(src: String, path: String): List<Pair<String, Boolean>> = smb(src) { s ->
        s.share.list(smbPath(s, path)).mapNotNull {
            val n = it.fileName
            if (n == "." || n == ".." || n.startsWith(".")) null
            else n to (it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L)
        }
    }

    private fun smbFile(src: String, path: String): ByteArray = smb(src) { s ->
        s.share.openFile(smbPath(s, path), EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL,
                         SMB2CreateDisposition.FILE_OPEN, null).use { f ->
            val out = ByteArrayOutputStream()
            f.inputStream.use { it.copyTo(out) }
            out.toByteArray()
        }
    }

    // ---------------------------------------------------------------- a media server (DLNA)
    private class Entry(val name: String, val dir: Boolean, val id: String, val url: String)
    private class Server(val name: String, val control: String)
    private val dlnaServers = HashMap<String, Server>()          // by the description's URL
    private val folders = HashMap<String, List<Entry>>()         // by "location|object id"
    private var found = JSONArray()
    private var foundAt = 0L

    private fun servers(fresh: Boolean): JSONArray = synchronized(this) {
        if (!fresh && System.currentTimeMillis() - foundAt < 60_000 && found.length() > 0) return found
        val out = JSONArray()
        for (loc in ssdp()) {
            val s = try { server(loc) } catch (e: Exception) { null } ?: continue
            out.put(JSONObject().put("src", "dlna:$loc").put("name", s.name))
        }
        found = out
        foundAt = System.currentTimeMillis()
        out
    }

    private fun ssdp(): Set<String> {
        val locs = LinkedHashSet<String>()
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager?
        val lock = wifi?.createMulticastLock("picoamp-dlna")
        try {
            lock?.setReferenceCounted(false)
            lock?.acquire()
            DatagramSocket().use { s ->
                s.soTimeout = 200
                val msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\n" +
                           "ST: urn:schemas-upnp-org:device:MediaServer:1\r\n\r\n").toByteArray()
                val to = InetAddress.getByName("239.255.255.250")
                val t0 = System.currentTimeMillis()
                var sent = 0
                val buf = ByteArray(2048)
                while (System.currentTimeMillis() - t0 < 2500) {
                    if (sent < 3 && System.currentTimeMillis() - t0 >= sent * 500L) { s.send(DatagramPacket(msg, msg.size, to, 1900)); sent++ }
                    val p = DatagramPacket(buf, buf.size)
                    try { s.receive(p) } catch (e: SocketTimeoutException) { continue }
                    Regex("(?im)^location:\\s*(\\S+)").find(String(p.data, 0, p.length))?.let { locs.add(it.groupValues[1]) }
                }
            }
        } catch (e: Exception) {
            // (no network: no servers)
        } finally {
            try { lock?.release() } catch (e: Exception) { }
        }
        return locs
    }

    private fun server(loc: String): Server = synchronized(dlnaServers) {
        dlnaServers[loc]?.let { return it }
        val xml = String(get(loc), Charsets.UTF_8)
        val name = tag(xml, "friendlyName") ?: URL(loc).host
        // the ContentDirectory service's control URL (against URLBase, else the description's)
        val svc = Regex("<service>(.*?)</service>", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { it.groupValues[1] }
            .firstOrNull { (tag(it, "serviceType") ?: "").contains("ContentDirectory") }
            ?: throw IllegalArgumentException("$name has no folders to browse")
        val base = tag(xml, "URLBase")?.takeIf { it.isNotBlank() } ?: loc
        val ctl = URL(URL(base), tag(svc, "controlURL") ?: "").toString()
        Server(name, ctl).also { dlnaServers[loc] = it }
    }

    private fun browse(loc: String, id: String): List<Entry> {
        val key = "$loc|$id"
        synchronized(folders) { folders[key]?.let { return it } }
        val s = server(loc)
        val out = ArrayList<Entry>()
        val names = HashSet<String>()
        var start = 0
        while (true) {
            val r = soap(s.control, "Browse",
                "<ObjectID>${esc(id)}</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag><Filter>*</Filter>" +
                "<StartingIndex>$start</StartingIndex><RequestedCount>500</RequestedCount><SortCriteria></SortCriteria>")
                ?: throw IllegalStateException("${s.name} did not answer")
            val didl = tag(r, "Result") ?: ""
            for (m in Regex("<(container|item)\\s([^>]*)>(.*?)</\\1>", RegexOption.DOT_MATCHES_ALL).findAll(didl)) {
                val dir = m.groupValues[1] == "container"
                val oid = attr(m.groupValues[2], "id") ?: continue
                val body = m.groupValues[3]
                var title = (tag(body, "title") ?: oid).replace('/', '-').trim()
                var url = ""
                if (!dir) {
                    // a picture: its largest one (not a thumbnail)
                    val res = Regex("<res\\s([^>]*)>(.*?)</res>", RegexOption.DOT_MATCHES_ALL).findAll(body)
                        .map { attr(it.groupValues[1], "protocolInfo").orEmpty() to (it.groupValues[1] to unescape(it.groupValues[2].trim())) }
                        .filter { it.first.contains(":image/") }.toList()
                    if (res.isEmpty()) continue
                    val best = res.maxByOrNull { (pi, r2) ->
                        val wh = attr(r2.first, "resolution")?.split('x')?.mapNotNull { it.toIntOrNull() }
                        val px = if (wh != null && wh.size == 2) wh[0].toLong() * wh[1] else 0L
                        px + (attr(r2.first, "size")?.toLongOrNull() ?: 0L) / 1000 - (if (pi.contains("_TN")) 1L shl 40 else 0L)
                    }!!
                    url = best.second.second
                    // a name the Pictures page knows as a picture
                    val mime = best.first.split(':').getOrNull(2) ?: "image/jpeg"
                    val ext = when { mime.contains("png") -> ".png"; mime.contains("gif") -> ".gif"; mime.contains("bmp") -> ".bmp"; mime.contains("webp") -> ".webp"; else -> ".jpg" }
                    if (!Regex("(?i)\\.(jpe?g|png|gif|bmp|webp)$").containsMatchIn(title)) title += ext
                }
                if (title.isEmpty()) title = oid
                var n = title
                var k = 2
                while (!names.add(n)) {
                    n = if (dir) "$title ($k)" else "${title.substringBeforeLast('.')} ($k).${title.substringAfterLast('.')}"
                    k++
                }
                out.add(Entry(n, dir, oid, url))
            }
            val got = tag(r, "NumberReturned")?.toIntOrNull() ?: 0
            val total = tag(r, "TotalMatches")?.toIntOrNull() ?: 0
            start += got
            if (got == 0 || start >= total || start >= 10_000) break
        }
        synchronized(folders) { folders[key] = out }
        return out
    }

    // a path's folder: from the top ("0") down by the folders' names
    private fun folderId(loc: String, path: String): String {
        var id = "0"
        for (p in path.split('/').filter { it.isNotEmpty() })
            id = browse(loc, id).firstOrNull { it.dir && it.name == p }?.id ?: throw IllegalArgumentException("No folder $p")
        return id
    }

    private fun dlnaList(loc: String, path: String): List<Entry> {
        if (path.isEmpty()) synchronized(folders) { folders.keys.removeAll { it.startsWith("$loc|") } }   // (the top: afresh)
        return browse(loc, folderId(loc, path))
    }

    private fun dlnaFile(loc: String, path: String): ByteArray {
        val dir = path.substringBeforeLast('/', "")
        val e = browse(loc, folderId(loc, dir)).firstOrNull { !it.dir && it.name == path.substringAfterLast('/') }
            ?: throw IllegalArgumentException("No such picture")
        return get(e.url)
    }

    // ---------------------------------------------------------------- HTTP, SOAP, XML
    private fun get(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 4000
        c.readTimeout = 20000
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            val out = ByteArrayOutputStream()
            c.inputStream.use { it.copyTo(out) }
            return out.toByteArray()
        } finally {
            c.disconnect()
        }
    }

    private fun soap(url: String, action: String, args: String): String? {
        val urn = "urn:schemas-upnp-org:service:ContentDirectory:1"
        val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                   "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:$action xmlns:u=\"$urn\">$args</u:$action></s:Body></s:Envelope>"
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 4000
            c.readTimeout = 20000
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

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

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

    companion object {
        fun typeOf(path: String): String = when (path.substringAfterLast('.').lowercase()) {
            "png" -> "image/png"; "gif" -> "image/gif"; "bmp" -> "image/bmp"; "webp" -> "image/webp"
            else -> "image/jpeg"
        }
    }
}
