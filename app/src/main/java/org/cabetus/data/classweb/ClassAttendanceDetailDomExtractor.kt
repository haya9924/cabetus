package org.cabetus.data.classweb

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * CLASS スマートフォン版「1科目の出欠詳細」から、固定スクリプトで対象DOMだけを読む。
 *
 * 対象は次の条件をすべて満たすページに限定する。
 * - `https` かつホストが `class.admin.tus.ac.jp` の完全一致
 * - ページが表示状態
 * - ページに可視の見出し（h1〜h4 / legend / caption / .ui-title などの") があり、
 *   「出席」または「出欠」を含む（実機では .mainContent の外にあるためページ全体から探す）
 * - `.mainContent` が一意に存在し、その中に
 *   `div[id$=jugyoKaisuTbl].ui-datatable.attendanceInfo` が一意に存在
 * - `table` と `tbody[id$=jugyoKaisuTbl_data]` を一意に特定でき、
 *   必須ヘッダー（回数・実施日・曜日時限・出欠・試験）が揃っている
 *
 * URL のパスは実機で確認できていないため許可リストにしない。代わりに、上記の
 * URL・見出し・テーブル構造を DOM で検証できた場合だけ読み取りボタンを出す。
 *
 * 読み取るのは対象テーブルのヘッダー・セルの textContent と、ページの可視見出しだけである。
 * ただしページ全体のテキストは読まず、プレビュー用の見出しは既知の固定ラベルに限る
 * （科目名・氏名などの画面固有テキストをプレビューへ持ち込まない）。
 * テーブルは Kotlin 側で再構築したサニタイズ済み HTML として返し、
 * [ClassAttendanceDetailParser] に渡す。
 * 科目コードは抽出しない（画面の 7 桁英数字を正式コードと推測しない）。
 * Cookie / localStorage / sessionStorage / IndexedDB / フォーム値 / hidden input /
 * ページ全体の HTML は参照しない。`addJavascriptInterface` は使わない。
 * 見出しなどの値はログへ出さない。
 */
object ClassAttendanceDetailDomExtractor {

    const val TARGET_HOST = "class.admin.tus.ac.jp"

    private val targetHostJsLiteral: String = "'$TARGET_HOST'"

    /** 既存パーサと同じ必須ヘッダー。ボタン表示と抽出で同じ条件を使う。 */
    private val REQUIRED_HEADERS: List<String> = ClassAttendanceDetailParser.REQUIRED_HEADERS

    private val requiredHeadersJsLiteral: String =
        REQUIRED_HEADERS.joinToString(separator = ", ") { "'$it'" }

    private const val CONTAINER_ID_SUFFIX = "jugyoKaisuTbl"
    private const val TBODY_ID_SUFFIX = "jugyoKaisuTbl_data"
    private const val MAX_ROWS = 500
    private const val MAX_CELL_LENGTH = 500

    /**
     * プレビューに表示してよい既知の固定見出し。
     * 実機で確認した「学生出欠状況確認」「出欠状況確認」だけを採用し、
     * これ以外の見出し文字列（科目名・氏名などを含み得る）は表示しない。
     */
    private val HEADING_LABELS: List<String> = listOf("学生出欠状況確認", "出欠状況確認")
    private val headingLabelsJsLiteral: String =
        HEADING_LABELS.joinToString(separator = ", ") { "'$it'" }

    /** 既知の見出しと一致しない場合に使う、画面固有情報を含まない代替ラベル。 */
    private const val HEADING_FALLBACK = "出欠詳細"

