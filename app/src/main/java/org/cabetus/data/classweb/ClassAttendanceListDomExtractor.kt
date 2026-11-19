package org.cabetus.data.classweb

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * CLASS スマートフォン版「出欠一覧」の科目行と、詳細画面の「戻る」ボタンだけを、
 * 固定スクリプトで扱う。
 *
 * 実機で確認できた構造（2025-11 時点の調査メモ）:
 * - `.mainContent` の中に `ul[id$=_list]` が一意に存在する
 * - 各項目は `ul > li > a.ui-commandlink`（実機では 13 件）
 * - 各リンクの `onclick` は `PrimeFaces.addSubmitParam(...)` を含み、
 *   クリックすると同じ URL のまま詳細 DOM に切り替わる
 * - 詳細画面の `.mainContent button` のうち、可視で文字が「戻る」のものが一覧へ戻す
 *
 * ここで行うのは、表示中の一覧行の特定・既存リンクのクリック・「戻る」ボタンのクリックだけである。
 * 出欠登録など副作用のあるボタンは対象にしない。Cookie / localStorage / sessionStorage /
 * IndexedDB / フォーム値 / hidden input / ページ全体の HTML は参照しない。
 * `addJavascriptInterface` は使わない。
 *
 * 一覧の行対応は、行番号だけでなくリンク文字列と `onclick` から作る短いトークンで検証する。
 * 巡回中に一覧の並び・件数・リンク先が変わった場合は、別の科目を読まないよう停止する。
 * `onclick` の生文字列は Android 側へ返さない（トークンだけを返す）。
 *
 * 一覧以外の画面で誤って巡回を開始しないよう、次のすべてを DOM で確認する。
 * - `.mainContent` が一意で、その中（またはページ）に見出し「出席」「出欠」がある
 * - 時間割ページの `.rishuArea` が無い
 * - 出欠詳細テーブル（`div[id$=jugyoKaisuTbl].ui-datatable.attendanceInfo`）が
 *   表示されていない
 * - `ul[id$=_list]` が一意で、`li > a.ui-commandlink` と PrimeFaces の onclick が揃う
 *
 * ページ送り（ページネーション）の構造は実機で未確認のため、このクラスは現在表示中の
 * 一覧だけを対象にする。次ページの存在確認・自動送りは行わない。
 */
object ClassAttendanceListDomExtractor {

    const val TARGET_HOST = "class.admin.tus.ac.jp"

    private const val LIST_ID_SUFFIX = "_list"
    private const val DETAIL_ID_SUFFIX = "jugyoKaisuTbl"
    private const val MAX_LABEL_LENGTH = 200

    /** 巡回で扱う一覧行の上限。無制限ループを防ぐため固定する。 */
    const val MAX_ROWS = 200

    /** 一覧の件数・並びが実機の想定（13 件）から大きく外れた場合の安全上限。 */
    const val EXPECTED_MAX_ROWS = 200

    private val targetHostJsLiteral: String = "'$TARGET_HOST'"

    /**
     * 詳細画面から一覧へ戻る直前の検証で使う必須ヘッダー。
     * 出欠詳細の抽出と同じ定義を共有し、片方だけ更新されて食い違わないようにする。
     */
    private val requiredHeadersJsLiteral: String =
        ClassAttendanceDetailParser.REQUIRED_HEADERS.joinToString(separator = ", ") { "'$it'" }

    private val tokenRegex = Regex("^[0-9a-f]{8}$")

