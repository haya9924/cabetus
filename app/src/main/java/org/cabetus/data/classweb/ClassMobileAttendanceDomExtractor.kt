package org.cabetus.data.classweb

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.MonthDay

/**
 * CLASS スマートフォン版「モバイル出席登録」から、**明示的に表示されている値だけ**を
 * 固定スクリプトで最小限に読み取る。
 *
 * 実機で確認できた構造（2026-11 時点の調査メモ）:
 * - URL は `https://class.admin.tus.ac.jp/uprx/up/pk/pky501/Pky50102.xhtml`
 * - ページに可視の見出し「モバイル出席登録」「出席登録」がある（両方が同時に存在する）
 * - `.mainContent` の中に `#...dataList` があり、その直接の子の `.dataAll` が各授業
 * - 各授業の科目名は `label.textOf.sizeBig` の直接テキスト
 * - 出席確認の受付時間は `label.signSize` の「出席確認時間：HH:mm～HH:mm」
 * - 受付中は `label.signFlging` の「出席確認中」
 * - 出席済みは `label.attendSuc` の文字が完全一致で「出席」のときだけ
 * - 出席コードの入力欄は `maxlength=1` の text input が 4 つ（値は読まない）
 * - 日付は独立した可視H1を含む見出し内の表記だけを読む
 * - 曜日時限のセレクタは実機で未確認のため読み取らない
 *
 * 不要な情報を広範に走査しないため、読み取る要素は次の構造だけに限定する。
 * - 見出し: `h1`〜`h6` / `.ui-title`（日付もこの見出し内だけを見る）
 * - 授業一覧: `.mainContent` 内の `[id$="dataList"]`
 * - 授業ブロック: 一覧の直接の子 `.dataAll`
 * - 授業ブロック内: `label.textOf.sizeBig` / `label.signSize` /
 *   `label.signFlging` / `label.attendSuc` の直接テキストだけ
 * - 学内アクセスの表示は、実機で確認できた `.mainContent` 内の `span` の葉（子要素なし）の
 *   直接テキストが完全一致「学内ネットワークからのアクセス」のときだけ true
 *   （1 件だけ確認できた場合に限る）。学外は推測せず、確認できない場合は false（不明）のまま。
 *   IP アドレス等の label は走査しない。
 *
 * 見出し以外の兄弟・親・他の label や、入力欄・表のセル・任意の div/span は走査しない。
 * 曜日時限が読めない画面では、科目・日付を一意に紐付けられないものとして何も反映しない。
 * 入力欄（出席コード）の値・IP アドレス・Cookie・localStorage・フォーム値・hidden input は読まない
 * （IP アドレスを探す正規表現も持たない）。`addJavascriptInterface` は使わない。
 *
 * 出席の判定は「`label.attendSuc` + 完全一致『出席』」だけを根拠にし、
 * `sizeBig` などの見た目の class から出席を推測しない（実機で確認済みの形式のみ）。
 * 科目名・日付・時限を一意に確定できない授業は出席完了・未提出のどちらにも扱わない。
 *
 * 出欠の登録・送信・コード入力は行わない。読み取り専用。
 */
object ClassMobileAttendanceDomExtractor {

    const val TARGET_HOST = "class.admin.tus.ac.jp"

    /**
     * 実機で確認できたモバイル出席登録のページパス（完全一致）。
     * パスだけを根拠に読み取りは行わず、DOM（見出し・#…dataList・.dataAll）で検証する。
     */
    val KNOWN_PATHS: List<String> = listOf("/uprx/up/pk/pky501/Pky50102.xhtml")

    /** 実機で確認できた見出し（部分一致で探す）。 */
    val TITLE_LABELS: List<String> = listOf("モバイル出席登録", "出席登録")

    /** 出席済みを表す、完全一致で確認できたラベル文字。 */
    const val SUCCESS_LABEL: String = "出席"

    /** 出席確認の受付中を表す、完全一致で確認できたラベル文字。 */
    const val OPEN_LABEL: String = "出席確認中"

    /** 出席済みを表す、実機で確認済みの class（完全一致トークン）。 */
    const val SUCCESS_CLASS: String = "attendSuc"

    /** 科目名を表す、実機で確認済みの class（完全一致トークン）。 */
    const val COURSE_CLASS: String = "sizeBig"

    /** 科目名の外側 wrapper を表す、実機で確認済みの class（完全一致トークン）。 */
    const val COURSE_WRAPPER_CLASS: String = "textOf"

    /** 出席確認時間を表す、実機で確認済みの class（完全一致トークン）。 */
    const val WINDOW_CLASS: String = "signSize"

    /** 出席確認中を表す、実機で確認済みの class（完全一致トークン）。 */
    const val OPEN_CLASS: String = "signFlging"

    /** 出席確認時間の必須接頭辞。 */
    const val WINDOW_PREFIX: String = "出席確認時間："

    /**
     * 学内アクセスを表す、実機で確認できた `span` の直接テキスト（完全一致）。
     * `.mainContent span` の葉で、可視かつ 1 件だけのときに限り学内と確認する。
     * この表記が無いことを学外の根拠にはしない（不明のまま）。
     */
    const val CAMPUS_LABEL: String = "学内ネットワークからのアクセス"

    /** 学習者が表示中のページで確認できる授業ブロックの上限。 */
    const val MAX_COURSES: Int = 40

    private const val MAX_TEXT_LENGTH = 120
    const val MAX_SLOT_LENGTH = 24
    private const val MAX_TITLE_LENGTH = 40
    private const val MAX_RAW_VALUES = 4
    /** 1授業ブロックで見る label の上限（DOM が想定外に大きくても走査し続けない）。 */
    private const val MAX_LABELS_PER_BLOCK = 60
    private const val DATALIST_ID_SUFFIX = "dataList"
    private const val DATA_ALL_CLASS = "dataAll"
    private const val MAIN_CONTENT_CLASS = "mainContent"

