package org.cabetus.data.classweb

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * CLASS の画面から「年度・学期」だけを、固定スクリプトで最小限に読み取る。
 *
 * 対象は次のとおり（完全一致のみ）。
 * - ホスト: `https://class.admin.tus.ac.jp`
 * - パス: [TARGET_PATHS] の 2 つだけ（年度・学期の選択がある画面として想定）
 *
 * 読み取るのは `.mainContent` の中にある native な `select` の
 * **選択中の値（`selectedOptions`）の表示文字だけ**である。
 * - `input` / hidden input / 全 option の走査 / フォーム値 / `.value` /
 *   Cookie / localStorage / sessionStorage / ページ全体の HTML は読まない。
 * - `addJavascriptInterface` は使わず、[SCRIPT] は
 *   [android.webkit.WebView.evaluateJavascript] に固定文字列として渡す。
 *   戻り値の JSON 文字列だけを Android 側で検証する。
 *
 * native の `select` がカスタム UI に隠されている場合は、選択中の値をそのまま
 * 信用しない。`select` から `.mainContent` までの祖先がすべて表示されているか、
 * または同じ親の中で「選択中の表示文字と完全一致する可視要素」がちょうど 1 つだけ
 * 確認できた場合に限り受け付ける。どちらも確認できない場合は推測せず拒否する。
 *
 * 年度・学期の候補が 1 つに定まらない場合（複数の select に選択がある、
 * 複数選択、複数候補）は取り込まず、エラーとして返す。
 * 年度は [MIN_YEAR]..[MAX_YEAR]、学期は「前期」「後期」のいずれかだけを受け付ける。
 *
 * この抽出結果を保存する処理・画面・Runner はここには含めない（純粋な読み取りだけ）。
 */
object ClassAcademicContextDomExtractor {

    const val TARGET_HOST = "class.admin.tus.ac.jp"

    /**
     * 対象パス（完全一致のみ）。前方一致やディレクトリ単位の許可はしない。
     *
     * 2 つ目は `Xut61301.xhtml` の配置（モジュールディレクトリ）が実機未確認のため、
     * 既存パス（`bsa501` / `ats501` / `pky501`）と同じ
     * 「モジュール名 + 画面番号 3 桁」の規則で置いている。
     * 実機の URL が異なる場合、この画面では [ErrorCode.UNEXPECTED_PAGE] となり
     * 何も読み取らない（誤ったパスへ広げることはしない）。
     */
    val TARGET_PATHS: List<String> = listOf(
        "/uprx/up/bs/bsa501/Bsa50101.xhtml",
        "/uprx/up/xu/xut613/Xut61301.xhtml",
        "/uprx/up/km/kmd508/Kmd50801.xhtml",
    )

    const val MIN_YEAR: Int = 2000
    const val MAX_YEAR: Int = 2100

    /** 実機で確認できた学期の表示文字。 */
    val TERM_LABELS: List<String> = listOf("前期", "後期")

    /** 読み取り対象の `.mainContent` の class（完全一致トークン）。 */
    const val MAIN_CONTENT_CLASS: String = "mainContent"

    /** 1 ページで見る select の上限（想定外に大きい DOM を走査し続けない）。 */
    const val MAX_SELECTS: Int = 20

    /** 表示一致ガードで見る要素数の上限。 */
    const val MAX_DISPLAY_SCAN: Int = 200

    private const val MAX_SELECTED_TEXT_LENGTH = 40

    private val targetPathsJsLiteral: String =
        TARGET_PATHS.joinToString(separator = ", ") { "'$it'" }

    private val termLabelsJsLiteral: String =
        TERM_LABELS.joinToString(separator = ", ") { "'$it'" }

    /** 学期。表示文字は実機で確認できた「前期」「後期」だけ。 */
    enum class Term(val label: String) {
        FIRST("前期"),
        SECOND("後期"),
        ;

        companion object {
            fun fromLabel(value: String?): Term? = entries.firstOrNull { it.label == value }
        }
    }

    /** 読み取れた年度・学期。year は [MIN_YEAR]..[MAX_YEAR]。 */
    data class AcademicContext(
        val year: Int,
        val term: Term,
    )