    /**
     * URL・表示状態・`.mainContent`・`ul[id$=_list]`・各 `li > a.ui-commandlink` を
     * 確認する共通スクリプト片。
     *
     * 行の文字列・トークンは [listInfo] が返すが、Cookie・入力値などは参照しない。
     */
    private val listGuardJs: String = """
        var TARGET_HOST = $targetHostJsLiteral;
        var LIST_ID_SUFFIX = '$LIST_ID_SUFFIX';
        var DETAIL_ID_SUFFIX = '$DETAIL_ID_SUFFIX';
        var DETAIL_TBODY_ID_SUFFIX = DETAIL_ID_SUFFIX + '_data';
        var REQUIRED_HEADERS = [$requiredHeadersJsLiteral];
        var MAX_ROWS = $MAX_ROWS;
        var MAX_LABEL_LENGTH = $MAX_LABEL_LENGTH;

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

        // 一覧ページらしい見出し（出席・出欠）が可視で存在するか。
        // 同じ CLASS 内の別画面にある ul[id$=_list] を一覧と誤認しないためのガード。
        function attendanceHeading(scope) {
          if (!scope || !scope.querySelectorAll) return false;
          var candidates = scope.querySelectorAll(
            'h1, h2, h3, h4, legend, caption, ' +
              '.ui-title, .title, .pageTitle, .headTitle'
          );
          for (var i = 0; i < candidates.length; i++) {
            var text = normalize(candidates[i].textContent);
            if (text && /出席|出欠/.test(text) && visible(candidates[i])) {
              return true;
            }
          }
          return false;
        }

        function hasAttendanceHeading(main) {
          return attendanceHeading(main) || attendanceHeading(document);
        }

        // 出欠詳細テーブルが表示中か。詳細画面では一覧を巡回しない。
        // 同一 document 内で一覧と詳細が一時的に併存する遷移中も検出できる。
        function detailVisible() {
          var mains = document.querySelectorAll('.mainContent');
          if (mains.length !== 1) return false;
          var divs = mains[0].querySelectorAll('div[id]');
          for (var i = 0; i < divs.length; i++) {
            var div = divs[i];
            var id = div.id || '';
            if (id.slice(-DETAIL_ID_SUFFIX.length) === DETAIL_ID_SUFFIX &&
                div.classList &&
                div.classList.contains('ui-datatable') &&
                div.classList.contains('attendanceInfo') &&
                visible(div)) {
              return true;
            }
          }
          return false;
        }

        function headerKey(text) {
          return normalize(text).replace(/\s+/g, '');
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

        function hasCellSpans(table) {
          var rows = table.querySelectorAll('tr');
          for (var r = 0; r < rows.length; r++) {
            var cells = directCells(rows[r]);
            for (var c = 0; c < cells.length; c++) {
              if (cells[c].colSpan > 1 || cells[c].rowSpan > 1) return true;
            }
          }
          return false;
        }

        // 「戻る」を押す直前に、同じ JS 実行内で出欠詳細だけが表示されていることを確認する。
        // 見出し・詳細テーブルの一意性・必須ヘッダー・データ行が揃わなければ、
        // 別画面の「戻る」を誤って押さないようエラーだけを返す（クリックはしない）。
        function detailTableInfo() {
          if (!detailVisible()) return { error: 'DETAIL_NOT_VERIFIED' };

          var mains = document.querySelectorAll('.mainContent');
          if (mains.length !== 1) return { error: 'DETAIL_NOT_VERIFIED' };
          var main = mains[0];
          if (!attendanceHeading(main) && !attendanceHeading(document)) {
            return { error: 'DETAIL_NOT_VERIFIED' };
          }

          var containers = [];
          var divs = main.querySelectorAll('div[id]');
          for (var i = 0; i < divs.length; i++) {
            var div = divs[i];
            var id = div.id || '';
            if (id.slice(-DETAIL_ID_SUFFIX.length) === DETAIL_ID_SUFFIX &&
                div.classList &&
                div.classList.contains('ui-datatable') &&
                div.classList.contains('attendanceInfo') &&
                visible(div)) {
              containers.push(div);
            }
          }
          if (containers.length !== 1) return { error: 'DETAIL_NOT_VERIFIED' };
          var container = containers[0];
          if (!visible(container)) return { error: 'DETAIL_NOT_VERIFIED' };

          var tables = container.querySelectorAll('table');
          var table = null;
          if (tables.length === 1) {
            table = tables[0];
          } else if (tables.length > 1) {
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
            if (matches.length !== 1) return { error: 'DETAIL_NOT_VERIFIED' };
            table = matches[0];
          }
          if (!table || !visible(table)) return { error: 'DETAIL_NOT_VERIFIED' };

          var headerRow = bestHeaderRow(table);
          if (!headerRow || !hasAllRequiredHeaders(headerRow)) {
            return { error: 'DETAIL_NOT_VERIFIED' };
          }
          if (hasCellSpans(table)) return { error: 'DETAIL_NOT_VERIFIED' };

          var tbodies = [];
          var bodyCandidates = table.querySelectorAll('tbody');
          for (var b = 0; b < bodyCandidates.length; b++) {
            var tbodyId = bodyCandidates[b].id || '';
            if (tbodyId.slice(-DETAIL_TBODY_ID_SUFFIX.length) === DETAIL_TBODY_ID_SUFFIX) {
              tbodies.push(bodyCandidates[b]);
            }
          }
          if (tbodies.length !== 1) return { error: 'DETAIL_NOT_VERIFIED' };

          return { error: null };
        }

        // 詳細画面であることを確認するときの一覧可視判定。
        // 一覧が可視なら true、非表示または存在しなければ false、
        // 判定できない（構造が想定外）なら null を返す。
        function listVisibleForDetailGuard() {
          var info = listCandidates();
          if (!info.error) return true;
          if (info.error === 'LIST_NOT_VISIBLE') return false;
          if (info.error === 'LIST_NOT_FOUND') return false;
          return null;
        }

        // 行対応用の短いトークン。リンク文字列と onclick の組み合わせを固定ハッシュにする。
        // onclick の生文字列（内部 ID などを含み得る）は返さない。
        function tokenOf(label, onclick) {
          var text = String(label) + '\n' + String(onclick);
          var hash = 2166136261;
          for (var i = 0; i < text.length; i++) {
            hash ^= text.charCodeAt(i);
            hash = (hash * 16777619) >>> 0;
          }
          return ('00000000' + hash.toString(16)).slice(-8);
        }

        // ul[id$=_list] の直下 li にある a.ui-commandlink だけを一覧行として扱う。
        // ここでは見出し・詳細表示のガードを掛けない（一覧と詳細が一時的に併存する
        // 遷移中も、どちらが表示されているかを個別に判定できるようにするため）。
        function listCandidates() {
          var mains = document.querySelectorAll('.mainContent');
          if (mains.length === 0) return { error: 'MAIN_CONTENT_NOT_FOUND' };
          if (mains.length > 1) return { error: 'MAIN_CONTENT_AMBIGUOUS' };
          var main = mains[0];

          // 時間割ページや別画面のリストを出欠一覧と誤認しない。
          if (document.querySelector('.rishuArea')) return { error: 'UNEXPECTED_PAGE' };

          var lists = [];
          var uls = main.querySelectorAll('ul[id]');
          for (var u = 0; u < uls.length; u++) {
            var ulId = uls[u].id || '';
            if (ulId.slice(-LIST_ID_SUFFIX.length) === LIST_ID_SUFFIX) {
              lists.push(uls[u]);
            }
          }
          if (lists.length === 0) return { error: 'LIST_NOT_FOUND' };
          if (lists.length > 1) return { error: 'LIST_AMBIGUOUS' };
          var list = lists[0];
          if (!visible(list)) return { error: 'LIST_NOT_VISIBLE' };

          var rows = [];
          var children = list.children || [];
          for (var i = 0; i < children.length; i++) {
            var li = children[i];
            var liTag = li.tagName ? li.tagName.toLowerCase() : '';
            if (liTag !== 'li') return { error: 'LIST_ITEM_MISMATCH' };

            var anchors = [];
            var liChildren = li.children || [];
            for (var j = 0; j < liChildren.length; j++) {
              var anchor = liChildren[j];
              var anchorTag = anchor.tagName ? anchor.tagName.toLowerCase() : '';
              if (anchorTag === 'a' && anchor.classList &&
                  anchor.classList.contains('ui-commandlink')) {
                anchors.push(anchor);
              }
            }
            if (anchors.length !== 1) return { error: 'LIST_ITEM_MISMATCH' };

            var link = anchors[0];
            if (!visible(link)) return { error: 'LIST_ITEM_NOT_VISIBLE' };
            var label = normalize(link.textContent);
            if (!label || label.length > MAX_LABEL_LENGTH) {
              return { error: 'LIST_ITEM_MISMATCH' };
            }
            var onclick = link.getAttribute('onclick') || '';
            if (!/PrimeFaces\s*\.\s*addSubmitParam\s*\(/i.test(onclick)) {
              return { error: 'LIST_ITEM_MISMATCH' };
            }
            rows.push({
              index: i + 1,
              label: label,
              token: tokenOf(label, onclick),
              link: link
            });
          }

          if (rows.length === 0) return { error: 'LIST_EMPTY' };
          if (rows.length > MAX_ROWS) return { error: 'TOO_LARGE' };
          return { error: null, main: main, list: list, rows: rows };
        }

        // 巡回で使う一覧判定。見出しと「詳細が表示されていないこと」も必須にする。
        function listInfo() {
          var info = listCandidates();
          if (info.error) return info;
          if (!hasAttendanceHeading(info.main)) return { error: 'LIST_HEADING_NOT_FOUND' };
          if (detailVisible()) return { error: 'DETAIL_VISIBLE' };
          return info;
        }

        // .mainContent 内で、可視かつ文字が完全一致で「戻る」の button だけを対象にする。
        // 出欠登録など、他の副作用のあるボタンは決してクリックしない。
        function backButton() {
          var mains = document.querySelectorAll('.mainContent');
          if (mains.length === 0) return { error: 'MAIN_CONTENT_NOT_FOUND' };
          if (mains.length > 1) return { error: 'MAIN_CONTENT_AMBIGUOUS' };

          var buttons = mains[0].querySelectorAll('button');
          var matches = [];
          for (var i = 0; i < buttons.length; i++) {
            if (normalize(buttons[i].textContent) === '戻る' && visible(buttons[i])) {
              matches.push(buttons[i]);
            }
          }
          if (matches.length === 0) return { error: 'BACK_NOT_FOUND' };
          if (matches.length > 1) return { error: 'BACK_AMBIGUOUS' };
          return { error: null, button: matches[0] };
        }
    """.trimIndent()