    /** パスが実機で確認できたモバイル出席登録のものか（読み取りの可否は DOM で別途検証する）。 */
    fun isKnownPagePath(path: String?): Boolean = path in KNOWN_PATHS

    private val titleLabelsJsLiteral: String =
        TITLE_LABELS.joinToString(separator = ", ") { "'$it'" }

    private val targetHostJsLiteral: String = "'$TARGET_HOST'"

    /**
     * URL・表示状態・見出し・授業ブロックを確認する共通スクリプト片。
     * 走査する要素は見出し・授業一覧・各授業の label だけに限定し、
     * ページ全体のテキストや IP 等の値は扱わない。
     */
    private val guardJs: String = """
        var TARGET_HOST = $targetHostJsLiteral;
        var TITLE_LABELS = [$titleLabelsJsLiteral];
        var SUCCESS_LABEL = '$SUCCESS_LABEL';
        var OPEN_LABEL = '$OPEN_LABEL';
        var SUCCESS_CLASS = '$SUCCESS_CLASS';
        var COURSE_CLASS = '$COURSE_CLASS';
        var COURSE_WRAPPER_CLASS = '$COURSE_WRAPPER_CLASS';
        var WINDOW_CLASS = '$WINDOW_CLASS';
        var OPEN_CLASS = '$OPEN_CLASS';
        var WINDOW_PREFIX = '$WINDOW_PREFIX';
        var CAMPUS_LABEL = '$CAMPUS_LABEL';
        var MAX_COURSES = $MAX_COURSES;
        var MAX_TEXT_LENGTH = $MAX_TEXT_LENGTH;
        var MAX_TITLE_LENGTH = $MAX_TITLE_LENGTH;
        var MAX_RAW_VALUES = $MAX_RAW_VALUES;
        var MAX_LABELS_PER_BLOCK = $MAX_LABELS_PER_BLOCK;
        var DATALIST_ID_SUFFIX = '$DATALIST_ID_SUFFIX';
        var DATA_ALL_CLASS = '$DATA_ALL_CLASS';
        var MAIN_CONTENT_CLASS = '$MAIN_CONTENT_CLASS';

        // 見出しは heading / CLASS のタイトル要素だけを見る（ページ全体は走査しない）。
        var TITLE_SELECTOR = 'h1, h2, h3, h4, h5, h6, .ui-title';
        // 授業ブロック内で読み取る値は label だけに限定する。
        var LABEL_SELECTOR = 'label';

        // 出席確認時間（必須接頭辞を除いた例: 10:20～12:00）
        var TIME_PATTERN = /^\d{1,2}:\d{2}\s*[~～〜]\s*\d{1,2}:\d{2}$/;

        // 日付（例: 2026/11/13(金)、11/13）。見出し内の表記だけを完全一致で読む。
        var DATE_PATTERN =
          /^(\d{4}\s*[\/年.\-]\s*)?\d{1,2}\s*[\/月]\s*\d{1,2}\s*日?\s*(?:\(|（)?[月火水木金土日]?(?:\)|）)?$/;

        // 出席以外の状態。判定対象にしない（値を補わない）。
        var NEGATIVE_PATTERN = /欠席|未出席|出席停止/;

        function normalize(text) {
          return String(text == null ? '' : text)
            .replace(/\u00a0/g, ' ')
            .replace(/\s+/g, ' ')
            .trim();
        }

        function pageError() {
          if (window.location.protocol !== 'https:' ||
              window.location.hostname !== TARGET_HOST) {
            return 'UNEXPECTED_PAGE';
          }
          if (document.visibilityState && document.visibilityState !== 'visible') {
            return 'PAGE_NOT_VISIBLE';
          }
          return null;
        }

        function visible(element) {
          if (!element || !element.getClientRects) return false;
          if (element.getClientRects().length === 0) return false;
          if (!window.getComputedStyle) return true;
          var style = window.getComputedStyle(element);
          return style.display !== 'none' &&
            style.visibility !== 'hidden' &&
            style.visibility !== 'collapse';
        }

        function classTokens(element) {
          if (!element || !element.getAttribute) return [];
          return normalize(element.getAttribute('class')).split(' ').filter(function (t) {
            return t.length > 0;
          });
        }

        // class の完全一致トークンだけを受け付ける（部分一致で別の class を拾わない）。
        function hasClassToken(element, token) {
          var expected = String(token).toLowerCase();
          var tokens = classTokens(element);
          for (var i = 0; i < tokens.length; i++) {
            if (tokens[i].toLowerCase() === expected) return true;
          }
          return false;
        }

        // 子要素の textContent を親が重複して含むため、直接のテキストノードだけを読む。
        function directText(element) {
          if (!element || !element.childNodes) return '';
          var text = '';
          for (var i = 0; i < element.childNodes.length; i++) {
            var node = element.childNodes[i];
            if (node.nodeType === 3 || node.nodeType === 4) {
              text += String(node.nodeValue == null ? '' : node.nodeValue);
            }
          }
          return normalize(text);
        }

        function containsTitle(text) {
          for (var i = 0; i < TITLE_LABELS.length; i++) {
            if (text.indexOf(TITLE_LABELS[i]) >= 0) return TITLE_LABELS[i];
          }
          return null;
        }

        // ページ見出し（モバイル出席登録 / 出席登録）を、heading / .ui-title の
        // 短い直接テキストからだけ探す。実機では 2 つの見出しが同時に存在するため、
        // 既知の見出しだけなら複数でも許容し、「モバイル出席登録」を優先する。
        function findTitle() {
          var elements = document.querySelectorAll(TITLE_SELECTOR);
          var matches = [];
          for (var i = 0; i < elements.length && matches.length < 4; i++) {
            var element = elements[i];
            if (!visible(element)) continue;
            var text = directText(element);
            if (!text || text.length > MAX_TITLE_LENGTH) continue;
            var label = containsTitle(text);
            if (label) matches.push({ element: element, label: label, text: text });
          }
          if (matches.length === 0) return { error: 'TITLE_NOT_FOUND' };
          if (matches.length > 2) return { error: 'TITLE_AMBIGUOUS' };
          var preferred = matches[0];
          for (var j = 0; j < matches.length; j++) {
            if (matches[j].label === TITLE_LABELS[0]) { preferred = matches[j]; break; }
          }
          return { error: null, matches: matches, preferred: preferred };
        }

        // 日付は見出し（heading / .ui-title）内の完全一致の表記だけを読む。
        // 兄弟・親・他の label などは走査しない（汎用のテキスト探索をしない）。
        function headingDates(element) {
          var found = [];
          function push(text) {
            var value = normalize(text);
            if (!value || value.length > 60) return;
            if (!DATE_PATTERN.test(value)) return;
            if (found.indexOf(value) < 0 && found.length < MAX_RAW_VALUES) found.push(value);
          }
          push(directText(element));
          if (!element || !element.children) return found;
          var children = element.children;
          for (var i = 0; i < children.length && found.length < MAX_RAW_VALUES; i++) {
            push(directText(children[i]));
          }
          return found;
        }

        // 見出しが複数ある場合も、それぞれの見出し内の日付をまとめて集める。
        function headingDatesOfAll(matches) {
          var found = [];
          // The verified mobile screen renders the date in a separate visible H1.
          // Restrict to heading elements and exact date grammar, never body/form text.
          var headings = document.querySelectorAll(TITLE_SELECTOR);
          for (var i = 0; i < headings.length; i++) {
            if (!visible(headings[i])) continue;
            var dates = headingDates(headings[i]);
            for (var j = 0; j < dates.length; j++) {
              if (found.indexOf(dates[j]) < 0 && found.length < MAX_RAW_VALUES) {
                found.push(dates[j]);
              }
            }
          }
          return found;
        }

        // 学内アクセスの表示は、実機で確認できた `.mainContent` 内の `span` の葉だけを根拠にする。
        // 可視・子要素なし・直接テキストが完全一致「学内ネットワークからのアクセス」の
        // 要素が 1 件だけのとき true。0 件・複数件・非表示は false（不明）のまま。
        // 学外は推測しない（この表記の不在を学外の根拠にしない）。
        // IP アドレス・入力欄・属性値・任意の label は走査しない。
        function campusNetworkVisible() {
          var elements = document.querySelectorAll('.mainContent span');
          var matches = 0;
          for (var i = 0; i < elements.length; i++) {
            var element = elements[i];
            if (!visible(element)) continue;
            if (element.children.length !== 0) continue;
            if (directText(element) !== CAMPUS_LABEL) continue;
            matches++;
            if (matches > 1) return false;
          }
          return matches === 1;
        }

        function mainContent() {
          var mains = document.querySelectorAll('.' + MAIN_CONTENT_CLASS);
          if (mains.length === 0) return { error: 'MAIN_CONTENT_NOT_FOUND' };
          if (mains.length > 1) return { error: 'MAIN_CONTENT_AMBIGUOUS' };
          return { error: null, main: mains[0] };
        }

        // 授業一覧は `.mainContent` 内の id が dataList で終わる要素だけに限定する。
        function dataList(main) {
          var candidates = main.querySelectorAll('[id${'$'}="' + DATALIST_ID_SUFFIX + '"]');
          if (candidates.length === 0) return { error: 'DATALIST_NOT_FOUND' };
          if (candidates.length > 1) return { error: 'DATALIST_AMBIGUOUS' };
          return { error: null, list: candidates[0] };
        }

        // 授業ブロックは一覧の直接の子 `.dataAll` だけに限定する（入れ子は追わない）。
        function courseBlocks(list) {
          var blocks = [];
          var children = list.children || [];
          for (var i = 0; i < children.length; i++) {
            if (!hasClassToken(children[i], DATA_ALL_CLASS)) continue;
            blocks.push(children[i]);
            if (blocks.length > MAX_COURSES) return { error: 'TOO_LARGE' };
          }
          if (blocks.length === 0) return { error: 'NO_COURSES' };
          return { error: null, blocks: blocks };
        }

        // 1授業ブロックの label だけから、科目名・出席確認時間・出席ラベルを集める。
        // 実機で確認済みの class（textOf/sizeBig・signSize・signFlging・attendSuc）の
        // label だけを選び、その直接テキストを読む。
        // 曜日時限のセレクタは未確認のため推測せず、slots は空のまま返す。
        // 出席は「label.attendSuc + 完全一致『出席』」だけで判定する。
        // 入力欄（出席コード）は参照しない（値も読まない）。
        function scanBlock(block) {
          var courseNames = [];
          var slots = [];
          var dates = [];
          var windows = [];
          var successCount = 0;
          var negative = false;
          var confirmationOpen = false;
          var scanned = 0;

          function pushUnique(list, value) {
            if (!value) return;
            if (list.length >= MAX_RAW_VALUES) return;
            if (list.indexOf(value) < 0) list.push(value);
          }

          var labels = block.querySelectorAll(LABEL_SELECTOR);
          for (var i = 0; i < labels.length && scanned < MAX_LABELS_PER_BLOCK; i++) {
            var element = labels[i];
            if (!visible(element)) continue;
            scanned++;

            var isSuccess = hasClassToken(element, SUCCESS_CLASS);
            var isWindow = hasClassToken(element, WINDOW_CLASS);
            var isOpen = hasClassToken(element, OPEN_CLASS);
            var isCourse = hasClassToken(element, COURSE_CLASS) &&
              (hasClassToken(element, COURSE_WRAPPER_CLASS) ||
                (element.parentElement &&
                  hasClassToken(element.parentElement, COURSE_WRAPPER_CLASS)));
            if (!isSuccess && !isWindow && !isOpen && !isCourse) continue;

            var text = directText(element);
            if (!text || text.length > MAX_TEXT_LENGTH) continue;

            if (isSuccess) {
              if (text === SUCCESS_LABEL) successCount++;
              else if (NEGATIVE_PATTERN.test(text)) negative = true;
              continue;
            }
            if (isOpen) {
              if (text === OPEN_LABEL) confirmationOpen = true;
              continue;
            }
            if (isWindow) {
              // 必須の接頭辞がある表記だけを受付時間として読む。
              if (text.indexOf(WINDOW_PREFIX) !== 0) continue;
              var timeText = normalize(text.substring(WINDOW_PREFIX.length));
              if (TIME_PATTERN.test(timeText)) pushUnique(windows, timeText);
              continue;
            }
            if (isCourse) {
              pushUnique(courseNames, text);
            }
          }

          return {
            courseNames: courseNames,
            slots: slots,
            dates: dates,
            windows: windows,
            successCount: successCount,
            confirmationOpen: confirmationOpen,
            negative: negative
          };
        }
    """.trimIndent()