    enum class ErrorCode(val jsCode: String) {
        UNEXPECTED_PAGE("UNEXPECTED_PAGE"),
        PAGE_NOT_VISIBLE("PAGE_NOT_VISIBLE"),
        MAIN_CONTENT_NOT_FOUND("MAIN_CONTENT_NOT_FOUND"),
        MAIN_CONTENT_AMBIGUOUS("MAIN_CONTENT_AMBIGUOUS"),
        MAIN_CONTENT_NOT_VISIBLE("MAIN_CONTENT_NOT_VISIBLE"),
        SELECT_NOT_FOUND("SELECT_NOT_FOUND"),
        SELECT_LIMIT_EXCEEDED("SELECT_LIMIT_EXCEEDED"),
        SELECT_AMBIGUOUS("SELECT_AMBIGUOUS"),
        SELECT_NOT_VISIBLE("SELECT_NOT_VISIBLE"),
        NO_SELECTION("NO_SELECTION"),
        UNSUPPORTED_OPTION("UNSUPPORTED_OPTION"),
        YEAR_OUT_OF_RANGE("YEAR_OUT_OF_RANGE"),
        NO_CANDIDATE("NO_CANDIDATE"),
        MULTIPLE_CANDIDATES("MULTIPLE_CANDIDATES"),
        SCRIPT_FAILED("SCRIPT_FAILED"),
        INVALID_RESULT("INVALID_RESULT"),
        ;

        val userMessage: String
            get() = when (this) {
                UNEXPECTED_PAGE ->
                    "CLASS の対象ページ（想定URL）ではありません。対象の画面を開き直してください。"
                PAGE_NOT_VISIBLE ->
                    "対象の画面が表示状態ではありません。画面を前面に表示してから再試行してください。"
                MAIN_CONTENT_NOT_FOUND ->
                    "年度・学期の表示領域（.mainContent）が見つかりません。画面を確認してください。"
                MAIN_CONTENT_AMBIGUOUS ->
                    "年度・学期の表示領域（.mainContent）を一意に確認できません。取り込みません。"
                MAIN_CONTENT_NOT_VISIBLE ->
                    "年度・学期の表示領域が画面に表示されていません。表示してから再試行してください。"
                SELECT_NOT_FOUND ->
                    "年度・学期の選択欄が見つかりません。画面のレイアウトが想定と異なります。"
                SELECT_LIMIT_EXCEEDED ->
                    "選択欄が想定より多く、年度・学期を一意に確認できません。取り込みません。"
                SELECT_AMBIGUOUS ->
                    "年度・学期の選択を一意に確認できません。取り込みません。"
                SELECT_NOT_VISIBLE ->
                    "年度・学期の選択欄が非表示で、表示中の値も確認できません。取り込みません。"
                NO_SELECTION ->
                    "年度・学期が選択されていません。選択してから再試行してください。"
                UNSUPPORTED_OPTION ->
                    "年度・学期の表示形式を確認できません。画面のレイアウトが想定と異なるため取り込みません。"
                YEAR_OUT_OF_RANGE ->
                    "年度が想定の範囲外です。画面の表示を確認してください。"
                NO_CANDIDATE ->
                    "年度・学期の候補が見つかりませんでした。画面を再読み込みしてから再試行してください。"
                MULTIPLE_CANDIDATES ->
                    "年度・学期の候補が複数あるため確定できません。取り込みません。"
                SCRIPT_FAILED ->
                    "年度・学期の読み取りに失敗しました。ページを再読み込みしてから再試行してください。"
                INVALID_RESULT ->
                    "年度・学期の読み取り結果を確認できませんでした。ページを再読み込みしてから再試行してください。"
            }

        companion object {
            fun fromJsCode(code: String?): ErrorCode =
                entries.firstOrNull { it.jsCode == code } ?: INVALID_RESULT
        }
    }

    data class Result(
        val context: AcademicContext?,
        val warnings: List<String>,
        val error: ErrorCode?,
    ) {
        val isSuccess: Boolean get() = error == null
        val year: Int? get() = context?.year
        val term: Term? get() = context?.term
    }