    /**
     * URL・表示状態・見出し・出席詳細テーブルを確認する共通スクリプト片。
     * 科目名・セル内容・科目コードは返さない。
     */
    private val pageAndTableGuardJs: String = """
        var TARGET_HOST = $targetHostJsLiteral;
        var REQUIRED_HEADERS = [$requiredHeadersJsLiteral];
        var CONTAINER_ID_SUFFIX = '$CONTAINER_ID_SUFFIX';
        var TBODY_ID_SUFFIX = '$TBODY_ID_SUFFIX';
        var MAX_ROWS = $MAX_ROWS;
        var MAX_CELL_LENGTH = $MAX_CELL_LENGTH;
        var HEADING_LABELS = [$headingLabelsJsLiteral];
        var HEADING_FALLBACK = '$HEADING_FALLBACK';

        function normalize(text) {
          return String(text == null ? '' : text)
            .replace(/\u00a0/g, ' ')
            .replace(/\s+/g, ' ')
            .trim();
        }

        function headerKey(text) {
          return normalize(text).replace(/\s+/g, '');
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

        function directCells(row) {
          var cells = [];
          var children = row.children || [];
          for (var i = 0; i < children.length; i++) {
            var tag = children[i].tagName ? children[i].tagName.toLowerCase() : '';
            if (tag === 'td' || tag === 'th') cells.push(children[i]);
          }
          return cells;
        }

        function hasAllRequiredHeaders(row) {
          var labels = {};
          var cells = directCells(row);
          for (var i = 0; i < cells.length; i++) {
            labels[headerKey(cells[i].textContent)] = true;
          }
          for (var h = 0; h < REQUIRED_HEADERS.length; h++) {
            if (!labels[headerKey(REQUIRED_HEADERS[h])]) return false;
          }
          return true;
        }

        function bestHeaderRow(table) {
          var candidates = table.querySelectorAll('thead tr');
          if (candidates.length === 0) {
            var allRows = table.querySelectorAll('tr');
            var thRows = [];
            for (var i = 0; i < allRows.length; i++) {
              if (allRows[i].querySelector('th')) thRows.push(allRows[i]);
            }
            candidates = thRows;
          }
          var best = null;
          var bestCount = -1;
          for (var c = 0; c < candidates.length; c++) {
            var labels = {};
            var cells = directCells(candidates[c]);
            for (var j = 0; j < cells.length; j++) {
              labels[headerKey(cells[j].textContent)] = true;
            }
            var count = 0;
            for (var h = 0; h < REQUIRED_HEADERS.length; h++) {
              if (labels[headerKey(REQUIRED_HEADERS[h])]) count++;
            }
            if (count > bestCount) {
              best = candidates[c];
              bestCount = count;
            }
          }
          return best;
        }

        // 実機では「学生出欠状況確認」「出欠状況確認」が .mainContent の外
        // （h 系 / .ui-title）にあるため、ページ全体から可視の見出しを探す。
        // 返すのは見出し要素だけで、ページ全体のテキストは扱わない。
        function headingElement(scope) {
          if (!scope || !scope.querySelectorAll) return null;
          var candidates = scope.querySelectorAll(
            'h1, h2, h3, h4, legend, caption, ' +
              '.ui-title, .title, .pageTitle, .headTitle'
          );
          for (var i = 0; i < candidates.length; i++) {
            var text = normalize(candidates[i].textContent);
            if (text && /出席|出欠/.test(text) && visible(candidates[i])) {
              return candidates[i];
            }
          }
          return null;
        }

        // .mainContent 内を優先し、無ければページ全体から可視の見出しを探す。
        function pageHeading(main) {
          return headingElement(main) || headingElement(document);
        }

        // プレビューへ返すのは既知の固定ラベルだけ。科目名・氏名などを含み得る
        // 生の見出し文字列は返さない。
        function headingLabel(heading) {
          var text = normalize(heading ? heading.textContent : '');
          for (var i = 0; i < HEADING_LABELS.length; i++) {
            if (text.indexOf(HEADING_LABELS[i]) >= 0) return HEADING_LABELS[i];
          }
          return HEADING_FALLBACK;
        }

        function checkCellSpans(table) {
          var rows = table.querySelectorAll('tr');
          for (var r = 0; r < rows.length; r++) {
            var cells = directCells(rows[r]);
            for (var c = 0; c < cells.length; c++) {
              if (cells[c].colSpan > 1 || cells[c].rowSpan > 1) {
                return 'UNSUPPORTED_CELL_SPAN';
              }
            }
          }
          return null;
        }

        // 表示中の出席詳細テーブルだけを返す。見出し・ヘッダー・tbody が想定と違えば失敗する。
        function attendanceTable() {
          var mains = document.querySelectorAll('.mainContent');
          if (mains.length === 0) return { error: 'MAIN_CONTENT_NOT_FOUND' };
          if (mains.length > 1) return { error: 'MAIN_CONTENT_AMBIGUOUS' };
          var main = mains[0];

          var heading = pageHeading(main);
          if (!heading) return { error: 'HEADING_NOT_FOUND' };

          var containers = [];
          var divs = main.querySelectorAll('div[id]');
          for (var i = 0; i < divs.length; i++) {
            var div = divs[i];
            var id = div.id || '';
            if (id.slice(-CONTAINER_ID_SUFFIX.length) === CONTAINER_ID_SUFFIX &&
                div.classList &&
                div.classList.contains('ui-datatable') &&
                div.classList.contains('attendanceInfo')) {
              containers.push(div);
            }
          }
          if (containers.length === 0) return { error: 'DATATABLE_NOT_FOUND' };
          if (containers.length > 1) return { error: 'DATATABLE_AMBIGUOUS' };
          var container = containers[0];

          if (!visible(container)) return { error: 'TABLE_NOT_VISIBLE' };

          var tables = container.querySelectorAll('table');
          if (tables.length === 0) return { error: 'TABLE_NOT_FOUND' };
          var table = null;
          if (tables.length === 1) {
            table = tables[0];
          } else {
            var matches = [];
            for (var t = 0; t < tables.length; t++) {
              var rows = tables[t].querySelectorAll('tr');
              for (var r = 0; r < rows.length; r++) {
                if (hasAllRequiredHeaders(rows[r])) {
                  matches.push(tables[t]);
                  break;
                }
              }
            }
            if (matches.length !== 1) return { error: 'TABLE_AMBIGUOUS' };
            table = matches[0];
          }

          if (!visible(table)) return { error: 'TABLE_NOT_VISIBLE' };

          var headerRow = bestHeaderRow(table);
          if (!headerRow) return { error: 'HEADER_ROW_NOT_FOUND' };
          if (!hasAllRequiredHeaders(headerRow)) return { error: 'HEADER_MISMATCH' };

          var spanError = checkCellSpans(table);
          if (spanError) return { error: spanError };

          var tbodies = [];
          var bodyCandidates = table.querySelectorAll('tbody');
          for (var b = 0; b < bodyCandidates.length; b++) {
            var tbodyId = bodyCandidates[b].id || '';
            if (tbodyId.slice(-TBODY_ID_SUFFIX.length) === TBODY_ID_SUFFIX) {
              tbodies.push(bodyCandidates[b]);
            }
          }
          if (tbodies.length === 0) return { error: 'TBODY_NOT_FOUND' };
          if (tbodies.length > 1) return { error: 'TBODY_AMBIGUOUS' };

          return {
            error: null,
            main: main,
            heading: heading,
            table: table,
            headerRow: headerRow,
            tbody: tbodies[0]
          };
        }
    """.trimIndent()