    /** 一覧の1行。index は表示順（1始まり）。token は行対応の検証用。 */
    data class Row(
        val index: Int,
        val label: String,
        val token: String,
    )

    enum class ErrorCode(val jsCode: String) {
        UNEXPECTED_PAGE("UNEXPECTED_PAGE"),
        PAGE_NOT_VISIBLE("PAGE_NOT_VISIBLE"),
        MAIN_CONTENT_NOT_FOUND("MAIN_CONTENT_NOT_FOUND"),
        MAIN_CONTENT_AMBIGUOUS("MAIN_CONTENT_AMBIGUOUS"),
        LIST_NOT_FOUND("LIST_NOT_FOUND"),
        LIST_AMBIGUOUS("LIST_AMBIGUOUS"),
        LIST_NOT_VISIBLE("LIST_NOT_VISIBLE"),
        LIST_HEADING_NOT_FOUND("LIST_HEADING_NOT_FOUND"),
        DETAIL_VISIBLE("DETAIL_VISIBLE"),
        LIST_EMPTY("LIST_EMPTY"),
        LIST_ITEM_MISMATCH("LIST_ITEM_MISMATCH"),
        LIST_ITEM_NOT_VISIBLE("LIST_ITEM_NOT_VISIBLE"),
        LIST_CHANGED("LIST_CHANGED"),
        TOO_LARGE("TOO_LARGE"),
        CLICK_FAILED("CLICK_FAILED"),
        BACK_NOT_FOUND("BACK_NOT_FOUND"),
        BACK_AMBIGUOUS("BACK_AMBIGUOUS"),
        BACK_FAILED("BACK_FAILED"),
        DETAIL_NOT_VERIFIED("DETAIL_NOT_VERIFIED"),
        LIST_VISIBLE("LIST_VISIBLE"),
        SCRIPT_FAILED("SCRIPT_FAILED"),
        INVALID_RESULT("INVALID_RESULT"),
        TIMEOUT("TIMEOUT"),
        ;