    /** パスが対象の 2 つ（完全一致）のどれかか。URL 判定をテスト可能にするための純ロジック。 */
    fun isTargetPath(path: String?): Boolean = path in TARGET_PATHS

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 固定スクリプト片。URL・表示状態・`.mainContent`・可視判定だけを扱い、
     * 年度・学期の値そのものは読まない。
     */
    private val pageAndContentGuardJs: String = """
        var TARGET_HOST = 'class.admin.tus.ac.jp';
        var TARGET_PATHS = [$targetPathsJsLiteral];
        var MAIN_CONTENT_CLASS = '$MAIN_CONTENT_CLASS';
        var MIN_YEAR = $MIN_YEAR;
        var MAX_YEAR = $MAX_YEAR;
        var TERM_LABELS = [$termLabelsJsLiteral];
        var MAX_SELECTS = $MAX_SELECTS;
        var MAX_DISPLAY_SCAN = $MAX_DISPLAY_SCAN;
        var MAX_TEXT_LENGTH = $MAX_SELECTED_TEXT_LENGTH;

        function normalize(text) {
          return String(text == null ? '' : text)
            .replace(/\u00a0/g, ' ')
            .replace(/\s+/g, ' ')
            .trim();
        }

        function pageError() {
          if (window.location.protocol !== 'https:' ||
              window.location.hostname !== TARGET_HOST ||
              TARGET_PATHS.indexOf(window.location.pathname) < 0) {
            return 'UNEXPECTED_PAGE';
          }
          if (document.visibilityState && document.visibilityState !== 'visible') {
            return 'PAGE_NOT_VISIBLE';
          }
          return null;
        }

        // 実際に描画されている要素だけを可視とみなす。
        function isVisible(element) {
          if (!element || !element.getClientRects) return false;
          if (element.getClientRects().length === 0) return false;
          var style = window.getComputedStyle ? window.getComputedStyle(element) : null;
          if (style && (style.display === 'none' ||
              style.visibility !== 'visible' ||
              style.opacity === '0')) {
            return false;
          }
          return true;
        }

        // select から .mainContent までの祖先がすべて表示されているか。
        // native select 自体がカスタム UI の裏に隠れている場合は false になる。
        function insideVisibleMainContent(element) {
          var node = element;
          var guard = 0;
          while (node && guard < 64) {
            if (node.classList && node.classList.contains(MAIN_CONTENT_CLASS)) return true;
            if (!isVisible(node)) return false;
            node = node.parentElement;
            guard++;
          }
          return false;
        }

        // 表示中の .mainContent を一意に返す。複数ある場合は推測しない。
        function visibleMainContent() {
          var nodes = document.querySelectorAll('.' + MAIN_CONTENT_CLASS);
          if (!nodes || nodes.length === 0) return { error: 'MAIN_CONTENT_NOT_FOUND' };
          if (nodes.length !== 1) return { error: 'MAIN_CONTENT_AMBIGUOUS' };
          if (!isVisible(nodes[0])) return { error: 'MAIN_CONTENT_NOT_VISIBLE' };
          return { content: nodes[0] };
        }

        // native select の選択中の値だけを読む。全 option は走査しない。
        function selectionOf(select) {
          var selected = select.selectedOptions;
          if (!selected) return { count: 0, text: null };
          if (selected.length !== 1) return { count: selected.length, text: null };
          return { count: 1, text: normalize(selected[0].textContent) };
        }

        function hasMatchingChild(element, text) {
          var children = element.children || [];
          for (var i = 0; i < children.length; i++) {
            if (normalize(children[i].textContent) === text) return true;
          }
          return false;
        }

        // 隠された native select を受け入れてよいのは、同じ親の中で
        // 「選択中の表示文字と完全一致する可視要素」がちょうど 1 つだけ
        // 確認できた場合に限る。0 件・2 件以上は曖昧として拒否する。
        function displayedTextMismatch(select, text) {
          var parent = select.parentElement;
          if (!parent) return 'SELECT_NOT_VISIBLE';
          var nodes = parent.querySelectorAll('*');
          var matches = 0;
          var scanned = 0;
          for (var i = 0; i < nodes.length; i++) {
            var element = nodes[i];
            var tag = element.tagName ? element.tagName.toLowerCase() : '';
            if (tag === 'script' || tag === 'style' || tag === 'textarea') continue;
            // select 配下（option 等）は表示中の値として数えない。
            if (element.closest && element.closest('select')) continue;
            scanned++;
            if (scanned > MAX_DISPLAY_SCAN) return 'SELECT_AMBIGUOUS';
            if (!isVisible(element)) continue;
            if (normalize(element.textContent) !== text) continue;
            // 内側の要素だけで数える（同じ文字を持つ入れ子の wrapper を重複計上しない）。
            if (hasMatchingChild(element, text)) continue;
            matches++;
          }
          if (matches !== 1) return 'SELECT_NOT_VISIBLE';
          return null;
        }
    """.trimIndent()

