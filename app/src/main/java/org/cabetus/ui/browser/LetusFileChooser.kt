package org.cabetus.ui.browser

import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient

/**
 * LETUS の課題提出など `<input type="file">` のファイル選択を SAF に委譲するためのロジック。
 *
 * - 端末のファイル一覧・ストレージ権限を WebView に渡さない。ユーザーが SAF で明示的に
 *   選んだ `content://` URI だけを WebView へ返す（`file://` / `http(s)://` は受け付けない）。
 * - 返す URI はページが `accept` で指定した MIME に一致するものだけに絞る。
 * - カメラ・マイク等の実行時権限は要求しない（`WebChromeClient.onPermissionRequest` は拒否）。
 * - WebView の `allowContentAccess` は false のままでよい。ここで扱うのは「アプリが
 *   SAF から受け取った URI を ContentResolver 経由で扱う」経路であり、WebView 自身に
 *   content:// を読み込ませる（`allowContentAccess` が許す）経路ではない。
 *
 * 判定は URI を文字列のまま扱う（単体テストで Android のスタブを呼ばないため）。
 */
internal object LetusFileChooser {

    /** ページが MIME を指定しない場合の accept。 */
    const val ANY_MIME = "*/*"

    /** `type/subtype` 形式（`*` 可）だけを MIME として受け付ける。 */
    private val MIME_PATTERN = Regex("^[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+*-]+$")

    /** ページの `accept`（[WebChromeClient.FileChooserParams.getAcceptTypes]）から SAF 用 MIME を作る。 */
    fun acceptTypes(params: WebChromeClient.FileChooserParams?): Array<String> =
        acceptTypesOf(params?.acceptTypes)

    /**
     * `accept` の生の値から SAF に渡す MIME 配列を作る。
     *
     * 拡張子（`.pdf` 等）や不正な値は SAF の MIME 指定としては使えないため捨て、
     * 有効な MIME が 1 つも残らなければ [ANY_MIME] に落とす。
     * ここで絞った MIME は SAF 側の選択制限と、選択後の [filterPickedUris] の両方で使う。
     */
    fun acceptTypesOf(rawAcceptTypes: Array<String>?): Array<String> {
        val accepted = rawAcceptTypes
            .orEmpty()
            .map { it.substringBefore(';').trim() }
            .filter { MIME_PATTERN.matches(it) }
            .distinct()
            .ifEmpty { listOf(ANY_MIME) }
        return accepted.toTypedArray()
    }

    /**
     * SAF の選択結果を WebView に返せる形へ絞り込む。
     *
     * - `content` スキーム以外は捨てる。
     * - MIME を判別できた場合は accept に一致するものだけ残す。
     *   MIME を判別できない URI は、SAF 側で既に MIME 制限をかけた結果であるため残す
     *   （端末のプロバイダによっては getType が null を返すため）。
     * - [multiple] が false（`MODE_OPEN_SINGLE` 等）なら 1 件だけ返す。
     * - 残らなければ空リスト（＝キャンセルとして callback を解放する）。
     */
    fun filterPickedUris(
        uris: List<String>?,
        acceptTypes: List<String>,
        multiple: Boolean,
        resolveMimeType: (String) -> String?,
    ): List<String> {
        val accepted = uris.orEmpty()
            .filter { it.trim().startsWith(CONTENT_SCHEME, ignoreCase = true) }
            .filter { matchesAccept(resolveMimeType(it), acceptTypes) }
        return if (multiple) accepted else accepted.take(1)
    }

    /** MIME が accept のいずれかに一致するか。MIME 不明（null）は許容する。 */
    fun matchesAccept(mimeType: String?, acceptTypes: List<String>): Boolean {
        if (acceptTypes.isEmpty()) return true
        val mime = mimeType?.substringBefore(';')?.trim()?.lowercase()
        if (mime.isNullOrEmpty()) return true
        return acceptTypes.any { accept ->
            val normalized = accept.substringBefore(';').trim().lowercase()
            when {
                normalized == ANY_MIME -> true
                normalized == mime -> true
                normalized.endsWith("/*") -> mime.startsWith(normalized.dropLast(1))
                else -> false
            }
        }
    }

    private const val CONTENT_SCHEME = "content:"
}

/**
 * 進行中のファイル選択要求。[complete] / [cancel] は必ず 1 回だけ callback を解放する。
 *
 * WebView は `onShowFileChooser` で受け取った callback に一度も値を返さないと、
 * その `<input type="file">` が「選択中」のまま固まり、以降の選択要求が届かなくなる。
 * キャンセル・画面離脱・WebView 破棄のいずれでも必ず null を返すためにここで管理する。
 */
internal class LetusFileChooserRequest internal constructor(
    private val acceptTypes: Array<String>,
    private val multiple: Boolean,
    private val resolveMimeType: (String) -> String?,
    private val onComplete: (List<String>?) -> Unit,
) {

    /** WebView の callback に `content://` URI 配列として返す（実機・本番経路）。 */
    constructor(
        callback: ValueCallback<Array<Uri>>,
        acceptTypes: Array<String>,
        multiple: Boolean,
        resolveMimeType: (String) -> String?,
    ) : this(
        acceptTypes = acceptTypes,
        multiple = multiple,
        resolveMimeType = resolveMimeType,
        onComplete = { accepted: List<String>? ->
            callback.onReceiveValue(
                accepted
                    ?.map { kotlin.runCatching { Uri.parse(it) }.getOrNull() }
                    ?.filterNotNull()
                    ?.takeIf { it.isNotEmpty() }
                    ?.toTypedArray(),
            )
        },
    )

    private var completed = false

    /** SAF の結果を返す。空・不許可は null（キャンセル）として返す。 */
    fun complete(uris: List<String>?) {
        if (completed) return
        completed = true
        val accepted = LetusFileChooser.filterPickedUris(
            uris = uris,
            acceptTypes = acceptTypes.toList(),
            multiple = multiple,
            resolveMimeType = resolveMimeType,
        )
        onComplete(accepted.takeIf { it.isNotEmpty() })
    }

    /** キャンセル・離脱時。callback を null で解放する。 */
    fun cancel() = complete(null)
}

/**
 * 未完了のファイル選択要求を 1 件だけ保持するスロット。
 *
 * `WebChromeClient.onShowFileChooser`、ActivityResult の結果、Composable の破棄は
 * すべてメインスレッドで起きるため同期化はしない。
 */
internal class LetusFileChooserSlot {

    private var pending: LetusFileChooserRequest? = null

    /** 新しい要求を開始する。前の要求が残っていれば必ず解放してから置き換える。 */
    fun start(request: LetusFileChooserRequest) {
        pending?.cancel()
        pending = request
    }

    /** ActivityResult の結果で要求を完了する。 */
    fun complete(uris: List<String>?) {
        val request = pending
        pending = null
        request?.complete(uris)
    }

    /** 画面離脱・WebView 破棄・ページ遷移時に未完了の要求を解放する。 */
    fun release() {
        val request = pending
        pending = null
        request?.cancel()
    }
}
