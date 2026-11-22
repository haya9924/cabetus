package org.cabetus.ui.browser

import android.webkit.CookieManager
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.OutputStream
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * LETUS の認証付きファイルを、WebView と共有している Cookie でダウンロードする。
 *
 * - 対象は `https://letus.ed.tus.ac.jp`（標準 443、userInfo なし）のみ。
 * - OkHttp の CookieJar は使わず、リダイレクトの各 hop で
 *   [CookieManager.getCookie] の値を明示的に `Cookie` ヘッダーへ設定する。
 * - リダイレクトは自動追従せず、毎 hop で URL を検証しながら最大 5 回まで追う。
 * - HTML（ログインページ等）は保存せず、上限 100MB を超える応答も保存しない。
 * - 認証情報・URL・Cookie をログへ出さない。エラーメッセージは一般的な日本語のみ。
 *
 * [download] が例外を投げた場合、[destination] には部分データが残っている可能性がある。
 * 呼び出し側でファイルを破棄すること。
 */
class LetusDownload(
    private val userAgent: String,
    private val cookieProvider: (String) -> String?,
    private val client: OkHttpClient,
    private val maxBytes: Long = MAX_BYTES,
) {

    constructor(userAgent: String) : this(
        userAgent = userAgent,
        cookieProvider = defaultCookieProvider(),
        client = createClient(),
    )

    /**
     * [url] を取得して [destination] へストリームコピーする。
     *
     * @return 保存したファイルの情報（ファイル名は Content-Disposition か URL から正規化）。
     * @throws LetusDownloadException URL 不正・通信失敗・ログインページ・サイズ超過など。
     */
    fun download(url: String, destination: OutputStream, onProgress: (Long, Long?) -> Unit = { _, _ -> }): DownloadedFile {
        var currentUrl = url.trim()
        var redirectCount = 0

        while (true) {
            if (!isAllowedUrl(currentUrl)) {
                throw LetusDownloadException(MSG_INVALID_URL)
            }

            val request = buildRequest(currentUrl)
            val response = try {
                client.newCall(request).execute()
            } catch (e: IOException) {
                throw LetusDownloadException(MSG_NETWORK, e)
            }

            response.use { res ->
                if (res.isRedirect) {
                    if (redirectCount >= MAX_REDIRECTS) {
                        throw LetusDownloadException(MSG_TOO_MANY_REDIRECTS)
                    }
                    val location = res.header("Location")
                        ?: throw LetusDownloadException(MSG_INVALID_REDIRECT)
                    val resolved = res.request.url.resolve(location)
                        ?: throw LetusDownloadException(MSG_INVALID_REDIRECT)
                    currentUrl = resolved.toString()
                    redirectCount++
                    return@use
                }

                return writeBody(res, destination, onProgress)
            }
        }
    }

    private fun buildRequest(url: String): Request {
        val builder = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", userAgent)
            .header("Accept", "*/*")

        val cookie = cookieProvider(url)
            ?.replace("\r", "")
            ?.replace("\n", "")
            ?.trim()
        if (!cookie.isNullOrEmpty()) {
            builder.header("Cookie", cookie)
        }
        return builder.build()
    }

    private fun writeBody(response: Response, destination: OutputStream, onProgress: (Long, Long?) -> Unit): DownloadedFile {
        if (!response.isSuccessful) {
            throw LetusDownloadException(MSG_HTTP_ERROR)
        }
        val body = response.body ?: throw LetusDownloadException(MSG_HTTP_ERROR)

        val contentLength = body.contentLength()
        if (contentLength > maxBytes) {
            throw LetusDownloadException(MSG_TOO_LARGE)
        }

        val expectedBytes = contentLength.takeIf { it > 0 }
        onProgress(0, expectedBytes)
        var lastPercent = -1
        var lastReportedBytes = 0L
        fun report(bytes: Long) {
            val percent = expectedBytes?.let { ((bytes * 100) / it).coerceIn(0, 99).toInt() }
            if ((percent != null && percent != lastPercent) || bytes - lastReportedBytes >= 256 * 1024) {
                onProgress(bytes, expectedBytes)
                lastPercent = percent ?: -1
                lastReportedBytes = bytes
            }
        }

        val contentType = body.contentType()?.toString()
        if (isHtmlContentType(contentType)) {
            throw LetusDownloadException(MSG_LOGIN_PAGE)
        }

        val fileName = suggestedFileName(response)
        val input = body.byteStream()
        val buffer = ByteArray(BUFFER_SIZE)

        try {
            // 先頭を少し読んでから HTML かどうかを判定し、判定前に書き込まない。
            var sniffed = 0
            while (sniffed < SNIFF_BYTES) {
                val read = input.read(buffer, sniffed, minOf(SNIFF_BYTES, buffer.size) - sniffed)
                if (read <= 0) break
                sniffed += read
            }
            if (sniffed > 0 && looksLikeHtml(buffer, sniffed)) {
                throw LetusDownloadException(MSG_LOGIN_PAGE)
            }

            var total = sniffed.toLong()
            if (total > maxBytes) {
                throw LetusDownloadException(MSG_TOO_LARGE)
            }
            if (sniffed > 0) {
                destination.write(buffer, 0, sniffed)
                report(total)
            }

            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                if (read == 0) continue
                total += read
                if (total > maxBytes) {
                    throw LetusDownloadException(MSG_TOO_LARGE)
                }
                destination.write(buffer, 0, read)
                report(total)
            }
            if (expectedBytes != null && total != expectedBytes) {
                throw LetusDownloadException(MSG_NETWORK)
            }
            destination.flush()
            onProgress(total, expectedBytes)

            return DownloadedFile(
                fileName = fileName,
                sizeBytes = total,
                contentType = contentType,
            )
        } catch (e: IOException) {
            throw LetusDownloadException(MSG_NETWORK, e)
        }
    }

    private fun suggestedFileName(response: Response): String {
        val fromHeader = fileNameFromContentDisposition(
            response.header("Content-Disposition"),
        )
        if (!fromHeader.isNullOrBlank()) {
            return normalizeFileName(fromHeader)
        }
        return fileNameFromUrl(response.request.url.toString())
    }

    /** 保存結果。 */
    data class DownloadedFile(
        val fileName: String,
        val sizeBytes: Long,
        val contentType: String?,
    )

    companion object {
        const val HOST = "letus.ed.tus.ac.jp"
        const val MAX_REDIRECTS = 5
        const val MAX_BYTES = 100L * 1024L * 1024L
        const val DEFAULT_FILE_NAME = "letus-download"

        private const val MAX_FILE_NAME_LENGTH = 120
        private const val MAX_EXTENSION_LENGTH = 16

        private const val BUFFER_SIZE = 16 * 1024
        private const val SNIFF_BYTES = 512

        private const val MSG_INVALID_URL = "このURLはダウンロードできません。"
        private const val MSG_INVALID_REDIRECT = "ダウンロード先の遷移が許可されていません。"
        private const val MSG_TOO_MANY_REDIRECTS = "ダウンロード先の遷移が多すぎます。"
        private const val MSG_LOGIN_PAGE = "ログインページが返されたため、ダウンロードを中止しました。"
        private const val MSG_TOO_LARGE = "ファイルサイズが上限（100MB）を超えています。"
        private const val MSG_HTTP_ERROR = "ファイルを取得できませんでした。"
        private const val MSG_NETWORK = "通信に失敗しました。ダウンロードできませんでした。"

        private val ILLEGAL_FILE_NAME_CHARS = charArrayOf(
            '\\', '/', ':', '*', '?', '"', '<', '>', '|',
        )

        /**
         * LETUS の HTTPS（標準 443・userInfo なし）だけを許可する。
         * ホストは完全一致で、サブドメインや類似ドメインは許可しない。
         */
        fun isAllowedUrl(raw: String?): Boolean {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty()) return false
            if (trimmed.any { it.isWhitespace() || it.isISOControl() }) return false
            if (trimmed.contains('\\')) return false

            val uri = runCatching { URI(trimmed) }.getOrNull() ?: return false
            if (!uri.scheme.equals("https", ignoreCase = true)) return false
            if (uri.userInfo != null) return false
            if (uri.port != -1 && uri.port != 443) return false
            val host = uri.host
                ?.trim()
                ?.trimEnd('.')
                ?.lowercase()
                ?: return false
            return host == HOST
        }

        /**
         * Content-Disposition からファイル名を取り出す（正規化はしない）。
         * `filename*`（RFC 5987）を優先し、無ければ `filename` を使う。
         */
        fun fileNameFromContentDisposition(header: String?): String? {
            if (header.isNullOrBlank()) return null
            var plain: String? = null

            header.split(';').drop(1).forEach { part ->
                val equals = part.indexOf('=')
                if (equals <= 0) return@forEach
                val key = part.substring(0, equals).trim().lowercase()
                val value = part.substring(equals + 1)
                    .trim()
                    .removeSurrounding("\"")

                when (key) {
                    "filename*" -> {
                        val encoded = value.substringAfter("''", value)
                        val decoded = runCatching {
                            URLDecoder.decode(encoded, Charsets.UTF_8.name())
                        }.getOrNull()
                        if (!decoded.isNullOrBlank()) return decoded
                    }
                    "filename" -> {
                        if (plain == null && value.isNotBlank()) plain = value
                    }
                }
            }
            return plain
        }

        /** URL の最終パスセグメントをファイル名として正規化する。 */
        fun fileNameFromUrl(raw: String?): String {
            val uri = runCatching {
                URI(raw?.trim().orEmpty())
            }.getOrNull()
            val path = uri?.path?.trimEnd('/').orEmpty()
            val last = path.substringAfterLast('/')
            return normalizeFileName(last)
        }

        /**
         * ファイル名を安全な形へ正規化する。
         * ディレクトリ部分を落とし、制御文字・禁止文字を `_` に置換し、
         * 先頭のドット・空白を除き、長すぎる名前は拡張子を残して切り詰める。
         */
        fun normalizeFileName(
            raw: String?,
            fallback: String = DEFAULT_FILE_NAME,
        ): String {
            sanitizeFileName(raw)?.let { return it }
            sanitizeFileName(fallback)?.let { return it }
            return DEFAULT_FILE_NAME
        }

        private fun sanitizeFileName(raw: String?): String? {
            val candidate = raw
                ?.trim()
                .orEmpty()
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .map { ch ->
                    if (ch.isISOControl() || ch in ILLEGAL_FILE_NAME_CHARS) '_' else ch
                }
                .joinToString("")
                .trim()
                .trim('.', ' ')
                .trim()

            if (candidate.isEmpty()) return null
            return truncateFileName(candidate)
        }

        private fun truncateFileName(name: String): String {
            if (name.length <= MAX_FILE_NAME_LENGTH) return name

            val dot = name.lastIndexOf('.')
            val extension = if (dot > 0 && name.length - dot <= MAX_EXTENSION_LENGTH) {
                name.substring(dot)
            } else {
                ""
            }
            val baseLimit = (MAX_FILE_NAME_LENGTH - extension.length).coerceAtLeast(1)
            val base = name
                .substring(0, name.length - extension.length)
                .take(baseLimit)
                .trimEnd('.', ' ')
            if (base.isEmpty()) return DEFAULT_FILE_NAME
            return base + extension
        }

        private fun isHtmlContentType(contentType: String?): Boolean {
            val mime = contentType
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase()
                ?: return false
            return mime == "text/html" || mime == "application/xhtml+xml"
        }

        private fun looksLikeHtml(bytes: ByteArray, length: Int): Boolean {
            if (length <= 0) return false
            var start = 0
            // UTF-8 BOM を読み飛ばす。
            if (length >= 3 &&
                bytes[0] == 0xEF.toByte() &&
                bytes[1] == 0xBB.toByte() &&
                bytes[2] == 0xBF.toByte()
            ) {
                start = 3
            }
            if (start >= length) return false
            val head = String(bytes, start, length - start, Charsets.ISO_8859_1)
                .trimStart()
                .lowercase()
            if (head.startsWith("<!doctype html")) return true
            if (head.startsWith("<html")) return true
            if (head.startsWith("<?xml") && head.contains("<html")) return true
            return false
        }

        private fun defaultCookieProvider(): (String) -> String? = { url ->
            runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
        }

        private fun createClient(): OkHttpClient = OkHttpClient.Builder()
            .cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

/** ダウンロード失敗を表す。メッセージは利用者向けの一般的な日本語のみ。 */
class LetusDownloadException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