    /**
     * 固定スクリプト。対象 URL・表示状態・`.mainContent` を確認したうえで、
     * 選択中の `select` の `selectedOptions` の表示文字だけを返す。
     * 候補が 1 つに定まらない場合は ok=false とエラーコードを返す。
     */
    internal val SCRIPT: String = """
        (function () {
          'use strict';
          $pageAndContentGuardJs

          function fail(code) {
            return JSON.stringify({ ok: false, error: code, candidates: [], warnings: [] });
          }

          try {
            var pageFailure = pageError();
            if (pageFailure) return fail(pageFailure);

            var contentInfo = visibleMainContent();
            if (contentInfo.error) return fail(contentInfo.error);
            var content = contentInfo.content;

            var selects = content.querySelectorAll('select');
            if (!selects || selects.length === 0) return fail('SELECT_NOT_FOUND');
            if (selects.length > MAX_SELECTS) return fail('SELECT_LIMIT_EXCEEDED');

            var candidateSelect = null;
            var candidateText = null;
            var warnings = [];

            for (var i = 0; i < selects.length; i++) {
              var selection = selectionOf(selects[i]);
              if (selection.count === 0) continue;
              // 複数選択、または選択のある select が複数ある場合は年度・学期を
              // 一意に決められないため、推測せず拒否する。
              if (selection.count !== 1) return fail('SELECT_AMBIGUOUS');
              if (candidateSelect !== null) return fail('SELECT_AMBIGUOUS');
              if (!selection.text) return fail('UNSUPPORTED_OPTION');
              candidateSelect = selects[i];
              candidateText = selection.text;
            }

            if (candidateSelect === null) return fail('NO_SELECTION');
            if (candidateText.length > MAX_TEXT_LENGTH) return fail('UNSUPPORTED_OPTION');

            // native select が可視の祖先を持つか、隠れている場合は
            // 選択中の表示文字と一致する可視要素が 1 つだけあることを確認する。
            var selectVisible = isVisible(candidateSelect) &&
              insideVisibleMainContent(candidateSelect);
            if (!selectVisible) {
              var mismatch = displayedTextMismatch(candidateSelect, candidateText);
              if (mismatch) return fail(mismatch);
              warnings.push('選択欄が非表示のため、表示中の値が一致することを確認しました。');
            }

            // 「2026年度 後期」形式だけを受け付ける（全角空白も可）。
            var parsed = /^(\d{4})\s*年度\s*(前期|後期)$/.exec(candidateText);
            if (!parsed) return fail('UNSUPPORTED_OPTION');
            var year = parseInt(parsed[1], 10);
            if (!(year >= MIN_YEAR && year <= MAX_YEAR)) return fail('YEAR_OUT_OF_RANGE');
            if (TERM_LABELS.indexOf(parsed[2]) < 0) return fail('UNSUPPORTED_OPTION');

            return JSON.stringify({
              ok: true,
              error: null,
              candidates: [
                { year: year, term: parsed[2], selectedText: candidateText }
              ],
              warnings: warnings
            });
          } catch (e) {
            return fail('SCRIPT_FAILED');
          }
        })();
    """.trimIndent()

