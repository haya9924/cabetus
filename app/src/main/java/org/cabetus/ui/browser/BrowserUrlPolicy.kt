package org.cabetus.ui.browser

import org.cabetus.data.classweb.ClassMobileAttendanceDomExtractor
import org.cabetus.data.classweb.ClassTimetableDomExtractor
import java.net.URI

/**
 * アプリ内ブラウザで開いてよい URL かどうかを判定する純ロジック。
 *
 * 認証 Cookie を共有する WebView 内では、任意のサイトへ遷移させないことが重要。
 * 大学のドメインと、SSO で実際に経由する Microsoft のログイン系ドメインだけを許可し、
 * それ以外はアプリ内では読み込まず外部ブラウザへ逃がす。
 */
object BrowserUrlPolicy {

    const val LETUS_URL = "https://letus.ed.tus.ac.jp/"
    const val CLASS_URL = "https://class.admin.tus.ac.jp/"

    /** LETUS の完全一致ホスト。サブドメイン・類似ホストは含めない。 */
    private const val LETUS_HOST = "letus.ed.tus.ac.jp"

    /**
     * 実機で確認した「学生時間割表」のページパス（JSF の内部 post action とは別）。
     *
     * 抽出スクリプトと同じリストを共有し、UI と抽出時の判定がずれないようにする。
     * 完全一致のみを許可し、前方一致・ディレクトリ単位では広げない。
     */
    val CLASS_TIMETABLE_PATHS: List<String> = ClassTimetableDomExtractor.TARGET_PATHS

    private const val CLASS_HOST = "class.admin.tus.ac.jp"

    /** 「アプリから最初に開く」ことを許可するホスト。 */
    private val INITIAL_HOSTS = setOf(
        "letus.ed.tus.ac.jp",
        "class.admin.tus.ac.jp",
    )

    /**
     * アプリ内で遷移を許可するホスト。
     * tus.ac.jp 配下は大学が管理する SSO / CLASS / LETUS を含む。
     * Microsoft 側はログイン画面・資産配信で使われるドメインに限定する。
     */
    private val TRUSTED_HOST_SUFFIXES = listOf(
        "tus.ac.jp",
        "microsoftonline.com",
        "microsoft.com",
        "live.com",
        "msftauth.net",
        "msauth.net",
    )

    /** 現在の URL をアプリ内 WebView で読み込んでよいか。 */
    fun isAllowedInBrowser(url: String?): Boolean {
        val uri = parseHttpsUri(url) ?: return false
        if (uri.userInfo != null) return false
        if (uri.port != -1 && uri.port != 443) return false
        val host = normalizedHost(uri) ?: return false
        return TRUSTED_HOST_SUFFIXES.any { host == it || host.endsWith(".$it") }
    }

    /** アプリから最初に開く URL として許可してよいか。 */
    fun isAllowedStart(url: String?): Boolean {
        val uri = parseHttpsUri(url) ?: return false
        if (uri.userInfo != null) return false
        if (uri.port != -1 && uri.port != 443) return false
        val host = normalizedHost(uri) ?: return false
        return host in INITIAL_HOSTS
    }

    /**
     * LETUS（`letus.ed.tus.ac.jp`）の完全一致 HTTPS ホストのページか。
     *
     * 課題の添付ファイル選択など、端末のファイルを WebView に渡す操作は
     * この判定を通ったページ（`https`・完全一致ホスト・標準ポート・userInfo 無し）
     * にだけ許可する。サブドメインや類似ホスト、CLASS・SSO のページは対象外。
     */
    fun isLetusPage(url: String?): Boolean {
        val uri = parseHttpsUri(url) ?: return false
        if (uri.userInfo != null) return false
        if (uri.port != -1 && uri.port != 443) return false
        return normalizedHost(uri) == LETUS_HOST
    }

    /**
     * CLASS の画面（`class.admin.tus.ac.jp` の完全一致ホスト）か。
     *
     * CLASS 向けの DOM 補正はこの判定を通ったページにだけ適用する。
     * サブドメイン・類似ホスト・非 HTTPS・非標準ポートは対象外。
     */
    fun isClassConsole(url: String?): Boolean {
        val uri = parseHttpsUri(url) ?: return false
        if (uri.userInfo != null) return false
        if (uri.port != -1 && uri.port != 443) return false
        val host = normalizedHost(uri) ?: return false
        return host == CLASS_HOST
    }

    /**
     * CLASS の学生時間割表ページそのものか。
     *
     * DOM 取り込みボタンを出す条件と、WebView 内の固定スクリプトが確認する条件を揃える。
     * `https`・完全一致ホスト・完全一致パス（[CLASS_TIMETABLE_PATHS] のいずれか）だけを対象とし、
     * 類似ホストや別画面は許可しない。
     */
    fun isClassTimetablePage(url: String?): Boolean {
        val uri = parseHttpsUri(url) ?: return false
        if (uri.userInfo != null) return false
        if (uri.port != -1 && uri.port != 443) return false
        if (normalizedHost(uri) != CLASS_HOST) return false
        return uri.path in CLASS_TIMETABLE_PATHS
    }

    /**
     * CLASS の「モバイル出席登録」のページか。
     *
     * 実機で確認できた完全一致のパス（[ClassMobileAttendanceDomExtractor.KNOWN_PATHS]）だけを対象にする。
     * パスは読み取りボタンを出す条件の 1 つにすぎず、実際の読み取り前には
     * DOM（見出し・授業一覧）の再検証も行う。前方一致・類似パスへは広げない。
     */
    fun isClassMobileAttendancePage(url: String?): Boolean {
        val uri = parseHttpsUri(url) ?: return false
        if (uri.userInfo != null) return false
        if (uri.port != -1 && uri.port != 443) return false
        if (normalizedHost(uri) != CLASS_HOST) return false
        return ClassMobileAttendanceDomExtractor.isKnownPagePath(uri.path)
    }

    /** 外部ブラウザに渡してよい URL か（http/https のみ）。 */
    fun isSafeExternalUrl(url: String?): Boolean {
        val uri = parseUri(url) ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "https" && scheme != "http") return false
        if (uri.host.isNullOrBlank()) return false
        if (uri.userInfo != null) return false
        return true
    }

    /** ツールバー表示用のホスト名。取得できなければ null。 */
    fun hostOf(url: String?): String? = parseUri(url)?.let { normalizedHost(it) }

    private fun parseHttpsUri(url: String?): URI? {
        val uri = parseUri(url) ?: return null
        return if (uri.scheme?.equals("https", ignoreCase = true) == true) uri else null
    }

    private fun parseUri(url: String?): URI? {
        val trimmed = url?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return runCatching { URI(trimmed) }.getOrNull()
    }

    private fun normalizedHost(uri: URI): String? =
        uri.host?.trim()?.trimEnd('.')?.lowercase()?.takeIf { it.isNotEmpty() }
}
