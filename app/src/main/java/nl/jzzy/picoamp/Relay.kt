// The page's relay, in the app: GET /relay/?url=<address> fetches that page and hands it on, as
// relay/worker.js of PicoAmp for the web does on Cloudflare (other sites' CORS rules are a
// browser's: an app may read any site). The song titles of stations, the markets and the news.
package nl.jzzy.picoamp

import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

class Relay(private val agent: String) {
    private val max = 8 * 1024 * 1024

    fun handle(u: Uri): WebResourceResponse {
        val target = u.getQueryParameter("url")
        if (target == null || !(target.startsWith("http://") || target.startsWith("https://")))
            return answer(400, "text/plain", "url=http(s)://... wanted\n".toByteArray())
        return try {
            fetch(target)
        } catch (e: Exception) {
            answer(502, "text/plain", "${e.javaClass.simpleName}: ${e.message}\n".toByteArray())
        }
    }

    private fun fetch(first: String): WebResourceResponse {
        var url = first
        for (hop in 0 until 6) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 10000
            c.readTimeout = 20000
            c.setRequestProperty("User-Agent", agent)
            c.setRequestProperty("Accept", "*/*")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: break
                url = URL(URL(url), loc).toString()
                c.disconnect()
                continue
            }
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val out = ByteArrayOutputStream()
            if (stream != null) {
                val b = ByteArray(16384)
                while (out.size() < max) {
                    val n = stream.read(b)
                    if (n < 0) break
                    out.write(b, 0, n)
                }
                stream.close()
            }
            val type = c.contentType ?: "application/octet-stream"
            c.disconnect()
            return answer(code, type, out.toByteArray())
        }
        return answer(508, "text/plain", "too many redirects\n".toByteArray())
    }

    companion object {
        fun answer(code: Int, type: String, body: ByteArray): WebResourceResponse {
            val mime = type.substringBefore(';').trim()
            val charset = Regex("charset=([^;]+)", RegexOption.IGNORE_CASE).find(type)?.groupValues?.get(1)?.trim('"', ' ')
            val reason = when (code) { 200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 404 -> "Not Found"; 502 -> "Bad Gateway"; else -> "Status" }
            return WebResourceResponse(mime, charset, code, reason,
                mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-store"), ByteArrayInputStream(body))
        }
    }
}