        val userMessage: String
            get() = when (this) {
                UNEXPECTED_PAGE ->
                    "CLASS の出欠一覧ページ（https・class.admin.tus.ac.jp）ではありません。" +
                        "出欠一覧を開き直してください。"
                PAGE_NOT_VISIBLE ->
                    "出欠一覧が表示状態ではありません。CLASS の出欠一覧を前面に表示してから再試行してください。"
                MAIN_CONTENT_NOT_FOUND ->
                    "出欠一覧の内容（.mainContent）が見つかりません。一覧が表示されているか確認してください。"
                MAIN_CONTENT_AMBIGUOUS ->
                    "出欠一覧の内容（.mainContent）を一意に特定できません。画面のレイアウトが想定と異なるため中止しました。"
                LIST_NOT_FOUND ->
                    "出欠一覧（ul[id$=_list]）が見つかりません。CLASS の出欠一覧が表示されているか確認してください。"
                LIST_AMBIGUOUS ->
                    "出欠一覧（ul[id$=_list]）の候補が複数あります。画面のレイアウトが想定と異なるため中止しました。"
                LIST_NOT_VISIBLE ->
                    "出欠一覧が画面に表示されていません。一覧を前面に表示してから再試行してください。"
                LIST_HEADING_NOT_FOUND ->
                    "CLASS の出欠一覧らしい見出し（出席・出欠）を確認できません。" +
                        "出欠一覧を開き直してから再試行してください。"
                DETAIL_VISIBLE ->
                    "出欠詳細が表示されています。一覧に戻ってから巡回を開始してください。"
                LIST_EMPTY ->
                    "出欠一覧に科目がありません。一覧が表示されているか確認してください。"
                LIST_ITEM_MISMATCH ->
                    "出欠一覧の行構造（li > a.ui-commandlink と onclick）を確認できません。" +
                        "画面のレイアウトが想定と異なるため巡回しません。"
                LIST_ITEM_NOT_VISIBLE ->
                    "出欠一覧に表示されていない科目行があります。一覧を表示してから再試行してください。"
                LIST_CHANGED ->
                    "巡回中に出欠一覧の件数・並び・リンク先が変わりました。" +
                        "別の科目を読み取らないよう停止しました。"
                TOO_LARGE ->
                    "出欠一覧の行数が想定より大きいため巡回しません。"
                CLICK_FAILED ->
                    "出欠一覧の科目リンクを開けませんでした。ページを再読み込みしてから再試行してください。"
                BACK_NOT_FOUND ->
                    "出欠詳細の「戻る」ボタンを確認できません。一覧へ戻れないため巡回を停止しました。"
                BACK_AMBIGUOUS ->
                    "出欠詳細の「戻る」ボタンが複数あります。誤操作を避けるため巡回を停止しました。"
                BACK_FAILED ->
                    "出欠詳細の「戻る」ボタンを押せませんでした。一覧へ戻れないため巡回を停止しました。"
                DETAIL_NOT_VERIFIED ->
                    "出欠詳細の見出し・表・必須ヘッダーを確認できないため、「戻る」を押さずに巡回を停止しました。"
                LIST_VISIBLE ->
                    "出欠一覧が表示されている画面のため、別画面の「戻る」を押さずに巡回を停止しました。"
                SCRIPT_FAILED ->
                    "出欠一覧の確認に失敗しました。ページを再読み込みしてから再試行してください。"
                INVALID_RESULT ->
                    "出欠一覧の確認結果を確認できませんでした。ページを再読み込みしてから再試行してください。"
                TIMEOUT ->
                    "時間内に画面が切り替わりませんでした。別の科目を読み取らないよう巡回を停止しました。"
            }

