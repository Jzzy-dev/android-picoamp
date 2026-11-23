// Pictures and music from the network, for PicoAmp's Pictures page and its music list (the TV has
// little room and no easy way in for files), and the music on the TV itself:
//
//   /net/servers[?fresh=1]          the media servers (DLNA, UPnP) in the network:
//                                   [{"src":"dlna:<description's URL>","name":".."}]
//   /net/list?src=..&path=a/b       a folder's folders and pictures, a line each: "d name" or "f name"
//   /net/file?src=..&path=a/b/c.jpg a picture's bytes
//   ...&kind=music                  the same for music: a server's songs; a song streamed (Range: a
//                                   part, 206: seeking, the tags)
//
// src:  smb://[user[:password]@]host/share[/folder]   a Windows or NAS share (SMB 2/3; no user: guest)
//       dlna:<URL of the server's description>        a media server, browsed by its folders
//       local:                                        the music on this TV and its USB sticks (Android's
//                                                     music library; the page asks for it first)
// (PicoAmp for Windows serves its pictures itself: /pics/ on its MSX link's port.)
package nl.jzzy.picoamp

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.provider.MediaStore
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
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.util.EnumSet
import java.util.concurrent.TimeUnit

class NetPictures(private val ctx: Context) {
    fun handle(u: Uri, headers: Map<String, String> = emptyMap()): WebResourceResponse {
        val what = u.path?.removePrefix("/net/") ?: ""
        val src = u.getQueryParameter("src") ?: ""
        val path = (u.getQueryParameter("path") ?: "").trim('/')
        val music = u.getQueryParameter("kind") == "music"
        return try {
            when (what) {
                "servers" -> Relay.answer(200, "application/json; charset=utf-8", servers(u.getQueryParameter("fresh") == "1").toString().toByteArray())
                "list" -> Relay.answer(200, "text/plain; charset=utf-8", list(src, path, music).joinToString("") { "${if (it.second) 'd' else 'f'} ${it.first}\n" }.toByteArray())
                "file" -> if (music) stream(src, path, headers.entries.firstOrNull { it.key.equals("Range", true) }?.value) else {
                    val b = file(src, path)
                    Relay.answer(200, typeOf(path), b)
                }
                else -> Relay.answer(404, "text/plain", "servers list file\n".toByteArray())
            }
        } catch (e: Exception) {
            Relay.answer(502, "text/plain; charset=utf-8", (e.message ?: e.javaClass.simpleName).toByteArray())
        }
    }

