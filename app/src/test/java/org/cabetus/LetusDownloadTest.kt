package org.cabetus

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.cabetus.ui.browser.LetusDownload
import org.cabetus.ui.browser.LetusDownloadException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream

class LetusDownloadTest {

    @Test
    fun reportsProgressWithKnownTotal() {
        val bytes = ByteArray(40_000) { 42 }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            response(chain.request(), 200, bytes, "application/octet-stream", null)
        }.build()
        val updates = mutableListOf<Pair<Long, Long?>>()
        val output = ByteArrayOutputStream()
        LetusDownload("test", { null }, client).download("https://letus.ed.tus.ac.jp/file", output) { read, total ->
            updates += read to total
        }
        assertEquals(0L to bytes.size.toLong(), updates.first())
        assertEquals(bytes.size.toLong() to bytes.size.toLong(), updates.last())
        assertTrue(updates.zipWithNext().all { (a, b) -> a.first <= b.first })
        assertEquals(bytes.size, output.size())
    }

    // --- isAllowedUrl ---

    @Test
    fun allowsLetusHttpsUrls() {
        assertTrue(LetusDownload.isAllowedUrl("https://letus.ed.tus.ac.jp/"))
        assertTrue(
            LetusDownload.isAllowedUrl(
                "https://letus.ed.tus.ac.jp/pluginfile.php/1/mod_resource/content/0/a.pdf",
            ),
        )
        assertTrue(LetusDownload.isAllowedUrl("https://letus.ed.tus.ac.jp:443/my/"))
        assertTrue(LetusDownload.isAllowedUrl("HTTPS://LETUS.ED.TUS.AC.JP/my/"))
        assertTrue(LetusDownload.isAllowedUrl("https://letus.ed.tus.ac.jp./my/"))
    }

    @Test
    fun rejectsNonLetusOrNonHttpsUrls() {
        assertFalse(LetusDownload.isAllowedUrl("http://letus.ed.tus.ac.jp/"))
        assertFalse(LetusDownload.isAllowedUrl("https://example.com/"))
        assertFalse(LetusDownload.isAllowedUrl("https://sub.letus.ed.tus.ac.jp/"))
        assertFalse(LetusDownload.isAllowedUrl("https://letus.ed.tus.ac.jp.evil.example/"))
        assertFalse(LetusDownload.isAllowedUrl("https://evil-letus.ed.tus.ac.jp/"))
        assertFalse(LetusDownload.isAllowedUrl("https://letus.ed.tus.ac.jp:8443/"))
        assertFalse(LetusDownload.isAllowedUrl("https://user:pass@letus.ed.tus.ac.jp/"))
        assertFalse(LetusDownload.isAllowedUrl("javascript:alert(1)"))
        assertFalse(LetusDownload.isAllowedUrl("file:///etc/passwd"))
        assertFalse(LetusDownload.isAllowedUrl(""))
        assertFalse(LetusDownload.isAllowedUrl("   "))
        assertFalse(LetusDownload.isAllowedUrl(null))
        assertFalse(LetusDownload.isAllowedUrl("https://letus.ed.tus.ac.jp/a b.pdf"))
        assertFalse(LetusDownload.isAllowedUrl("https://letus.ed.tus.ac.jp\\@evil.example/"))
    }

    // --- normalizeFileName ---

    @Test
    fun normalizeFileNameRemovesPathsAndIllegalChars() {
        assertEquals("passwd", LetusDownload.normalizeFileName("../../etc/passwd"))
        assertEquals("report.pdf", LetusDownload.normalizeFileName("C:\\temp\\report.pdf"))
        assertEquals("a_b_c_.pdf", LetusDownload.normalizeFileName("a:b*c?.pdf"))
        assertEquals("tab_name.pdf", LetusDownload.normalizeFileName("tab\u0000name.pdf"))
    }

    @Test
    fun normalizeFileNameTrimsDotsAndSpaces() {
        assertEquals("name.pdf", LetusDownload.normalizeFileName("  name.pdf  "))
        assertEquals("hidden", LetusDownload.normalizeFileName("...hidden..."))
        assertEquals("a.pdf", LetusDownload.normalizeFileName(".a.pdf"))
    }

    @Test
    fun normalizeFileNameFallsBackWhenEmpty() {
        assertEquals(LetusDownload.DEFAULT_FILE_NAME, LetusDownload.normalizeFileName(null))
        assertEquals(LetusDownload.DEFAULT_FILE_NAME, LetusDownload.normalizeFileName(""))
        assertEquals(LetusDownload.DEFAULT_FILE_NAME, LetusDownload.normalizeFileName("////"))
        assertEquals("fallback.bin", LetusDownload.normalizeFileName("/", "fallback.bin"))
    }

    @Test
    fun normalizeFileNameKeepsJapaneseAndTruncatesLongNames() {
        assertEquals("第1回レポート.pdf", LetusDownload.normalizeFileName("第1回レポート.pdf"))

        val longName = "あ".repeat(300) + ".pdf"
        val normalized = LetusDownload.normalizeFileName(longName)
        assertTrue(normalized.length <= 120)
        assertTrue(normalized.endsWith(".pdf"))
    }

    // --- fileNameFromUrl / fileNameFromContentDisposition ---

    @Test
    fun fileNameFromUrlUsesLastPathSegment() {
        assertEquals(
            "lecture1.pdf",
            LetusDownload.fileNameFromUrl("https://letus.ed.tus.ac.jp/files/lecture1.pdf?forcedownload=1"),
        )
        assertEquals(
            LetusDownload.DEFAULT_FILE_NAME,
            LetusDownload.fileNameFromUrl("https://letus.ed.tus.ac.jp/"),
        )
        assertEquals(
            LetusDownload.DEFAULT_FILE_NAME,
            LetusDownload.fileNameFromUrl(null),
        )
    }

    @Test
    fun fileNameFromContentDispositionHandlesPlainAndEncoded() {
        assertEquals(
            "report.pdf",
            LetusDownload.fileNameFromContentDisposition("attachment; filename=\"report.pdf\""),
        )
        assertEquals(
            "report.pdf",
            LetusDownload.fileNameFromContentDisposition("attachment; filename=report.pdf"),
        )
        assertEquals(
            "第1回 レポート.pdf",
            LetusDownload.fileNameFromContentDisposition(
                "attachment; filename*=UTF-8''%E7%AC%AC1%E5%9B%9E%20%E3%83%AC%E3%83%9D%E3%83%BC%E3%83%88.pdf",
            ),
        )
        // filename* が優先される。
        assertEquals(
            "encoded.pdf",
            LetusDownload.fileNameFromContentDisposition(
                "attachment; filename=\"plain.pdf\"; filename*=UTF-8''encoded.pdf",
            ),
        )
        assertNull(LetusDownload.fileNameFromContentDisposition(null))
        assertNull(LetusDownload.fileNameFromContentDisposition("attachment"))
    }

    // --- download ---

    @Test
    fun downloadWritesBodyAndSendsCookieFromProvider() {
        val requestedUrls = mutableListOf<String>()
        val cookies = mutableListOf<String?>()
        val body = "PDFDATA".toByteArray()

        val download = LetusDownload(
            userAgent = "TestAgent/1.0",
            cookieProvider = { url -> "MoodleSession=abc; other=1".also { requestedUrls += url } },
            client = fakeClient { request ->
                cookies += request.header("Cookie")
                assertEquals("TestAgent/1.0", request.header("User-Agent"))
                response(request, 200, body, "application/pdf", "attachment; filename=\"lecture.pdf\"")
            },
        )

        val out = ByteArrayOutputStream()
        val result = download.download("https://letus.ed.tus.ac.jp/files/1", out)

        assertEquals("PDFDATA", out.toString(Charsets.UTF_8.name()))
        assertEquals("lecture.pdf", result.fileName)
        assertEquals(body.size.toLong(), result.sizeBytes)
        assertEquals(listOf("MoodleSession=abc; other=1"), cookies)
        assertEquals(listOf("https://letus.ed.tus.ac.jp/files/1"), requestedUrls)
    }

    @Test
    fun downloadOmitsCookieHeaderWhenProviderReturnsNull() {
        var cookieSeen = "unset"
        val download = LetusDownload(
            userAgent = "TestAgent/1.0",
            cookieProvider = { null },
            client = fakeClient { request ->
                cookieSeen = request.header("Cookie").orEmpty()
                response(request, 200, "x".toByteArray(), "application/octet-stream", null)
            },
        )

        download.download("https://letus.ed.tus.ac.jp/files/1", ByteArrayOutputStream())
        assertEquals("", cookieSeen)
    }

    @Test
    fun downloadFollowsRedirectsWithCookieOnEachHop() {
        val cookieUrls = mutableListOf<String>()
        val cookieProvider = { url: String -> cookieUrls += url; "SID=1" }
        var hop = 0

        val download = LetusDownload(
            userAgent = "TestAgent/1.0",
            cookieProvider = cookieProvider,
            client = fakeClient { request ->
                hop++
                if (hop == 1) {
                    response(request, 302, ByteArray(0), null, null, "https://letus.ed.tus.ac.jp/files/real.pdf")
                } else {
                    response(request, 200, "ok".toByteArray(), "application/pdf", null)
                }
            },
        )

        val out = ByteArrayOutputStream()
        val result = download.download("https://letus.ed.tus.ac.jp/files/start", out)

        assertEquals("ok", out.toString(Charsets.UTF_8.name()))
        assertEquals("real.pdf", result.fileName)
        assertEquals(
            listOf(
                "https://letus.ed.tus.ac.jp/files/start",
                "https://letus.ed.tus.ac.jp/files/real.pdf",
            ),
            cookieUrls,
        )
    }

    @Test
    fun downloadRejectsRedirectToOtherHost() {
        val download = LetusDownload(
            userAgent = "TestAgent/1.0",
            cookieProvider = { "SID=1" },
            client = fakeClient { request ->
                response(request, 302, ByteArray(0), null, null, "https://example.com/steal")
            },
        )

        assertFailsWithoutLeakingUrl {
            download.download("https://letus.ed.tus.ac.jp/files/start", ByteArrayOutputStream())
        }
    }

    @Test
    fun downloadStopsAfterMaxRedirects() {
        var hops = 0
        val download = LetusDownload(
            userAgent = "TestAgent/1.0",
            cookieProvider = { "SID=1" },
            client = fakeClient { request ->
                hops++
                response(request, 302, ByteArray(0), null, null, "https://letus.ed.tus.ac.jp/files/loop")
            },
        )

        try {
            download.download("https://letus.ed.tus.ac.jp/files/loop", ByteArrayOutputStream())
            fail("expected LetusDownloadException")
        } catch (e: LetusDownloadException) {
            assertTrue(e.message!!.contains("遷移"))
        }
        // 初回 + 最大 5 回の追従で、6 回目の応答で打ち切る。
        assertEquals(LetusDownload.MAX_REDIRECTS + 1, hops)
    }

    @Test
    fun downloadRejectsHtmlContentTypeWithoutWriting() {
        val download = LetusDownload(
            userAgent = "TestAgent/1.0",
            cookieProvider = { "SID=1" },
            client = fakeClient { request ->
                response(
                    request,
                    200,
                    "<!DOCTYPE html><html><body>login</body></html>".toByteArray(),
                    "text/html; charset=utf-8",
                    null,
                )
            },
        )

        val out = ByteArrayOutputStream()
        try {
            download.download("https://letus.ed.tus.ac.jp/file.pdf", out)
            fail("expected LetusDownloadException")
        } catch (e: LetusDownloadException) {
            assertTrue(e.message!!.contains("ログイン"))
        }
        assertEquals(0, out.size())
    }

    @Test
    fun downloadRejectsHtmlBodyEvenWithBinaryContentType() {
        val download = LetusDownload(
            userAgent = "TestAgent/1.0",
            cookieProvider = { "SID=1" },
            client = fakeClient { request ->
                response(
                    request,
                    200,
                    "<html><head><title>Login</title></head></html>".toByteArray(),
                    "application/octet-stream",
                    null,
                )
            },
        )

        val out = ByteArrayOutputStream()
        try {
            download.download("https://letus.ed.tus.ac.jp/file.pdf", out)
            fail("expected LetusDownloadException")
        } catch (e: LetusDownloadException) {
            assertTrue(e.message!!.contains("ログイン"))
        }
        assertEquals(0, out.size())
    }

    @Test
    fun downloadRejectsTooLargeContentLength() {
        val download = LetusDownload(
            userAgent = "TestAgent/1.0",
            cookieProvider = { "SID=1" },
            client = fakeClient { request ->
                response(request, 200, "0123456789".toByteArray(), "application/pdf", null)
            },
            maxBytes = 4,
        )

        try {
            download.download("https://letus.ed.tus.ac.jp/big.pdf", ByteArrayOutputStream())
            fail("expected LetusDownloadException")
        } catch (e: LetusDownloadException) {
            assertTrue(e.message!!.contains("100MB"))
        }
    }

    @Test
    fun downloadRejectsTooLargeStreamWhenLengthUnknown() {
        // Content-Length が無い（chunked）場合でもストリーム中に上限で打ち切る。
        val download = LetusDownload(
            userAgent = "TestAgent/1.0",
            cookieProvider = { "SID=1" },
            client = fakeClient { request ->
                response(request, 200, "x".repeat(4096).toByteArray(), "application/pdf", null)
            },
            maxBytes = 1024,
        )

        val out = ByteArrayOutputStream()
        try {
            download.download("https://letus.ed.tus.ac.jp/big.pdf", out)
            fail("expected LetusDownloadException")
        } catch (e: LetusDownloadException) {
            assertTrue(e.message!!.contains("100MB"))
        }
        assertTrue(out.size() <= 1024)
    }

    @Test
    fun downloadRejectsErrorResponses() {
        val download = LetusDownload(
            userAgent = "TestAgent/1.0",
            cookieProvider = { "SID=1" },
            client = fakeClient { request ->
                response(request, 403, ByteArray(0), "text/plain", null)
            },
        )

        try {
            download.download("https://letus.ed.tus.ac.jp/file.pdf", ByteArrayOutputStream())
            fail("expected LetusDownloadException")
        } catch (e: LetusDownloadException) {
            assertTrue(e.message!!.contains("取得できません"))
        }
    }

    @Test
    fun downloadRejectsDisallowedStartUrlBeforeRequest() {
        var calls = 0
        val download = LetusDownload(
            userAgent = "TestAgent/1.0",
            cookieProvider = { "SID=1" },
            client = fakeClient { request ->
                calls++
                response(request, 200, "x".toByteArray(), "application/pdf", null)
            },
        )

        try {
            download.download("https://example.com/file.pdf", ByteArrayOutputStream())
            fail("expected LetusDownloadException")
        } catch (e: LetusDownloadException) {
            assertFalse(e.message.orEmpty().contains("example.com"))
        }
        assertEquals(0, calls)
    }

    // --- helpers ---

    private fun assertFailsWithoutLeakingUrl(block: () -> Unit) {
        try {
            block()
            fail("expected LetusDownloadException")
        } catch (e: LetusDownloadException) {
            val message = e.message.orEmpty()
            assertTrue(message.isNotEmpty())
            assertFalse(message.contains("http"))
            assertFalse(message.contains("letus"))
            assertFalse(message.contains("SID=1"))
        }
    }

    private fun fakeClient(
        handler: (okhttp3.Request) -> Response,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain -> handler(chain.request()) }
        .build()

    private fun response(
        request: okhttp3.Request,
        code: Int,
        body: ByteArray,
        contentType: String?,
        contentDisposition: String?,
        location: String? = null,
    ): Response {
        val builder = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("stub")
            .body(body.toResponseBody(contentType?.toMediaType()))
        if (contentDisposition != null) {
            builder.header("Content-Disposition", contentDisposition)
        }
        if (location != null) {
            builder.header("Location", location)
        }
        return builder.build()
    }
}