        companion object {
            fun fromJsCode(code: String?): ErrorCode =
                entries.firstOrNull { it.jsCode == code } ?: INVALID_RESULT
        }
    }

    data class Roster(
        val rows: List<Row>,
        val warnings: List<String>,
        val error: ErrorCode?,
    ) {
        val isSuccess: Boolean get() = error == null
    }

    /**
     * 現在の document が一覧だけか、詳細だけかを判定した結果。
     * 非同期の画面切り替えでは、一覧と詳細が一時的に併存したり、
     * 前の DOM が残ったりするため、実際の表示状態を毎回確認する。
     */
    data class ScreenState(
        val listReady: Boolean,
        val detailPresent: Boolean,
        /** 一覧として扱えなかった理由。詳細表示中は [ErrorCode.DETAIL_VISIBLE]。 */
        val listError: ErrorCode?,
        /** URL・表示状態の検証に失敗した場合の理由。 */
        val error: ErrorCode?,
    ) {
        val isValid: Boolean get() = error == null
        /** 一覧だけが表示されている（巡回の開始・一覧への復帰条件）。 */
        val isListOnly: Boolean get() = isValid && listReady && !detailPresent
        /** 詳細だけが表示されている（詳細の読み取り条件）。 */
        val isDetailOnly: Boolean get() = isValid && detailPresent && !listReady
    }