    enum class ErrorCode(val jsCode: String) {
        UNEXPECTED_PAGE("UNEXPECTED_PAGE"),
        PAGE_NOT_VISIBLE("PAGE_NOT_VISIBLE"),
        MAIN_CONTENT_NOT_FOUND("MAIN_CONTENT_NOT_FOUND"),
        MAIN_CONTENT_AMBIGUOUS("MAIN_CONTENT_AMBIGUOUS"),
        TITLE_NOT_FOUND("TITLE_NOT_FOUND"),
        TITLE_AMBIGUOUS("TITLE_AMBIGUOUS"),
        PAGE_DATE_NOT_FOUND("PAGE_DATE_NOT_FOUND"),
        PAGE_DATE_AMBIGUOUS("PAGE_DATE_AMBIGUOUS"),
        DATALIST_NOT_FOUND("DATALIST_NOT_FOUND"),
        DATALIST_AMBIGUOUS("DATALIST_AMBIGUOUS"),
        NO_COURSES("NO_COURSES"),
        TOO_LARGE("TOO_LARGE"),
        SCRIPT_FAILED("SCRIPT_FAILED"),
        INVALID_RESULT("INVALID_RESULT"),
        ;

        val userMessage: String
            get() = when (this) {
                UNEXPECTED_PAGE ->
                    "CLASS のモバイル出席登録ページ（https・class.admin.tus.ac.jp）ではありません。" +
                        "モバイル出席登録の画面を開き直してください。"
                PAGE_NOT_VISIBLE ->
                    "モバイル出席登録の画面が表示状態ではありません。画面を前面に表示してから再試行してください。"
                MAIN_CONTENT_NOT_FOUND ->
                    "モバイル出席登録の内容（.mainContent）が見つかりません。" +
                        "出席登録の画面が表示されているか確認してください。"
                MAIN_CONTENT_AMBIGUOUS ->
                    "モバイル出席登録の内容（.mainContent）を一意に特定できません。" +
                        "画面のレイアウトが想定と異なるため読み取りません。"
                TITLE_NOT_FOUND ->
                    "見出し「モバイル出席登録」「出席登録」を確認できません。" +
                        "出席登録の画面か確認してください。"
                TITLE_AMBIGUOUS ->
                    "見出し「モバイル出席登録」「出席登録」が複数あります。" +
                        "画面のレイアウトが想定と異なるため読み取りません。"
                PAGE_DATE_NOT_FOUND ->
                    "ページの対象日付を確認できません。科目・日付を確実に紐付けられないため反映しません。"
                PAGE_DATE_AMBIGUOUS ->
                    "ページの対象日付を一意に確認できません。科目・日付を確実に紐付けられないため反映しません。"
                DATALIST_NOT_FOUND ->
                    "出席登録の授業一覧（#…dataList）が見つかりません。" +
                        "出席登録の画面が表示されているか確認してください。"
                DATALIST_AMBIGUOUS ->
                    "出席登録の授業一覧（#…dataList）の候補が複数あります。" +
                        "画面のレイアウトが想定と異なるため読み取りません。"
                NO_COURSES ->
                    "出席登録の授業（.dataAll）が見つかりません。" +
                        "出席登録の画面が表示されているか確認してください。"
                TOO_LARGE ->
                    "出席登録の授業数が想定より大きいため読み取りません。"
                SCRIPT_FAILED ->
                    "出席登録ページの読み取りに失敗しました。ページを再読み込みしてから再試行してください。"
                INVALID_RESULT ->
                    "出席登録ページの読み取り結果を確認できませんでした。" +
                        "ページを再読み込みしてから再試行してください。"
            }