    private fun list(src: String, path: String, music: Boolean = false): List<Pair<String, Boolean>> = when {
        src.startsWith("smb://") -> smbList(src, path)
        src.startsWith("dlna:") -> dlnaList(src.removePrefix("dlna:"), path, music).map { it.name to it.dir }
        src.startsWith("local:") -> localList(path)
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

    private fun browse(loc: String, id: String, music: Boolean = false): List<Entry> {
        val key = "$loc|$id|$music"
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
                if (!dir && music) {
                    // a song: its own file (the largest: not a smaller one made for the network)
                    val res = Regex("<res\\s([^>]*)>(.*?)</res>", RegexOption.DOT_MATCHES_ALL).findAll(body)
                        .map { attr(it.groupValues[1], "protocolInfo").orEmpty() to (it.groupValues[1] to unescape(it.groupValues[2].trim())) }
                        .filter { it.first.contains(":audio/") }.toList()
                    if (res.isEmpty()) continue
                    val best = res.maxByOrNull { (_, r2) -> attr(r2.first, "size")?.toLongOrNull() ?: 0L }!!
                    url = best.second.second
                    // a name the music list knows as a song (its number first: the album's order)
                    val mime = best.first.split(':').getOrNull(2)?.lowercase() ?: "audio/mpeg"
                    val ext = when {
                        mime.contains("flac") -> ".flac"; mime.contains("mp4") || mime.contains("m4a") -> ".m4a"
                        mime.contains("aac") -> ".aac"; mime.contains("ogg") || mime.contains("vorbis") -> ".ogg"
                        mime.contains("opus") -> ".opus"; mime.contains("wav") || mime.contains("l16") -> ".wav"
                        mime.contains("webm") -> ".webm"; else -> ".mp3"
                    }
                    if (!Regex("(?i)\\.(mp3|flac|m4a|aac|ogg|oga|opus|wav|webm)$").containsMatchIn(title)) title += ext
                    tag(body, "originalTrackNumber")?.trim()?.toIntOrNull()?.let { if (!Regex("^\\d").containsMatchIn(title)) title = "%02d %s".format(it, title) }
                } else if (!dir) {
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
    private fun folderId(loc: String, path: String, music: Boolean = false): String {
        var id = "0"
        for (p in path.split('/').filter { it.isNotEmpty() })
            id = browse(loc, id, music).firstOrNull { it.dir && it.name == p }?.id ?: throw IllegalArgumentException("No folder $p")
        return id
    }

    private fun dlnaList(loc: String, path: String, music: Boolean = false): List<Entry> {
        if (path.isEmpty()) synchronized(folders) { folders.keys.removeAll { it.startsWith("$loc|") } }   // (the top: afresh)
        return browse(loc, folderId(loc, path, music), music)
    }

    private fun dlnaFile(loc: String, path: String): ByteArray {
        val dir = path.substringBeforeLast('/', "")
        val e = browse(loc, folderId(loc, dir)).firstOrNull { !it.dir && it.name == path.substringAfterLast('/') }
            ?: throw IllegalArgumentException("No such picture")
        return get(e.url)
    }

    // ---------------------------------------------------------------- a song, streamed
    // Range "bytes=a-b", "bytes=a-" or "bytes=-n": that part (206), else all of it; the page's player
    // seeks with it, the tags are read with it
    private fun stream(src: String, path: String, range: String?): WebResourceResponse = when {
        src.startsWith("dlna:") -> dlnaStream(src.removePrefix("dlna:"), path, range)
        src.startsWith("smb://") -> smbStream(src, path, range)
        src.startsWith("local:") -> localStream(path, range)
        else -> throw IllegalArgumentException("Not a share or a media server")
    }

    private fun ranged(size: Long, range: String?, type: String, open: (Long) -> InputStream): WebResourceResponse {
        var a = 0L
        var b = size - 1
        var part = false
        Regex("bytes=(\\d*)-(\\d*)").find(range ?: "")?.let { m ->
            val s0 = m.groupValues[1]
            val e0 = m.groupValues[2]
            if (s0.isEmpty() && e0.isNotEmpty()) a = maxOf(0L, size - e0.toLong())
            else { a = s0.toLongOrNull() ?: 0L; if (e0.isNotEmpty()) b = minOf(e0.toLong(), size - 1) }
            part = true
        }
        val h = mutableMapOf("Access-Control-Allow-Origin" to "*", "Access-Control-Expose-Headers" to "Content-Range, Content-Length",
                             "Accept-Ranges" to "bytes", "Cache-Control" to "no-store")
        if (part && (a > b || a >= size)) {
            h["Content-Range"] = "bytes */$size"
            return WebResourceResponse("text/plain", null, 416, "Range Not Satisfiable", h, java.io.ByteArrayInputStream(ByteArray(0)))
        }
        val len = b - a + 1
        h["Content-Length"] = len.toString()
        if (part) h["Content-Range"] = "bytes $a-$b/$size"
        return WebResourceResponse(type, null, if (part) 206 else 200, if (part) "Partial Content" else "OK", h, Limited(open(a), len))
    }

    // at most n bytes of a stream (and it closed with it)
    private class Limited(private val inp: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int = if (left <= 0) -1 else inp.read().also { if (it >= 0) left-- }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = inp.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }
        override fun close() = inp.close()
    }

    private fun smbStream(src: String, path: String, range: String?): WebResourceResponse = smb(src) { s ->
        val f = s.share.openFile(smbPath(s, path), EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL,
                                 SMB2CreateDisposition.FILE_OPEN, null)
        val size = f.fileInformation.standardInformation.endOfFile
        ranged(size, range, songType(path)) { at ->
            object : InputStream() {
                var pos = at
                override fun read(): Int { val one = ByteArray(1); return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 255 }
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = f.read(b, pos, off, len)
                    if (n > 0) pos += n
                    return if (n <= 0) -1 else n
                }
                override fun close() { try { f.close() } catch (e: Exception) { } }
            }
        }
    }

    // a media server's song: asked of the server with the same Range, its answer passed on
    private fun dlnaStream(loc: String, path: String, range: String?): WebResourceResponse {
        val dir = path.substringBeforeLast('/', "")
        val e = browse(loc, folderId(loc, dir, true), true).firstOrNull { !it.dir && it.name == path.substringAfterLast('/') }
            ?: throw IllegalArgumentException("No such song")
        val c = URL(e.url).openConnection() as HttpURLConnection
        c.connectTimeout = 4000
        c.readTimeout = 30000
        if (range != null) c.setRequestProperty("Range", range)
        val code = c.responseCode
        if (code != 200 && code != 206) { c.disconnect(); throw IllegalStateException("${server(loc).name} said $code") }
        val h = mutableMapOf("Access-Control-Allow-Origin" to "*", "Access-Control-Expose-Headers" to "Content-Range, Content-Length",
                             "Accept-Ranges" to "bytes", "Cache-Control" to "no-store")
        c.getHeaderField("Content-Length")?.let { h["Content-Length"] = it }
        c.getHeaderField("Content-Range")?.let { h["Content-Range"] = it }
        val type = c.contentType?.substringBefore(';')?.takeIf { it.startsWith("audio/") } ?: songType(path)
        return WebResourceResponse(type, null, code, if (code == 206) "Partial Content" else "OK", h, c.inputStream)
    }

    // ---------------------------------------------------------------- the music on this TV
    // Android's music library (the TV's storage and its USB sticks): a folder for each storage (its
    // name: "Internal shared storage", "SanDisk USB drive"), the folders of its songs in it
    private class Song(val path: String, val uri: Uri)
    private var songs: List<Song> = emptyList()
    private var songsAt = 0L

    private fun localSongs(fresh: Boolean): List<Song> = synchronized(this) {
        if (!fresh && System.currentTimeMillis() - songsAt < 30_000) return songs
        val out = ArrayList<Song>()
        val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        val vols = if (Build.VERSION.SDK_INT >= 29) MediaStore.getExternalVolumeNames(ctx).toList() else listOf("external")
        val labels = HashSet<String>()
        for (v in vols) {
            val base = if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.getContentUri(v) else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            var label = if (Build.VERSION.SDK_INT >= 29) (try { sm.getStorageVolume(base)?.getDescription(ctx) } catch (e: Exception) { null }) ?: v else "This TV"
            label = label.replace('/', '-')
            if (!labels.add(label)) { label = "$label ($v)"; labels.add(label) }
            val cols = if (Build.VERSION.SDK_INT >= 29)
                arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.RELATIVE_PATH)
            else arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.DATA)
            try {
                ctx.contentResolver.query(base, cols, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        val name = c.getString(1) ?: continue
                        val rel = if (Build.VERSION.SDK_INT >= 29) c.getString(2).orEmpty()
                                  else (c.getString(2) ?: "").substringBeforeLast('/', "").let {
                                      // (/storage/emulated/0/Music/x, /storage/1234-ABCD/Music/x: after the storage)
                                      Regex("^/storage/(emulated/\\d+|[^/]+)/?").replace(it, "")
                                  }
                        val dir = rel.trim('/')
                        out.add(Song(listOf(label, dir, name).filter { it.isNotEmpty() }.joinToString("/"), ContentUris.withAppendedId(base, id)))
                    }
                }
            } catch (e: SecurityException) {
                throw IllegalStateException("PicoAmp may not read the music on this TV")
            }
        }
        songs = out
        songsAt = System.currentTimeMillis()
        out
    }

    private fun localList(path: String): List<Pair<String, Boolean>> {
        val all = localSongs(path.isEmpty())
        val pre = if (path.isEmpty()) "" else "$path/"
        val seen = LinkedHashMap<String, Boolean>()
        for (sng in all) {
            if (!sng.path.startsWith(pre)) continue
            val rest = sng.path.substring(pre.length)
            val sl = rest.indexOf('/')
            if (sl < 0) seen[rest] = false else seen.putIfAbsent(rest.substring(0, sl), true)
        }
        if (seen.isEmpty() && path.isNotEmpty()) throw IllegalArgumentException("No such folder")
        return seen.map { it.key to it.value }
    }

    private fun localStream(path: String, range: String?): WebResourceResponse {
        val sng = localSongs(false).firstOrNull { it.path == path } ?: localSongs(true).firstOrNull { it.path == path }
            ?: throw IllegalArgumentException("No such song")
        val size = ctx.contentResolver.openFileDescriptor(sng.uri, "r")?.use { it.statSize } ?: throw IllegalStateException("Cannot read it")
        return ranged(size, range, songType(path)) { at ->
            val pfd = ctx.contentResolver.openFileDescriptor(sng.uri, "r") ?: throw IllegalStateException("Cannot read it")
            ParcelFileDescriptor.AutoCloseInputStream(pfd).also { it.channel.position(at) }
        }
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
        fun songType(path: String): String = when (path.substringAfterLast('.').lowercase()) {
            "flac" -> "audio/flac"; "m4a", "mp4" -> "audio/mp4"; "aac" -> "audio/aac"
            "ogg", "oga", "opus" -> "audio/ogg"; "wav" -> "audio/wav"; "webm" -> "audio/webm"
            else -> "audio/mpeg"
        }

        fun typeOf(path: String): String = when (path.substringAfterLast('.').lowercase()) {
            "png" -> "image/png"; "gif" -> "image/gif"; "bmp" -> "image/bmp"; "webp" -> "image/webp"
            else -> "image/jpeg"
        }
    }
}