    /**
     * ページ送り（ページネーション）の構造は実機で未確認のため、
     * 現在表示中の一覧だけを対象にすることを UI と文書で明示する。
     */
    const val PAGINATION_NOTICE =
        "ページ送り（ページネーション）は未確認のため、現在表示中の一覧だけを巡回します。" +
            "次ページの自動送りは行いません。"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 一覧ページの表示判定に使う固定スクリプト。
     * 行の文字列やトークンは返さず、成功/失敗とエラーコードだけを返す。
     */
    internal val LIST_VERIFY_SCRIPT: String = """
        (function () {
          'use strict';
          $listGuardJs

          function result(ok, code) {
            return JSON.stringify({ ok: ok, error: code });
          }

          try {
            var pageFailure = pageError();
            if (pageFailure) return result(false, pageFailure);

            var info = listInfo();
            if (info.error) return result(false, info.error);
            return result(true, null);
          } catch (e) {
            return result(false, 'SCRIPT_FAILED');
          }
        })();
    """.trimIndent()

    /**
     * 一覧と詳細のどちらが実際に表示されているかを返す固定スクリプト。
     * 非同期の画面切り替えで古い DOM を読まないよう、クリック前後と復帰時に使う。
     * 行の内容や onclick は返さない。
     */
    internal val SCREEN_STATE_SCRIPT: String = """
        (function () {
          'use strict';
          $listGuardJs

          function result(ok, code, listReady, detailPresent, listError) {
            return JSON.stringify({
              ok: ok,
              error: code,
              listReady: listReady === true,
              detailPresent: detailPresent === true,
              listError: listError || null
            });
          }

          try {
            var pageFailure = pageError();
            if (pageFailure) return result(false, pageFailure, false, false, null);

            var detail = detailVisible();
            // 一覧の表示可否は詳細ガードを掛けずに判定する。
            // 一覧と詳細が併存する遷移中は listReady と detailPresent が両方 true になり、
            // 呼び出し側はどちらとも見なさず再試行する。
            var info = listCandidates();
            var listError = info.error;
            if (!listError && !hasAttendanceHeading(info.main)) {
              listError = 'LIST_HEADING_NOT_FOUND';
            }
            return result(true, null, !listError, detail, listError);
          } catch (e) {
            return result(false, 'SCRIPT_FAILED', false, false, null);
          }
        })();
    """.trimIndent()

    /**
     * 現在表示中の一覧行を返す固定スクリプト。
     * 行のリンク文字列とトークンだけを返し、onclick の生文字列は返さない。
     */
    internal val LIST_SCRIPT: String = """
        (function () {
          'use strict';
          $listGuardJs

          function fail(code) {
            return JSON.stringify({ ok: false, error: code, rows: [], warnings: [] });
          }

          try {
            var pageFailure = pageError();
            if (pageFailure) return fail(pageFailure);

            var info = listInfo();
            if (info.error) return fail(info.error);

            var rows = [];
            for (var i = 0; i < info.rows.length; i++) {
              rows.push({
                index: info.rows[i].index,
                label: info.rows[i].label,
                token: info.rows[i].token
              });
            }
            return JSON.stringify({ ok: true, error: null, rows: rows, warnings: [] });
          } catch (e) {
            return fail('SCRIPT_FAILED');
          }
        })();
    """.trimIndent()