    enum class ErrorCode(val jsCode: String) {
        UNEXPECTED_PAGE("UNEXPECTED_PAGE"),
        PAGE_NOT_VISIBLE("PAGE_NOT_VISIBLE"),
        MAIN_CONTENT_NOT_FOUND("MAIN_CONTENT_NOT_FOUND"),
        MAIN_CONTENT_AMBIGUOUS("MAIN_CONTENT_AMBIGUOUS"),
        HEADING_NOT_FOUND("HEADING_NOT_FOUND"),
        DATATABLE_NOT_FOUND("DATATABLE_NOT_FOUND"),
        DATATABLE_AMBIGUOUS("DATATABLE_AMBIGUOUS"),
        TABLE_NOT_FOUND("TABLE_NOT_FOUND"),
        TABLE_AMBIGUOUS("TABLE_AMBIGUOUS"),
        TABLE_NOT_VISIBLE("TABLE_NOT_VISIBLE"),
        HEADER_ROW_NOT_FOUND("HEADER_ROW_NOT_FOUND"),
        HEADER_MISMATCH("HEADER_MISMATCH"),
        UNSUPPORTED_CELL_SPAN("UNSUPPORTED_CELL_SPAN"),
        TBODY_NOT_FOUND("TBODY_NOT_FOUND"),
        TBODY_AMBIGUOUS("TBODY_AMBIGUOUS"),
        TOO_LARGE("TOO_LARGE"),
        SCRIPT_FAILED("SCRIPT_FAILED"),
        INVALID_RESULT("INVALID_RESULT"),
        ;

