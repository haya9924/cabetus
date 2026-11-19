package org.cabetus

import org.cabetus.ui.browser.BrowserUrlPolicy
import org.cabetus.ui.browser.LetusFileChooser
import org.cabetus.ui.browser.LetusFileChooserRequest
import org.cabetus.ui.browser.LetusFileChooserSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LETUS のファイル選択（`onShowFileChooser`）まわりの純ロジック。
 * WebView / SAF 自体は実機でしか動かせないため、判定と callback 解放だけを検証する。
 *
 * ここでは Android のスタブ（Uri / ValueCallback / FileChooserParams 等）を一切呼ばない。
 * `LetusFileChooserRequest` は `(List<String>?) -> Unit` を受け取る経路で生成し、
 * `acceptTypes` も生の文字列配列（`acceptTypesOf`）から組み立てる。
 */
class LetusFileChooserTest {

    // --- BrowserUrlPolicy.isLetusPage ---

    @Test
    fun letusPage_allowsExactHttpsHostOnly() {
        assertTrue(BrowserUrlPolicy.isLetusPage("https://letus.ed.tus.ac.jp/"))
        assertTrue(
            BrowserUrlPolicy.isLetusPage(
                "https://letus.ed.tus.ac.jp/mod/assign/view.php?id=1&action=submit",
            ),
        )
        assertTrue(BrowserUrlPolicy.isLetusPage("https://letus.ed.tus.ac.jp:443/my/"))
    }

    @Test
    fun letusPage_rejectsOtherHostsAndSchemes() {
        assertFalse(BrowserUrlPolicy.isLetusPage("https://sub.letus.ed.tus.ac.jp/"))
        assertFalse(BrowserUrlPolicy.isLetusPage("https://letus.ed.tus.ac.jp.evil.example/"))
        assertFalse(BrowserUrlPolicy.isLetusPage("https://evilletus.ed.tus.ac.jp/"))
        assertFalse(BrowserUrlPolicy.isLetusPage("http://letus.ed.tus.ac.jp/"))
        assertFalse(BrowserUrlPolicy.isLetusPage("https://class.admin.tus.ac.jp/"))
        assertFalse(BrowserUrlPolicy.isLetusPage("https://login.microsoftonline.com/"))
        assertFalse(BrowserUrlPolicy.isLetusPage("https://letus.ed.tus.ac.jp:8443/"))
        assertFalse(BrowserUrlPolicy.isLetusPage("https://user:pass@letus.ed.tus.ac.jp/"))
        assertFalse(BrowserUrlPolicy.isLetusPage("javascript:alert(1)"))
        assertFalse(BrowserUrlPolicy.isLetusPage(""))
        assertFalse(BrowserUrlPolicy.isLetusPage(null))
    }

    // --- LetusFileChooser.acceptTypes / acceptTypesOf ---

    @Test
    fun acceptTypes_usesMimeOnlyAndFallsBackToAny() {
        // params が null のときは */*（FileChooserParams のスタブは呼ばない）。
        assertEquals(listOf("*/*"), LetusFileChooser.acceptTypes(null).toList())
        assertEquals(
            listOf("application/pdf", "image/png"),
            LetusFileChooser.acceptTypesOf(
                arrayOf(".pdf,.png", "application/pdf", "image/png"),
            ).toList(),
        )
        // 拡張子だけ・不正な値だけの場合は SAF に使わせず */* に落とす。
        assertEquals(
            listOf("*/*"),
            LetusFileChooser.acceptTypesOf(arrayOf(".pdf", "not-a-mime")).toList(),
        )
        // パラメータ付き（image/*;capture=camera 等）は MIME 部分だけを使う。
        assertEquals(
            listOf("image/*"),
            LetusFileChooser.acceptTypesOf(arrayOf("image/*;capture=camera")).toList(),
        )
    }

    // --- LetusFileChooser.matchesAccept / filterPickedUris ---

    @Test
    fun matchesAccept_handlesWildcardsAndUnknownMime() {
        assertTrue(LetusFileChooser.matchesAccept("application/pdf", listOf("application/pdf")))
        assertTrue(LetusFileChooser.matchesAccept("image/png", listOf("image/*")))
        assertFalse(LetusFileChooser.matchesAccept("video/mp4", listOf("image/*")))
        assertTrue(LetusFileChooser.matchesAccept("anything", listOf("*/*")))
        assertTrue(LetusFileChooser.matchesAccept(null, listOf("application/pdf")))
        assertTrue(LetusFileChooser.matchesAccept("application/pdf", emptyList()))
    }

