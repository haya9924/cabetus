package org.cabetus.data.classweb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.cabetus.data.letus.WebViewCookieJar
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Dedicated read-only CLASS protocol boundary. No interceptors/body logging or external redirects. */
@Singleton
class ClassAttendanceHttpsTransport @Inject constructor() {
    companion object {
        const val HOST = "class.admin.tus.ac.jp"
        const val LIST_PATH = "/uprx/up/km/kmd508/Kmd50801.xhtml"
        val PATHS = setOf(LIST_PATH, "/uprx/up/bs/bsa501/Bsa50101.xhtml", "/uprx/up/xu/xut613/Xut61301.xhtml")
        const val MAX_BYTES = 4L * 1024 * 1024
    }
    class AuthRequired : IOException("CLASS authentication required")
    class Unsupported : IOException("Unsupported CLASS response")
    // Lazy initialization also keeps WebView setup off Hilt construction paths.
    private val client by lazy {
        OkHttpClient.Builder().cookieJar(WebViewCookieJar()).followRedirects(false).followSslRedirects(false)
            .connectTimeout(30, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS).build()
    }
    class Page(val path: String, val html: String) {
        override fun toString() = "ClassAttendanceResponse(redacted)"
    }
    suspend fun get(path: String): Page = request(path, null)
    suspend fun post(path: String, fields: List<Pair<String, String>>): Page = request(path, fields)

    private suspend fun request(path: String, fields: List<Pair<String, String>>?): Page = withContext(Dispatchers.IO) {
        require(path in PATHS)
        var next = "https://$HOST$path"
        var body = fields?.let { pairs -> FormBody.Builder().apply { pairs.forEach { (k, v) -> add(k, v) } }.build() }
        repeat(5) {
            currentCoroutineContext().ensureActive()
            val builder = Request.Builder().url(next).header("Accept", "text/html,application/xhtml+xml")
            if (body != null) builder.post(body!!) else builder.get()
            val response = execute(client.newCall(builder.build()))
            response.use { r ->
                if (r.code == 401) throw AuthRequired()
                if (r.code in 300..399) {
                    val location = r.header("Location") ?: throw Unsupported()
                    val url = r.request.url.resolve(location) ?: throw Unsupported()
                    if (url.scheme != "https" || url.host != HOST) throw Unsupported()
                    if (url.encodedPath in setOf("/uprx/up/pk/pky001/Pky00101.xhtml", "/uprx/MobileShibbolethAuthServlet", "/uprx/ShibbolethAuthServlet")) throw AuthRequired()
                    if (url.encodedPath !in PATHS || url.query != null || url.username.isNotEmpty() || url.password.isNotEmpty() || url.port != 443) throw Unsupported()
                    // Do not replay JSF POST to a new endpoint; only standard POST->GET redirects.
                    if (body != null && r.code !in setOf(302,303)) throw Unsupported()
                    next = url.toString(); body = null
                } else {
                    if (!r.isSuccessful) throw IOException("CLASS request failed")
                    val b = r.body ?: throw Unsupported()
                    if (b.contentLength() > MAX_BYTES) throw Unsupported()
                    val mime = b.contentType()?.let { "${it.type}/${it.subtype}" }
                    if (mime !in setOf("text/html", "application/xhtml+xml")) throw Unsupported()
                    val source = b.source()
                    val buffer = okio.Buffer()
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = source.read(buffer, 8192)
                        if (n < 0) break
                        if (buffer.size > MAX_BYTES) throw Unsupported()
                    }
                    val html = buffer.readString(b.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
                    return@withContext Page(r.request.url.encodedPath, html)
                }
            }
        }
        throw Unsupported()
    }
    private suspend fun execute(call: Call): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(IOException("CLASS network failure"))
            }
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }
        })
    }
}