        val userMessage: String
            get() = when (this) {
                UNEXPECTED_PAGE ->
                    "CLASS の出席詳細ページ（https・class.admin.tus.ac.jp）ではありません。" +
                        "CLASS の出欠詳細を開き直してください。"
                PAGE_NOT_VISIBLE ->
                    "出欠詳細が表示状態ではありません。CLASS の出欠詳細を前面に表示してから再試行してください。"
                MAIN_CONTENT_NOT_FOUND ->
                    "出席詳細の内容（.mainContent）が見つかりません。出欠詳細が表示されているか確認してください。"
                MAIN_CONTENT_AMBIGUOUS ->
                    "出席詳細の内容（.mainContent）を一意に特定できません。画面のレイアウトが想定と異なるため読み取りません。"
                HEADING_NOT_FOUND ->
                    "「出席」または「出欠」の見出しを確認できません。出欠詳細の画面か確認してください。"
                DATATABLE_NOT_FOUND ->
                    "出席詳細テーブルが見つかりません。出欠詳細が表示されているか確認してください。"
                DATATABLE_AMBIGUOUS ->
                    "出席詳細テーブルの候補が複数あります。画面のレイアウトが想定と異なるため読み取りません。"
                TABLE_NOT_FOUND ->
                    "出席詳細の表が見つかりません。出欠詳細が表示されているか確認してください。"
                TABLE_AMBIGUOUS ->
                    "出席詳細の表を一意に特定できません。画面のレイアウトが想定と異なるため読み取りません。"
                TABLE_NOT_VISIBLE ->
                    "出席詳細の表が画面に表示されていません。表示してから再試行してください。"
                HEADER_ROW_NOT_FOUND ->
                    "出席詳細のヘッダー行が見つかりません。画面のレイアウトが想定と異なるため読み取りません。"
                HEADER_MISMATCH ->
                    "出席詳細の必須ヘッダー（回数・実施日・曜日時限・出欠・試験）を確認できません。読み取りません。"
                UNSUPPORTED_CELL_SPAN ->
                    "セル結合を含む出席詳細テーブルは安全に列を対応付けられないため読み取りません。"
                TBODY_NOT_FOUND ->
                    "出席詳細のデータ行（tbody）が見つかりません。画面のレイアウトが想定と異なるため読み取りません。"
                TBODY_AMBIGUOUS ->
                    "出席詳細のデータ行（tbody）を一意に特定できません。画面のレイアウトが想定と異なるため読み取りません。"
                TOO_LARGE ->
                    "出席詳細の行数またはセル文字数が想定より大きいため読み取りません。"
                SCRIPT_FAILED ->
                    "出欠詳細の読み取りに失敗しました。ページを再読み込みしてから再試行してください。"
                INVALID_RESULT ->
                    "出欠詳細の読み取り結果を確認できませんでした。ページを再読み込みしてから再試行してください。"
            }

        companion object {
            fun fromJsCode(code: String?): ErrorCode =
                entries.firstOrNull { it.jsCode == code } ?: INVALID_RESULT
        }
    }

    /** 明示ボタンで読み取った1科目分の内容。表示用プレビューにだけ使う。 */
    data class ExtractedDetail(
        val heading: String?,
        /** Kotlin 側で再構築した、パース可能な対象テーブルだけの HTML。 */
        val tableHtml: String,
        val warnings: List<String>,
    )

    /**
     * 表示中の詳細が、直前に開いた一覧行の科目と一致するかを確認した結果。
     * 一致を確認できなければ、呼び出し側は結果を「暫定」として扱い、保存しない。
     */
    data class IdentityCheck(
        val verified: Boolean,
        val error: ErrorCode?,
    ) {
        val isSuccess: Boolean get() = error == null
    }