    /**
     * 指定した行の既存リンクをクリックする固定スクリプトを作る。
     * 件数・行番号・トークンが一致しなければクリックせず [ErrorCode.LIST_CHANGED] を返す。
     * 出欠登録など他のボタンは押さない。
     */
    internal fun buildClickScript(row: Row, expectedCount: Int): String {
        require(expectedCount in 1..MAX_ROWS) { "unexpected row count" }
        require(row.index in 1..expectedCount) { "unexpected row index" }
        require(tokenRegex.matches(row.token)) { "unexpected row token" }

        return """
            (function () {
              'use strict';
              $listGuardJs

              function result(ok, code) {
                return JSON.stringify({ ok: ok, error: code });
              }

              try {
                var pageFailure = pageError();
                if (pageFailure) return result(false, pageFailure);

                var info = listInfo();
                if (info.error) return result(false, info.error);
                if (info.rows.length !== $expectedCount) {
                  return result(false, 'LIST_CHANGED');
                }

                var target = null;
                for (var i = 0; i < info.rows.length; i++) {
                  if (info.rows[i].index === ${row.index}) {
                    target = info.rows[i];
                    break;
                  }
                }
                if (!target || target.token !== '${row.token}') {
                  return result(false, 'LIST_CHANGED');
                }

                target.link.click();
                return result(true, null);
              } catch (e) {
                return result(false, 'CLICK_FAILED');
              }
            })();
        """.trimIndent()
    }

    /**
     * 詳細画面に「戻る」ボタンが一意に存在し、詳細だけが表示されていることを確認する
     * 固定スクリプト（クリックしない）。詳細 DOM が表示されたことの判定に使う。
     */
    internal val BACK_VERIFY_SCRIPT: String = """
        (function () {
          'use strict';
          $listGuardJs

          function result(ok, code) {
            return JSON.stringify({ ok: ok, error: code });
          }

          try {
            var pageFailure = pageError();
            if (pageFailure) return result(false, pageFailure);

            var detail = detailTableInfo();
            if (detail.error) return result(false, detail.error);
            if (listVisibleForDetailGuard() !== false) {
              return result(false, listVisibleForDetailGuard() === true ?
                'LIST_VISIBLE' : 'DETAIL_NOT_VERIFIED');
            }

            var info = backButton();
            if (info.error) return result(false, info.error);
            return result(true, null);
          } catch (e) {
            return result(false, 'SCRIPT_FAILED');
          }
        })();
    """.trimIndent()

    /**
     * 詳細画面の「戻る」ボタンをクリックする固定スクリプト。
     *
     * クリックと同じ JS 実行内で、見出し・詳細テーブルの一意性・必須ヘッダー・
     * 一覧が可視でないことを検証する。いずれかを確認できなければクリックしない。
     * これにより、画面が切り替わった後に別画面の「戻る」を誤って押さない。
     */
    internal val BACK_SCRIPT: String = """
        (function () {
          'use strict';
          $listGuardJs

          function result(ok, code) {
            return JSON.stringify({ ok: ok, error: code });
          }

          try {
            var pageFailure = pageError();
            if (pageFailure) return result(false, pageFailure);

            var detail = detailTableInfo();
            if (detail.error) return result(false, detail.error);
            var listVisible = listVisibleForDetailGuard();
            if (listVisible === true) return result(false, 'LIST_VISIBLE');
            if (listVisible !== false) return result(false, 'DETAIL_NOT_VERIFIED');

            var info = backButton();
            if (info.error) return result(false, info.error);

            info.button.click();
            return result(true, null);
          } catch (e) {
            return result(false, 'BACK_FAILED');
          }
        })();
    """.trimIndent()