    /**
     * WebView から返る evaluateJavascript の結果を検証する。
     *
     * 固定スクリプトは `JSON.stringify(...)` の文字列を返すため、
     * 二重に JSON 文字列になった場合も受け付ける。
     * 形式が不正、または値が想定外の場合は [ErrorCode.INVALID_RESULT] として扱う。
     */
    fun parseEvaluationResult(raw: String?): Result {
        val decoded = decode(raw)
            ?: return Result(null, emptyList(), ErrorCode.INVALID_RESULT)
        val root = runCatching { json.parseToJsonElement(decoded) as? JsonObject }.getOrNull()
            ?: return Result(null, emptyList(), ErrorCode.INVALID_RESULT)

        val warnings = root.stringArray("warnings").toMutableList()
        val okValue = root["ok"] as? JsonPrimitive
            ?: return Result(null, warnings, ErrorCode.INVALID_RESULT)
        if (okValue.isString) return Result(null, warnings, ErrorCode.INVALID_RESULT)
        val ok = okValue.booleanOrNull
            ?: return Result(null, warnings, ErrorCode.INVALID_RESULT)

        if (!ok) {
            val code = (root["error"] as? JsonPrimitive)?.contentOrNull
            return Result(null, warnings, ErrorCode.fromJsCode(code))
        }
        // ok=true とエラーコードが同時に返る矛盾した結果は成功扱いしない。
        val contradiction = (root["error"] as? JsonPrimitive)?.contentOrNull
        if (contradiction != null) {
            warnings += "読み取り結果が矛盾しているため取り込みません。"
            return Result(null, warnings, ErrorCode.INVALID_RESULT)
        }

        val rawCandidates = root["candidates"] as? JsonArray
            ?: return Result(null, warnings, ErrorCode.INVALID_RESULT)
        if (rawCandidates.isEmpty()) return Result(null, warnings, ErrorCode.NO_CANDIDATE)
        if (rawCandidates.size > 1) {
            warnings += "年度・学期の候補が${rawCandidates.size}件あるため確定できません。"
            return Result(null, warnings, ErrorCode.MULTIPLE_CANDIDATES)
        }

        val candidate = rawCandidates.first() as? JsonObject
            ?: return Result(null, warnings, ErrorCode.INVALID_RESULT)
        val year = candidate.nonStringInt("year")
            ?: return Result(null, warnings, ErrorCode.INVALID_RESULT)
        val termLabel = (candidate["term"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.contentOrNull
        val term = Term.fromLabel(termLabel)
            ?: return Result(null, warnings, ErrorCode.INVALID_RESULT)
        val selectedText = (candidate["selectedText"] as? JsonPrimitive)?.contentOrNull
            ?: return Result(null, warnings, ErrorCode.INVALID_RESULT)

        if (year !in MIN_YEAR..MAX_YEAR) {
            return Result(null, warnings, ErrorCode.YEAR_OUT_OF_RANGE)
        }
        // 表示文字と構造化した year/term が食い違う場合は、どちらかを推測せず拒否する。
        if (compact(selectedText) != compact("${year}年度 ${term.label}")) {
            warnings += "表示中の年度・学期と読み取り結果が一致しません。"
            return Result(null, warnings, ErrorCode.INVALID_RESULT)
        }

        return Result(AcademicContext(year, term), warnings, null)
    }

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

    private fun JsonObject.nonStringInt(key: String): Int? {
        val value = this[key] as? JsonPrimitive ?: return null
        if (value.isString) return null
        return value.intOrNull
    }

    private fun JsonObject.stringArray(key: String): List<String> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { element ->
            (element as? JsonPrimitive)?.contentOrNull
                ?.replace('\u00a0', ' ')
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }

    /** 照合用。全角/半角・空白の違いだけを揃え、文字そのものは変えない。 */
    private fun compact(value: String?): String =
        java.text.Normalizer.normalize(value.orEmpty(), java.text.Normalizer.Form.NFKC)
            .replace('\u00a0', ' ')
            .replace(Regex("\\s+"), "")
            .trim()
}