    data class Result(
        val detail: ExtractedDetail?,
        val warnings: List<String>,
        val error: ErrorCode?,
    ) {
        val isSuccess: Boolean get() = error == null && detail != null
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * ボタン表示・クリック前再検証に使う固定スクリプト。
     * 成功/失敗とエラーコードだけを返し、見出し・科目コード・セル内容は返さない。
     */
    internal val VERIFY_SCRIPT: String = """
        (function () {
          'use strict';
          $pageAndTableGuardJs

          function result(ok, code) {
            return JSON.stringify({ ok: ok, error: code });
          }

          try {
            var pageFailure = pageError();
            if (pageFailure) return result(false, pageFailure);

            var info = attendanceTable();
            if (info.error) return result(false, info.error);

            return result(true, null);
          } catch (e) {
            return result(false, 'SCRIPT_FAILED');
          }
        })();
    """.trimIndent()

    /**
     * 固定スクリプト。URL・表示状態・見出し・テーブル構造を再検証したうえで、
     * 対象テーブルの textContent だけからサニタイズ済み HTML を組み立て、
     * テーブル外の見出しを返す。科目コードは画面の見た目から推測しない。
     */
    internal val SCRIPT: String = """
        (function () {
          'use strict';
          $pageAndTableGuardJs

          function fail(code, warnings) {
            return JSON.stringify({
              ok: false,
              error: code,
              heading: null,
              tableHtml: null,
              warnings: warnings || []
            });
          }

          function escapeHtml(text) {
            return String(text)
              .replace(/&/g, '&amp;')
              .replace(/</g, '&lt;')
              .replace(/>/g, '&gt;')
              .replace(/"/g, '&quot;')
              .replace(/'/g, '&#39;');
          }

          function cellText(cell) {
            var text = normalize(cell.textContent);
            if (text.length > MAX_CELL_LENGTH) return null;
            return text;
          }

          try {
            var pageFailure = pageError();
            if (pageFailure) return fail(pageFailure);

            var info = attendanceTable();
            if (info.error) return fail(info.error);

            var warnings = [];
            var headerCells = directCells(info.headerRow);
            var headerTexts = [];
            for (var h = 0; h < headerCells.length; h++) {
              var headerText = cellText(headerCells[h]);
              if (headerText === null) return fail('TOO_LARGE');
              headerTexts.push(headerText);
            }

            var rows = info.tbody.children || [];
            if (rows.length > MAX_ROWS) return fail('TOO_LARGE');

            var tableHtml = '<table><thead><tr>';
            for (var t = 0; t < headerTexts.length; t++) {
              tableHtml += '<th>' + escapeHtml(headerTexts[t]) + '</th>';
            }
            tableHtml += '</tr></thead><tbody id="extracted:jugyoKaisuTbl_data">';

            var dataRowCount = 0;
            for (var r = 0; r < rows.length; r++) {
              if (!rows[r].tagName || rows[r].tagName.toLowerCase() !== 'tr') continue;
              var cells = directCells(rows[r]);
              tableHtml += '<tr>';
              for (var c = 0; c < cells.length; c++) {
                var text = cellText(cells[c]);
                if (text === null) return fail('TOO_LARGE');
                tableHtml += '<td>' + escapeHtml(text) + '</td>';
              }
              tableHtml += '</tr>';
              dataRowCount++;
            }
            tableHtml += '</tbody></table>';

            if (dataRowCount > MAX_ROWS) return fail('TOO_LARGE');

            var headingText = headingLabel(info.heading);

            return JSON.stringify({
              ok: true,
              error: null,
              heading: headingText,
              tableHtml: tableHtml,
              warnings: warnings
            });
          } catch (e) {
            return fail('SCRIPT_FAILED');
          }
        })();
    """.trimIndent()

    /**
     * 表示中の詳細が、指定した一覧行の科目と確実に一致するかを確認する固定スクリプトを作る。
     *
     * 一致の判定は、詳細ページの `.mainContent` 内に「行の科目名と完全一致する
     * 可視テキスト」がちょうど 1 つだけ存在することでのみ行う。
     * 科目名は JS 内で照合に使うだけで、結果には真偽値だけを返す（生の文字列は返さない）。
     * 一致を確認できなければ false を返し、抽出結果は暫定として扱う。
     * 詳細テーブル自体を確認できない場合はエラーコードを返す（クリックはしない）。
     */
    internal fun buildIdentityScript(rowLabel: String): String {
        val label = rowLabel.trim()
        require(label.isNotEmpty() && label.length <= MAX_IDENTITY_LABEL_LENGTH) {
            "unexpected row label"
        }
        // JSON 文字列リテラルとして安全に埋め込む（引用符・改行・Unicode をエスケープする）。
        val labelLiteral = JsonPrimitive(label).toString()

        return """
            (function () {
              'use strict';
              $pageAndTableGuardJs

              var ROW_LABEL = $labelLiteral;

              function result(ok, code, identity) {
                return JSON.stringify({
                  ok: ok,
                  error: code,
                  identity: identity === true
                });
              }

              // 子要素の textContent を親が重複して含むため、直接のテキストノードだけを比べる。
              function directText(element) {
                var text = '';
                var nodes = element.childNodes || [];
                for (var i = 0; i < nodes.length; i++) {
                  if (nodes[i].nodeType === 3) {
                    text += String(nodes[i].nodeValue == null ? '' : nodes[i].nodeValue);
                  }
                }
                return normalize(text);
              }

              function matchingElements(scope) {
                var count = 0;
                if (!scope || !scope.querySelectorAll) return count;
                var elements = scope.querySelectorAll('*');
                for (var i = 0; i < elements.length; i++) {
                  if (directText(elements[i]) === ROW_LABEL && visible(elements[i])) {
                    count++;
                  }
                }
                return count;
              }

              try {
                var pageFailure = pageError();
                if (pageFailure) return result(false, pageFailure, false);

                var info = attendanceTable();
                if (info.error) return result(false, info.error, false);

                // 詳細テーブルは表示中でも、科目名の一致までは保証できないことがある。
                // その場合もエラーにはせず identity=false を返し、呼び出し側が暫定表示する。
                var matches = matchingElements(info.main);
                return result(true, null, matches === 1);
              } catch (e) {
                return result(false, 'SCRIPT_FAILED', false);
              }
            })();
        """.trimIndent()
    }

    /**
     * 一覧の行ラベルを照合に使うときの上限。
     * 抽出側のラベル上限と同じ値にして、長すぎる文字列を JS へ埋め込まない。
     */
    private const val MAX_IDENTITY_LABEL_LENGTH = 200

    /** 合致確認スクリプトの戻り値を検証する。形式不正は [ErrorCode.INVALID_RESULT]。 */
    fun parseIdentityResult(raw: String?): IdentityCheck {
        val decoded = decode(raw) ?: return IdentityCheck(false, ErrorCode.INVALID_RESULT)
        val root = runCatching { json.parseToJsonElement(decoded) as? JsonObject }.getOrNull()
            ?: return IdentityCheck(false, ErrorCode.INVALID_RESULT)

        val okValue = root["ok"] as? JsonPrimitive ?: return IdentityCheck(
            false,
            ErrorCode.INVALID_RESULT,
        )
        if (okValue.isString) return IdentityCheck(false, ErrorCode.INVALID_RESULT)
        val errorCode = (root["error"] as? JsonPrimitive)?.contentOrNull
        if (okValue.booleanOrNull != true) {
            return IdentityCheck(false, ErrorCode.fromJsCode(errorCode))
        }
        if (errorCode != null) return IdentityCheck(false, ErrorCode.INVALID_RESULT)

        val identity = root["identity"] as? JsonPrimitive ?: return IdentityCheck(
            false,
            ErrorCode.INVALID_RESULT,
        )
        if (identity.isString) return IdentityCheck(false, ErrorCode.INVALID_RESULT)
        return IdentityCheck(
            verified = identity.booleanOrNull == true,
            error = null,
        )
    }

    /**
     * WebView から返る evaluateJavascript の結果を検証する。
     * 形式が不正、または想定外の値は [ErrorCode.INVALID_RESULT] として扱い、
     * 読み取った内容を保存対象にしない。
     */
    fun parseEvaluationResult(raw: String?): Result {
        val decoded = decode(raw) ?: return Result(null, emptyList(), ErrorCode.INVALID_RESULT)
        val root = runCatching { json.parseToJsonElement(decoded) as? JsonObject }.getOrNull()
            ?: return Result(null, emptyList(), ErrorCode.INVALID_RESULT)

        val warnings = root.stringArray("warnings")
        val ok = (root["ok"] as? JsonPrimitive)?.booleanOrNull == true
        if (!ok) {
            val code = (root["error"] as? JsonPrimitive)?.contentOrNull
            return Result(null, warnings, ErrorCode.fromJsCode(code))
        }

        val tableHtml = (root["tableHtml"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotBlank() && it.length <= 2_000_000 }
            ?: return Result(null, warnings, ErrorCode.INVALID_RESULT)
        val heading = (root["heading"] as? JsonPrimitive)?.contentOrNull
            ?.replace('\u00a0', ' ')
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.let { sanitizeHeading(it) }

        return Result(
            detail = ExtractedDetail(
                heading = heading,
                tableHtml = tableHtml,
                warnings = warnings,
            ),
            warnings = warnings,
            error = null,
        )
    }

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
     * プレビュー用の見出しを既知の固定ラベルに限定する。
     * 想定外の文字列（科目名・氏名などを含み得る）は [HEADING_FALLBACK] に置き換え、
     * 画面固有のテキストを UI へ持ち込まない。
     */
    private fun sanitizeHeading(text: String): String =
        HEADING_LABELS.firstOrNull { text.contains(it) } ?: HEADING_FALLBACK

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
                ?.takeIf { it.isNotEmpty() }
        }
}