    @Test
    fun filterPickedUris_keepsOnlyContentUrisWithAcceptedMime() {
        val pdf = "content://com.example.docs/1"
        val exe = "content://com.example.docs/2"
        val file = "file:///sdcard/secret.pdf"
        val remote = "https://example.com/a.pdf"
        val types = mapOf(
            pdf to "application/pdf",
            exe to "application/x-msdownload",
        )

        val result = LetusFileChooser.filterPickedUris(
            uris = listOf(file, remote, pdf, exe),
            acceptTypes = listOf("application/pdf"),
            multiple = true,
            resolveMimeType = { types[it] },
        )

        assertEquals(listOf(pdf), result)
    }

    @Test
    fun filterPickedUris_singleModeReturnsAtMostOne() {
        val first = "content://com.example.docs/1"
        val second = "content://com.example.docs/2"

        val result = LetusFileChooser.filterPickedUris(
            uris = listOf(first, second),
            acceptTypes = listOf("*/*"),
            multiple = false,
            resolveMimeType = { "application/pdf" },
        )

        assertEquals(listOf(first), result)
    }

    @Test
    fun filterPickedUris_returnsEmptyWhenNothingUsable() {
        assertTrue(
            LetusFileChooser.filterPickedUris(
                uris = emptyList(),
                acceptTypes = listOf("*/*"),
                multiple = true,
                resolveMimeType = { "application/pdf" },
            ).isEmpty(),
        )
        assertTrue(
            LetusFileChooser.filterPickedUris(
                uris = null,
                acceptTypes = listOf("*/*"),
                multiple = true,
                resolveMimeType = { "application/pdf" },
            ).isEmpty(),
        )
        assertTrue(
            LetusFileChooser.filterPickedUris(
                uris = listOf("file:///sdcard/a.pdf"),
                acceptTypes = listOf("*/*"),
                multiple = true,
                resolveMimeType = { "application/pdf" },
            ).isEmpty(),
        )
    }

    // --- callback の解放 ---

    @Test
    fun request_releasesCallbackExactlyOnce() {
        val callback = RecordingCallback()
        val request = request(callback)

        request.complete(listOf("content://com.example.docs/1"))
        request.cancel()
        request.complete(emptyList())

        assertEquals(1, callback.values.size)
        assertEquals(listOf("content://com.example.docs/1"), callback.values.single())
    }

    @Test
    fun request_cancelReleasesCallbackWithNull() {
        val callback = RecordingCallback()

        request(callback).cancel()

        assertEquals(1, callback.values.size)
        assertNull(callback.values.first())
    }

    @Test
    fun request_rejectsNonContentUriAndReleasesWithNull() {
        val callback = RecordingCallback()

        request(callback).complete(listOf("file:///sdcard/a.pdf", "https://example.com/a.pdf"))

        assertNull(callback.values.single())
    }

    @Test
    fun slot_releasesPreviousRequestWhenNewOneStarts() {
        val first = RecordingCallback()
        val second = RecordingCallback()
        val slot = LetusFileChooserSlot()

        slot.start(request(first))
        slot.start(request(second))
        slot.complete(listOf("content://com.example.docs/2"))

        assertEquals(1, first.values.size)
        assertNull(first.values.single())
        assertEquals(1, second.values.size)
        assertTrue(!second.values.single().isNullOrEmpty())
    }

    @Test
    fun slot_completeIsSafeWhenIdle() {
        val slot = LetusFileChooserSlot()
        // 例外を投げないことだけを確認する（対応する要求が無い状態の完了）。
        slot.complete(emptyList())
        assertNull(null)
    }

    @Test
    fun slot_releaseIsSafeWhenIdle() {
        val slot = LetusFileChooserSlot()
        slot.release()
        slot.release()
        slot.complete(emptyList())
        // 例外を投げないことだけを確認する（二重解放でクラッシュしない）。
        assertNull(null)
    }

    @Test
    fun slot_releaseReleasesPendingRequest() {
        val callback = RecordingCallback()
        val slot = LetusFileChooserSlot()

        slot.start(request(callback))
        slot.release()

        assertEquals(1, callback.values.size)
        assertNull(callback.values.single())
    }

    private fun request(onComplete: (List<String>?) -> Unit) = LetusFileChooserRequest(
        onComplete = onComplete,
        acceptTypes = arrayOf("application/pdf"),
        multiple = true,
        resolveMimeType = { "application/pdf" },
    )

    /** Android の ValueCallback を実装せず、callback の解放回数だけを記録する。 */
    private class RecordingCallback : (List<String>?) -> Unit {
        val values = mutableListOf<List<String>?>()

        override fun invoke(value: List<String>?) {
            values.add(value)
        }
    }
}