        companion object {
            fun fromJsCode(code: String?): ErrorCode =
                entries.firstOrNull { it.jsCode == code } ?: INVALID_RESULT
        }
    }

    /**
     * 画面の日付表記。年がない表記は年を推測せず [year] を null のままにする。
     * 曜日表記があれば、年が確定している場合だけ整合性を確認する。
     */
    data class CapturedDate(
        val year: Int?,
        val month: Int,
        val day: Int,
        val weekdayLabel: String? = null,
    ) {
        val monthDay: MonthDay get() = MonthDay.of(month, day)

        val isYearKnown: Boolean get() = year != null

        /** 画面の表記（年があるものだけ年を含む、表示用の短い文字列）。 */
        fun displayLabel(): String =
            if (year != null) "%d/%d/%d".format(year, month, day) else "%d/%d".format(month, day)

        /**
         * 同じ日付か。画面の日付（ページ日付）と授業ブロックの日付を比べる用途に限る。
         * 年が片方にしか無い場合は月日で比較し、両方に年がある場合は年も一致させる
         * （年の食い違いは同一日付と扱わない）。
         */
        fun sameDateAs(other: CapturedDate): Boolean {
            if (month != other.month || day != other.day) return false
            if (year != null && other.year != null) return year == other.year
            return true
        }

        /**
         * 端末のローカル日付と一致するか。
         * 年がない表記は月日だけで比較する（年を推測しない）。
         */
        fun matches(date: LocalDate): Boolean =
            if (year != null) {
                year == date.year && month == date.monthValue && day == date.dayOfMonth
            } else {
                month == date.monthValue && day == date.dayOfMonth
            }

        companion object {
            private val FULL = Regex(
                "^(\\d{4})\\s*[年/\\-.]\\s*(\\d{1,2})\\s*[月/\\-.]\\s*(\\d{1,2})\\s*日?" +
                    "\\s*(?:[(（]\\s*([月火水木金土日])\\s*[)）])?$",
            )
            private val MONTH_DAY = Regex(
                "^(\\d{1,2})\\s*[月/]\\s*(\\d{1,2})\\s*日?\\s*(?:[(（]\\s*([月火水木金土日])\\s*[)）])?$",
            )
            private val WEEKDAYS = mapOf(
                "月" to 1, "火" to 2, "水" to 3, "木" to 4, "金" to 5, "土" to 6, "日" to 7,
            )

            /**
             * 日付表記を検証して読む。存在しない月日・曜日と日付の不一致は null。
             * 年なしの表記は [year] を null のままにする（推測しない）。
             */
            fun parse(text: String?): CapturedDate? {
                val cleaned = (text ?: "").replace('\u00a0', ' ').trim()
                    .replace(Regex("\\s+"), " ")
                if (cleaned.isEmpty() || cleaned.length > 40) return null

                FULL.matchEntire(cleaned)?.let { m ->
                    val year = m.groupValues[1].toIntOrNull() ?: return null
                    val month = m.groupValues[2].toIntOrNull() ?: return null
                    val day = m.groupValues[3].toIntOrNull() ?: return null
                    val weekday = m.groupValues[4].takeIf { it.isNotEmpty() }
                    val date = runCatching { LocalDate.of(year, month, day) }.getOrNull()
                        ?: return null
                    if (!weekdayMatches(weekday, date)) return null
                    return CapturedDate(year, month, day, weekday)
                }

                MONTH_DAY.matchEntire(cleaned)?.let { m ->
                    val month = m.groupValues[1].toIntOrNull() ?: return null
                    val day = m.groupValues[2].toIntOrNull() ?: return null
                    val weekday = m.groupValues[3].takeIf { it.isNotEmpty() }
                    // 年が無いので実在する月日だけを確認する（2/29 は許容し、年は確定しない）。
                    if (runCatching { MonthDay.of(month, day) }.isFailure) return null
                    return CapturedDate(null, month, day, weekday)
                }

                return null
            }

            private fun weekdayMatches(label: String?, date: LocalDate): Boolean {
                if (label == null) return true
                val expected = WEEKDAYS[label] ?: return false
                return date.dayOfWeek.value == expected
            }
        }
    }