    /** 一覧スクリプトの結果を検証する。形式不正・行の欠落・重複は [ErrorCode.INVALID_RESULT]。 */
    fun parseListResult(raw: String?): Roster {
        val decoded = decode(raw) ?: return Roster(emptyList(), emptyList(), ErrorCode.INVALID_RESULT)
        val root = runCatching { json.parseToJsonElement(decoded) as? JsonObject }.getOrNull()
            ?: return Roster(emptyList(), emptyList(), ErrorCode.INVALID_RESULT)

        val warnings = root.stringArray("warnings")
        val ok = (root["ok"] as? JsonPrimitive)?.booleanOrNull == true
        if (!ok) {
            val code = (root["error"] as? JsonPrimitive)?.contentOrNull
            return Roster(emptyList(), warnings, ErrorCode.fromJsCode(code))
        }
        val errorCode = (root["error"] as? JsonPrimitive)?.contentOrNull
        if (errorCode != null) {
            return Roster(emptyList(), warnings, ErrorCode.INVALID_RESULT)
        }

        val rowElements = root["rows"] as? JsonArray
            ?: return Roster(emptyList(), warnings, ErrorCode.INVALID_RESULT)
        if (rowElements.isEmpty() || rowElements.size > MAX_ROWS) {
            return Roster(emptyList(), warnings, ErrorCode.INVALID_RESULT)
        }

        val rows = mutableListOf<Row>()
        rowElements.forEachIndexed { position, element ->
            val obj = element as? JsonObject
                ?: return Roster(emptyList(), warnings, ErrorCode.INVALID_RESULT)
            val index = (obj["index"] as? JsonPrimitive)?.intOrNull
            val label = (obj["label"] as? JsonPrimitive)?.contentOrNull
                ?.replace('\u00a0', ' ')
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
            val token = (obj["token"] as? JsonPrimitive)?.contentOrNull?.trim()

            if (index != position + 1 ||
                label.isNullOrEmpty() || label.length > MAX_LABEL_LENGTH ||
                token == null || !tokenRegex.matches(token)
            ) {
                return Roster(emptyList(), warnings, ErrorCode.INVALID_RESULT)
            }
            rows += Row(index = index, label = label, token = token)
        }

        return Roster(rows, warnings, null)
    }

    /**
     * 一覧/詳細の表示状態スクリプトの結果を検証する。
     * 形式不正・矛盾した結果は [ErrorCode.INVALID_RESULT] として扱い、一覧とも詳細とも見なさない。
     */
    fun parseScreenState(raw: String?): ScreenState {
        val invalid = ScreenState(false, false, null, ErrorCode.INVALID_RESULT)
        val decoded = decode(raw) ?: return invalid
        val root = runCatching { json.parseToJsonElement(decoded) as? JsonObject }.getOrNull()
            ?: return invalid

        val okValue = root["ok"] as? JsonPrimitive ?: return invalid
        if (okValue.isString) return invalid
        val errorCode = (root["error"] as? JsonPrimitive)?.contentOrNull
        if (okValue.booleanOrNull != true) {
            val code = ErrorCode.fromJsCode(errorCode)
            return ScreenState(false, false, null, code)
        }
        if (errorCode != null) return invalid

        val listReadyValue = root["listReady"] as? JsonPrimitive ?: return invalid
        val detailPresentValue = root["detailPresent"] as? JsonPrimitive ?: return invalid
        // "true" のような文字列は真偽値として扱わない（矛盾した結果を通さない）。
        if (listReadyValue.isString || detailPresentValue.isString) return invalid
        val listReady = listReadyValue.booleanOrNull ?: return invalid
        val detailPresent = detailPresentValue.booleanOrNull ?: return invalid
        val listError = (root["listError"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?.let { ErrorCode.fromJsCode(it) }
        if (listError == ErrorCode.INVALID_RESULT) return invalid
        // 一覧として読めたのにエラー理由が付いている結果は矛盾している。
        if (listReady && listError != null) return invalid

        return ScreenState(
            listReady = listReady,
            detailPresent = detailPresent,
            listError = listError,
            error = null,
        )
    }

    /** クリック/戻るスクリプトの結果を検証する。成功なら null、失敗なら [ErrorCode]。 */
    fun parseActionResult(raw: String?): ErrorCode? {
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
