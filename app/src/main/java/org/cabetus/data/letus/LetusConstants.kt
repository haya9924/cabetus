package org.cabetus.data.letus

/**
 * LETUS（Moodle）関連の定数。変更に強いよう1箇所に集約する。
 * 参考: github.com/waiteu-git/lms-task-watcher
 */
object LetusConstants {
    const val HOST = "letus.ed.tus.ac.jp"
    const val BASE = "https://letus.ed.tus.ac.jp"
    const val MY_URL = "$BASE/my/"
    const val COURSES_URL = "$BASE/my/courses.php"
    const val LOGIN_URL = "$BASE/login/index.php"

    /** Moodle AJAX API（ログイン中ページの M.cfg から取った sesskey が必要）。 */
    const val AJAX_SERVICE_URL = "$BASE/lib/ajax/service.php"

    /** コース発見のフォールバックに用いるサーバレンダリングのインデックスページ群。 */
    val COURSE_INDEX_URLS = listOf(MY_URL, COURSES_URL)

    /** コースページのURL断片。 */
    const val COURSE_VIEW = "/course/view.php"

    /**
     * 未ログイン時に到達する認証系パス（小文字で比較）。
     * 実測（Firefoxログ）: 未ログインで保護ページへアクセスすると
     * 303 → /auth/shibboleth/index.php → idp.admin.tus.ac.jp（SAML）
     * → login.microsoftonline.com と遷移する。/login/ は経由しない。
     */
    val AUTH_PATH_MARKERS = listOf("/login/", "/auth/shibboleth", "/shibboleth.sso")

    /** 未ログインを示す本文。 */
    val NOT_LOGGED_IN_MARKERS = listOf(
        "あなたはログインしていません",
        "You are not logged in",
    )

    /** 課題とみなすモジュールのパス（strict）。 */
    val STRICT_MODULE_PATHS = listOf(
        "/mod/assign/view.php",
        "/mod/quiz/view.php",
        "/mod/turnitintool/view.php",
        "/mod/turnitintooltwo/view.php",
    )

    /** standard に追加するモジュールパス。 */
    val STANDARD_EXTRA_PATHS = listOf(
        "/mod/workshop/view.php",
        "/mod/feedback/view.php",
        "/mod/choice/view.php",
        "/mod/questionnaire/view.php",
        "/mod/lti/view.php",
    )

    /** broad にさらに追加するモジュールパス。 */
    val BROAD_EXTRA_PATHS = listOf(
        "/mod/forum/view.php",
        "/mod/survey/view.php",
        "/mod/lesson/view.php",
    )

    /** 明らかに課題でないURL断片（除外）。 */
    val EXCLUDED_PATHS = listOf(
        "/grade/", "/grade/report/", "/reportbuilder/", "/user/", "/calendar/",
        "/message/", "/blog/", "/badges/", "/competency/", "/course/report/",
        "/course/view.php", "/mod/resource/", "/mod/folder/", "/mod/page/",
        "/mod/url/", "/mod/book/", "/mod/label/", "/mod/glossary/", "/mod/wiki/",
    )

    /** broad モードで課題らしさを判定するキーワード。 */
    val ASSIGNMENT_KEYWORDS = listOf(
        "課題", "提出", "レポート", "小テスト", "確認テスト", "テスト",
        "アンケート", "回答", "投稿",
        "assignment", "assign", "report", "quiz", "test",
        "questionnaire", "feedback", "workshop", "turnitin",
    )

    val DEADLINE_KEYWORDS = listOf(
        "提出期限", "提出締切", "締切日時", "締切", "期限", "終了予定", "終了済み", "終了日時",
        "利用終了日時", "受験終了", "回答終了",
        "Due date", "Closing date", "Close date", "Closes", "Due", "Close",
    )
}

enum class ScanLevel { STRICT, STANDARD, BROAD }