    /**
     * 出席確認の受付時間（`label.signSize` の「出席確認時間：HH:mm～HH:mm」）。
     * 日付をまたぐ時間帯は想定していないため、終了が開始以前の表記は不正として扱う。
     */
    data class ConfirmationWindow(
        val start: LocalTime,
        val end: LocalTime,
    ) {
        val isValid: Boolean get() = end.isAfter(start)

        /** 受付中か（開始ちょうど〜終了ちょうど）。 */
        fun isOpenAt(time: LocalTime): Boolean = !time.isBefore(start) && !time.isAfter(end)

        /** 同じ日の時刻までの残り秒数（負にはならない）。 */
        fun remainingSeconds(now: LocalTime): Long =
            Duration.between(now, end).seconds.coerceAtLeast(0L)

        /** 「10:20～12:00」のような表示。 */
        fun displayLabel(): String = "%02d:%02d～%02d:%02d".format(
            start.hour, start.minute, end.hour, end.minute,
        )

        companion object {
            private val PATTERN = Regex(
                "^(\\d{1,2}):(\\d{2})\\s*[~～〜]\\s*(\\d{1,2}):(\\d{2})$",
            )

            /**
             * 受付時間の表記を検証して読む。実在しない時刻・逆転した範囲は null。
             * 先頭の「出席確認時間：」等の見出しは呼び出し側で除いたものを渡す。
             */
            fun parse(text: String?): ConfirmationWindow? {
                val cleaned = (text ?: "").replace('\u00a0', ' ').trim()
                if (cleaned.isEmpty() || cleaned.length > 24) return null
                val m = PATTERN.matchEntire(cleaned) ?: return null
                val start = runCatching {
                    LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt())
                }.getOrNull() ?: return null
                val end = runCatching {
                    LocalTime.of(m.groupValues[3].toInt(), m.groupValues[4].toInt())
                }.getOrNull() ?: return null
                if (!end.isAfter(start)) return null
                return ConfirmationWindow(start, end)
            }
        }
    }

    /** 曜日時限の表記。例: 「木1」「木1・2」。 */
    data class SlotLabel(
        /** 1=月 … 7=日。 */
        val dayOfWeek: Int,
        val periods: List<Int>,
        val raw: String,
    ) {
        /** 単一の時限として確定できるときだけ値を返す。 */
        val singlePeriod: Int? get() = periods.singleOrNull()

        companion object {
            private val PATTERN = Regex(
                "^([月火水木金土日])(?:曜日?)?\\s*" +
                    "(\\d{1,2}(?:\\s*[・,、/]\\s*\\d{1,2})*)\\s*(?:限|時限)?$",
            )
            private val WEEKDAYS = mapOf(
                "月" to 1, "火" to 2, "水" to 3, "木" to 4, "金" to 5, "土" to 6, "日" to 7,
            )

            fun parse(text: String?): SlotLabel? {
                val cleaned = (text ?: "").replace('\u00a0', ' ').trim()
                if (cleaned.isEmpty() || cleaned.length > MAX_SLOT_LENGTH) return null
                val m = PATTERN.matchEntire(cleaned) ?: return null
                val day = WEEKDAYS[m.groupValues[1]] ?: return null
                // 一部だけ不正な時限（「木1・25」など）を切り捨てて採用しない。
                // 表記全体を検証し、1 つでも解釈できない値があれば全体を拒否する。
                val tokens = m.groupValues[2].split(Regex("[・,、/]"))
                if (tokens.isEmpty()) return null
                val periods = tokens.map { token ->
                    val period = token.trim().toIntOrNull() ?: return null
                    if (period !in 1..20) return null
                    period
                }.distinct()
                if (periods.isEmpty()) return null
                return SlotLabel(dayOfWeek = day, periods = periods, raw = cleaned)
            }
        }
    }

    /** 画面に表示されていた1授業分の観測結果。判定は呼び出し側が行う。 */
    data class ObservedCourse(
        /** 一意に確認できた科目名。複数候補・未検出なら null。 */
        val courseName: String?,
        /** 同じ授業ブロックの曜日時限（セレクタ未確認のため現状は常に null）。 */
        val slot: SlotLabel?,
        /** 同じ授業ブロックに完全一致の「出席」ラベルがあったか。 */
        val successLabelVisible: Boolean,
        /** 同じ授業ブロックの日付（実機で確認できていないため現状は常に null）。 */
        val date: CapturedDate?,
        /** 同じ授業ブロックの出席確認時間（一意に確認できたときだけ）。 */
        val confirmationWindow: ConfirmationWindow?,
        /** 同じ授業ブロックに「出席確認中」が可視で表示されていたか。 */
        val confirmationOpen: Boolean,
        /** 欠席などの別表記、または候補の重複で判定できない。 */
        val ambiguous: Boolean,
    ) {
        /**
         * 出席完了として扱えるか。
         * 科目名・日付・時限が揃い、かつ「出席」ラベルの日付がページ日付と一致する場合だけ true。
         */
        fun isAttendanceConfirmed(pageDate: CapturedDate?): Boolean {
            if (ambiguous) return false
            if (!successLabelVisible) return false
            if (courseName.isNullOrBlank()) return false
            if (slot?.singlePeriod == null) return false
            val courseDate = date ?: return false
            if (pageDate == null) return false
            return courseDate.sameDateAs(pageDate)
        }

        /**
         * 「読み取った時点では出席コード未提出」としてホームに締切を出せるか。
         * 画面に「出席確認中」が可視で表示され、「出席」ラベルも欠席等の表記も無く、
         * 科目名・日付・時限・受付時間がすべて一意に揃った場合だけ true。
         * 「出席」ラベルの不在や受付時間だけから未提出を推測しない。
         */
        fun isAttendancePending(pageDate: CapturedDate?): Boolean {
            if (ambiguous) return false
            if (successLabelVisible) return false
            if (!confirmationOpen) return false
            if (courseName.isNullOrBlank()) return false
            if (slot?.singlePeriod == null) return false
            val courseDate = date ?: return false
            if (pageDate == null) return false
            if (!courseDate.sameDateAs(pageDate)) return false
            val window = confirmationWindow ?: return false
            return window.isValid
        }
    }

    data class Observation(
        /** 確認できた見出し（[TITLE_LABELS] のいずれか）。 */
        val titleLabel: String,
        val pageDate: CapturedDate?,
        /**
         * `.mainContent` 内の `span` の葉に「学内ネットワークからのアクセス」が可視で
         * 1 件だけ確認できたか。false は学外ではなく不明を表す。
         */
        val campusNetworkLabelVisible: Boolean,
        val courses: List<ObservedCourse>,
        val warnings: List<String>,
    )

    data class Result(
        val observation: Observation?,
        val warnings: List<String>,
        val error: ErrorCode?,
    ) {
        val isSuccess: Boolean get() = error == null && observation != null
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** 固定スクリプト。成功/失敗と理由だけを返し、科目名などは返さない。 */
    internal val VERIFY_SCRIPT: String = """
        (function () {
          'use strict';
          $guardJs

          function result(ok, code) {
            return JSON.stringify({ ok: ok, error: code });
          }

          try {
            var pageFailure = pageError();
            if (pageFailure) return result(false, pageFailure);

            var title = findTitle();
            if (title.error) return result(false, title.error);

            var main = mainContent();
            if (main.error) return result(false, main.error);

            var list = dataList(main.main);
            if (list.error) return result(false, list.error);

            var blocks = courseBlocks(list.list);
            if (blocks.error) return result(false, blocks.error);

            return result(true, null);
          } catch (e) {
            return result(false, 'SCRIPT_FAILED');
          }
        })();
    """.trimIndent()

    /**
     * 固定スクリプト。URL・表示状態・見出し・授業ブロックを再検証したうえで、
     * 科目名・出席確認時間・出席ラベルの有無だけを返す。
     * 曜日時限は実機でセレクタを確認していないため常に空（紐付け不能を許容する）。
     * 学内アクセスは `.mainContent` 内の `span` の葉の完全一致ラベルが 1 件だけ可視のとき true。
     * 確認できない場合は false（不明）で、学外とは判定しない。
     * 出席コードの入力値・IP ラベルの値・時間帯・ページ全体のテキストは返さない。
     * 登録・送信は行わない。
     */
    internal val SCRIPT: String = """
        (function () {
          'use strict';
          $guardJs

          function fail(code, warnings) {
            return JSON.stringify({
              ok: false,
              error: code,
              titleLabel: null,
              pageDates: [],
              campusNetworkLabelVisible: false,
              courses: [],
              warnings: warnings || []
            });
          }

          try {
            var pageFailure = pageError();
            if (pageFailure) return fail(pageFailure);

            var title = findTitle();
            if (title.error) return fail(title.error);
            var titleMatch = title.preferred;

            var main = mainContent();
            if (main.error) return fail(main.error);

            var list = dataList(main.main);
            if (list.error) return fail(list.error);

            var blocks = courseBlocks(list.list);
            if (blocks.error) return fail(blocks.error);

            var pageDates = headingDatesOfAll(title.matches);
            var campusVisible = campusNetworkVisible();

            var courses = [];
            for (var i = 0; i < blocks.blocks.length; i++) {
              courses.push(scanBlock(blocks.blocks[i]));
            }

            return JSON.stringify({
              ok: true,
              error: null,
              titleLabel: titleMatch.label,
              pageDates: pageDates,
              campusNetworkLabelVisible: campusVisible,
              courses: courses,
              warnings: []
            });
          } catch (e) {
            return fail('SCRIPT_FAILED');
          }
        })();
    """.trimIndent()

    /** 検証スクリプトの戻り値を検証する。成功なら null、失敗なら [ErrorCode]。 */
    fun parseVerificationResult(raw: String?): ErrorCode? {
        val decoded = decode(raw) ?: return ErrorCode.INVALID_RESULT
        val root = runCatching { json.parseToJsonElement(decoded) as? JsonObject }.getOrNull()
            ?: return ErrorCode.INVALID_RESULT

        val okValue = root["ok"] as? JsonPrimitive ?: return ErrorCode.INVALID_RESULT
        if (okValue.isString) return ErrorCode.INVALID_RESULT

        val errorCode = (root["error"] as? JsonPrimitive)?.contentOrNull
        if (okValue.booleanOrNull == true) {
            return if (errorCode == null) null else ErrorCode.INVALID_RESULT
        }
        return ErrorCode.fromJsCode(errorCode)
    }

    /**
     * 抽出スクリプトの戻り値を検証し、[Observation] へ変換する。
     * 形式不正・矛盾した結果は [ErrorCode.INVALID_RESULT] として扱い、保存対象にしない。
     */
    fun parseEvaluationResult(raw: String?): Result {
        val decoded = decode(raw) ?: return Result(null, emptyList(), ErrorCode.INVALID_RESULT)
        val root = runCatching { json.parseToJsonElement(decoded) as? JsonObject }.getOrNull()
            ?: return Result(null, emptyList(), ErrorCode.INVALID_RESULT)

        val warnings = root.stringArray("warnings")
        val okValue = root["ok"] as? JsonPrimitive ?: return Result(null, warnings, ErrorCode.INVALID_RESULT)
        if (okValue.isString) return Result(null, warnings, ErrorCode.INVALID_RESULT)
        val errorCode = (root["error"] as? JsonPrimitive)?.contentOrNull
        if (okValue.booleanOrNull != true) {
            return Result(null, warnings, ErrorCode.fromJsCode(errorCode))
        }
        if (errorCode != null) return Result(null, warnings, ErrorCode.INVALID_RESULT)

        val titlePrimitive = root["titleLabel"] as? JsonPrimitive
            ?: return Result(null, warnings, ErrorCode.INVALID_RESULT)
        // 見出しは文字列だけを受け付ける（数値・真偽値・配列などは不正な結果として扱う）。
        if (!titlePrimitive.isString) return Result(null, warnings, ErrorCode.INVALID_RESULT)
        val titleText = titlePrimitive.content
        val title = TITLE_LABELS.firstOrNull { titleText.contains(it) }
            ?: return Result(null, warnings, ErrorCode.INVALID_RESULT)

        val pageDateRaws = root.stringArray("pageDates")
        val parsedPageDates = pageDateRaws.mapNotNull { CapturedDate.parse(it) }.distinct()
        val pageDate = when {
            parsedPageDates.size == 1 -> parsedPageDates.single()
            parsedPageDates.isEmpty() -> null
            else -> null
        }
        if (parsedPageDates.size > 1) {
            return Result(null, warnings, ErrorCode.PAGE_DATE_AMBIGUOUS)
        }
        if (pageDate == null) {
            return Result(null, warnings, ErrorCode.PAGE_DATE_NOT_FOUND)
        }

        val campusVisible = booleanField(root, "campusNetworkLabelVisible")
            ?: return Result(null, warnings, ErrorCode.INVALID_RESULT)

        val courseElements = root["courses"] as? JsonArray
            ?: return Result(null, warnings, ErrorCode.INVALID_RESULT)
        if (courseElements.isEmpty() || courseElements.size > MAX_COURSES) {
            return Result(null, warnings, ErrorCode.INVALID_RESULT)
        }

        val courses = mutableListOf<ObservedCourse>()
        for (element in courseElements) {
            val obj = element as? JsonObject
                ?: return Result(null, warnings, ErrorCode.INVALID_RESULT)
            val courseNames = obj.stringArray("courseNames")
            // 曜日時限のセレクタは実機で未確認のため、スクリプトは slots を返さない。
            // 仮に返ってきた場合も紐付けの根拠にはしない（紐付け不能を許容する）。
            // 生の表記を保持したまま解釈する（解釈できない表記を捨てて矛盾を隠さない）。
            val rawDates = obj.stringArray("dates")
            val dates = rawDates.mapNotNull { CapturedDate.parse(it) }.distinct()
            // 空でないのに日付として解釈できない表記が 1 件でもあれば、日付を確定しない
            // （ページ日付で補完しない）。複数の異なる日付がある場合も同様に確定しない。
            val hasUnparsableDate = rawDates.any { CapturedDate.parse(it) == null }
            // 受付時間はスクリプト側で「出席確認時間：」接頭辞を確認済みの値だけを読む。
            // 接頭辞が残っている値は ConfirmationWindow.parse で不正となる。
            val windows = obj.stringArray("windows")
                .mapNotNull { ConfirmationWindow.parse(windowText(it)) }
                .distinct()
            val successCount = (obj["successCount"] as? JsonPrimitive)?.intOrNull
                ?: return Result(null, warnings, ErrorCode.INVALID_RESULT)
            val confirmationOpen = booleanField(obj, "confirmationOpen")
                ?: return Result(null, warnings, ErrorCode.INVALID_RESULT)
            val negative = booleanField(obj, "negative")
                ?: return Result(null, warnings, ErrorCode.INVALID_RESULT)

            if (successCount < 0 || successCount > MAX_COURSES) {
                return Result(null, warnings, ErrorCode.INVALID_RESULT)
            }

            val name = courseNames.distinct().singleOrNull()
            val slot: SlotLabel? = null
            // 1授業ブロックに日付・受付時間はそれぞれ 1 つだけのはず。複数あれば
            // 別の授業が混ざっている可能性があるため判定しない（対応を推測しない）。
            // 日付はページ日付と一致するものだけを採用し、ページ日付でブロックの日付を補完しない。
            val blockDate = dates.singleOrNull()
            val date = blockDate?.takeIf { !hasUnparsableDate && it.sameDateAs(pageDate) }
            // 解釈できない日付表記がある、または解釈できた日付がページ日付と食い違う場合は
            // 別の日の表示が混ざっている可能性があるため、判定しない。
            val dateConflict = hasUnparsableDate ||
                (blockDate != null && !blockDate.sameDateAs(pageDate))
            val ambiguous = negative ||
                courseNames.size > 1 ||
                successCount > 1 ||
                dates.size > 1 ||
                dateConflict ||
                windows.size > 1

            courses += ObservedCourse(
                courseName = name?.takeIf { it.length <= MAX_TEXT_LENGTH },
                slot = slot,
                successLabelVisible = successCount == 1,
                date = date,
                confirmationWindow = windows.singleOrNull(),
                confirmationOpen = confirmationOpen,
                ambiguous = ambiguous,
            )
        }

        val courseWarnings = warnings.toMutableList()
        if (courses.any { it.ambiguous }) {
            courseWarnings += "判定できない表記（欠席・候補の重複など）がある授業は反映しません。"
        }
        if (courses.any { it.successLabelVisible && it.date == null }) {
            courseWarnings +=
                "「出席」ラベルの近くに対象日付を確認できない授業は、科目・日付を紐付けられないため反映しません。"
        }
        if (courses.any { !it.successLabelVisible && !it.ambiguous && it.confirmationWindow == null }) {
            courseWarnings +=
                "出席確認時間を確認できない授業は、未提出としてホームに表示しません。"
        }
        if (courses.any {
                !it.successLabelVisible && !it.confirmationOpen && it.confirmationWindow != null
            }
        ) {
            courseWarnings +=
                "「出席確認中」の表示が無い授業は、未提出としてホームに表示しません。"
        }

        return Result(
            observation = Observation(
                titleLabel = title,
                pageDate = pageDate,
                campusNetworkLabelVisible = campusVisible,
                courses = courses,
                warnings = courseWarnings.distinct(),
            ),
            warnings = courseWarnings.distinct(),
            error = null,
        )
    }

    private fun booleanField(obj: JsonObject, key: String): Boolean? {
        val value = obj[key] as? JsonPrimitive ?: return null
        if (value.isString) return null
        return value.booleanOrNull
    }

    /**
     * スクリプトが受付時間の接頭辞を除いた後の表記を正規化するだけ。
     * 接頭辞（「出席確認時間：」等）が残っている表記は時刻として解釈できず null となる。
     */
    private fun windowText(value: String): String =
        value.replace('\u00a0', ' ').trim().replace(Regex("\\s+"), " ")

    private fun decode(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text == "null") return null
        val element = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return null
        return when (element) {
            is JsonPrimitive -> element.contentOrNull?.takeIf { element.isString }
            is JsonObject -> text
            else -> null
        }
    }

    private fun JsonObject.stringArray(key: String): List<String> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { element ->
            (element as? JsonPrimitive)?.contentOrNull
                ?.replace('\u00a0', ' ')
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                ?.takeIf { it.isNotEmpty() && it.length <= MAX_TEXT_LENGTH }
        }
}
