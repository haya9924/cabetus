package org.cabetus.data.classweb

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * CLASS スマートフォン版「学生時間割表」から、固定スクリプトで時間割セルだけを読み取る。
 *
 * 実機で確認できた対象は次のとおり（2025-11 時点の調査メモ）。
 * - `https://class.admin.tus.ac.jp`（完全一致ホスト）
 * - `/uprx/up/at/ats501/Ats50101.xhtml`（学生時間割表）
 * - `/uprx/up/bs/bsa501/Bsa50101.xhtml`（学生時間割表。実機で表示を確認済み）
 * - `.rishuArea` の最初の `table`
 * - ヘッダーは月〜土、時限は `td.colJigen`、各曜日セルの `a.jugyo-info` の textContent が科目名
 * - `a.jugyo-info` の onclick は `scrollToRisyuInfo('2', '1', '1',1010996498)` 形式。
 *   最後の数値は、同じページ内の `div#jugyoInfo1010996498.jugyoInfoArea` に対応する
 * - 科目詳細の直接子は `span.jugyoName`、教員、教室、科目コード、単位の div が
 *   この順に並ぶ。教室は「葛：E501教室」のようにキャンパス略字 + 全角コロンで始まり、
 *   科目コードは大文字英数字7桁、単位は「2単位」形式。詳細は開閉状態に関係なく DOM に存在する
 * - 直接子の並びが上記と異なる場合は、7桁英数字の div を科目コードと推測しない
 * - 昼休み行（colspan 6）は時限を持たないため対象外
 *
 * ここで行うのは「画面に表示されている科目名・曜日・時限・科目コード・教室」の抽出だけである。
 * 科目名と詳細の `span.jugyoName` が一致しない場合や、直接子の並び・コード・教室を
 * 一意に確認できない場合は、その値を推測せず null にする。
 * Cookie / localStorage / sessionStorage / フォーム値 / hidden input / HTML 全体は読まない。
 * `addJavascriptInterface` は使わず、[VERIFY_SCRIPT] / [SCRIPT] は
 * [android.webkit.WebView.evaluateJavascript] に固定文字列として渡す。結果は JSON 文字列だけを
 * Android 側で検証する。
 * 科目名などの実データをログへ出したり、ファイルへ保存したりはしない。
 */
object ClassTimetableDomExtractor {

    const val TARGET_HOST = "class.admin.tus.ac.jp"

    /**
     * 実機で表示を確認済みの学生時間割表パス（完全一致のみ）。
     *
     * ここに列挙したパス以外は取り込まない。前方一致やディレクトリ単位の許可はしない。
     * アプリ内ブラウザのボタン表示判定（`BrowserUrlPolicy`）と固定スクリプトは
     * このリストを唯一の定義として共有する。
     */
    val TARGET_PATHS: List<String> = listOf(
        "/uprx/up/at/ats501/Ats50101.xhtml",
        "/uprx/up/bs/bsa501/Bsa50101.xhtml",
    )

    private val targetPathsJsLiteral: String =
        TARGET_PATHS.joinToString(separator = ", ") { "'$it'" }

    /**
     * 時間割ページかどうかを判定する共通スクリプト片。
     *
     * URL・表示状態・`.rishuArea` の表・月〜土ヘッダーだけを確認する。
     * 科目名などの実データは読まない。
     */
    private val pageAndTableGuardJs: String = """
        var TARGET_HOST = 'class.admin.tus.ac.jp';
        var TARGET_PATHS = [$targetPathsJsLiteral];
        var WEEKDAYS = ['月', '火', '水', '木', '金', '土'];

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

        // 実際に表示されている時間割表だけを返す。ヘッダーが月〜土でなければ失敗する。
        function timetableTable() {
          var area = document.querySelector('.rishuArea');
          if (!area) return { error: 'AREA_NOT_FOUND' };

          var table = area.querySelector('table');
          if (!table) return { error: 'TABLE_NOT_FOUND' };
          if (!table.getClientRects || table.getClientRects().length === 0) {
            return { error: 'TABLE_NOT_VISIBLE' };
          }
          var tableStyle = window.getComputedStyle ? window.getComputedStyle(table) : null;
          if (tableStyle && (tableStyle.display === 'none' ||
              tableStyle.visibility !== 'visible')) {
            return { error: 'TABLE_NOT_VISIBLE' };
          }
          if (!table.rows || table.rows.length < 2) return { error: 'ROWS_NOT_FOUND' };

          var header = table.rows[0];
          var dayColumn = {};
          var weekdayCount = 0;
          for (var h = 0; h < header.cells.length; h++) {
            var label = normalize(header.cells[h].textContent);
            if (WEEKDAYS.indexOf(label) >= 0 && dayColumn[label] === undefined) {
              dayColumn[label] = h;
              weekdayCount++;
            }
          }
          if (weekdayCount !== WEEKDAYS.length) return { error: 'HEADER_MISMATCH' };

          return { table: table, dayColumn: dayColumn };
        }
    """.trimIndent()

    /** 1コマ分。dayOfWeek は 1=月 … 6=土、period は 1..7。 */
    data class Slot(
        val dayOfWeek: Int,
        val period: Int,
        val courseName: String,
        /** CLASS の科目詳細で確認できた正式な科目コード。不明・不一致なら null。 */
        val courseCode: String? = null,
        /** キャンパス表記を除いた教室名（例: 「E501」）。不明・不一致なら null。 */
        val room: String? = null,
    )

    enum class ErrorCode(val jsCode: String) {
        UNEXPECTED_PAGE("UNEXPECTED_PAGE"),
        PAGE_NOT_VISIBLE("PAGE_NOT_VISIBLE"),
        AREA_NOT_FOUND("AREA_NOT_FOUND"),
        TABLE_NOT_FOUND("TABLE_NOT_FOUND"),
        TABLE_NOT_VISIBLE("TABLE_NOT_VISIBLE"),
        ROWS_NOT_FOUND("ROWS_NOT_FOUND"),
        HEADER_MISMATCH("HEADER_MISMATCH"),
        NO_SLOTS("NO_SLOTS"),
        SCRIPT_FAILED("SCRIPT_FAILED"),
        INVALID_RESULT("INVALID_RESULT"),
        ;

        val userMessage: String
            get() = when (this) {
                UNEXPECTED_PAGE ->
                    "CLASS の学生時間割表ページ（想定URL）ではありません。時間割表を開き直してください。"
                PAGE_NOT_VISIBLE ->
                    "時間割表が表示状態ではありません。CLASS の時間割表を前面に表示してから再試行してください。"
                AREA_NOT_FOUND ->
                    "時間割領域（.rishuArea）が見つかりません。時間割表が表示されているか確認してください。"
                TABLE_NOT_FOUND ->
                    "時間割の表が見つかりません。時間割表が表示されているか確認してください。"
                TABLE_NOT_VISIBLE ->
                    "時間割の表が画面に表示されていません。表示してから再試行してください。"
                ROWS_NOT_FOUND ->
                    "時間割の行が不足しています。時間割表が完全に表示されるまで待ってから再試行してください。"
                HEADER_MISMATCH ->
                    "時間割の曜日ヘッダー（月〜土）を確認できません。画面のレイアウトが想定と異なるため取り込みません。"
                NO_SLOTS ->
                    "取り込める科目が見つかりませんでした。時間割表が表示されているか確認してください。"
                SCRIPT_FAILED ->
                    "時間割の読み取りに失敗しました。ページを再読み込みしてから再試行してください。"
                INVALID_RESULT ->
                    "時間割の読み取り結果を確認できませんでした。ページを再読み込みしてから再試行してください。"
            }

        companion object {
            fun fromJsCode(code: String?): ErrorCode =
                entries.firstOrNull { it.jsCode == code } ?: INVALID_RESULT
        }
    }

    data class Result(
        val slots: List<Slot>,
        val warnings: List<String>,
        val error: ErrorCode?,
    ) {
        val isSuccess: Boolean get() = error == null
    }

    private val json = Json { ignoreUnknownKeys = true }
    private const val MAX_COURSE_NAME_LENGTH = 200

    /**
     * WebView から返る evaluateJavascript の結果を検証する。
     *
     * Android のコールバックは JS の戻り値を JSON 表現で返す。固定スクリプトは
     * `JSON.stringify(...)` の文字列を返すため、二重に JSON 文字列になった場合も受け付ける。
     * 形式が不正、または値が想定外の場合は [ErrorCode.INVALID_RESULT] として扱う。
     */
    fun parseEvaluationResult(raw: String?): Result {
        val decoded = decode(raw) ?: return Result(emptyList(), emptyList(), ErrorCode.INVALID_RESULT)
        val root = runCatching { json.parseToJsonElement(decoded) as? JsonObject }.getOrNull()
            ?: return Result(emptyList(), emptyList(), ErrorCode.INVALID_RESULT)

        val warnings = root.stringArray("warnings").toMutableList()
        val ok = (root["ok"] as? JsonPrimitive)?.booleanOrNull == true
        if (!ok) {
            val code = (root["error"] as? JsonPrimitive)?.contentOrNull
            return Result(emptyList(), warnings, ErrorCode.fromJsCode(code))
        }

        val rawSlots = root["slots"] as? JsonArray
            ?: return Result(emptyList(), warnings, ErrorCode.INVALID_RESULT)

        val slots = LinkedHashMap<SlotKey, Slot>()
        var invalidSlots = 0
        rawSlots.forEach { element ->
            val obj = element as? JsonObject
            val day = (obj?.get("dayOfWeek") as? JsonPrimitive)?.intOrNull
            val period = (obj?.get("period") as? JsonPrimitive)?.intOrNull
            val name = normalizeDisplayName(
                (obj?.get("courseName") as? JsonPrimitive)?.contentOrNull,
            )
            val code = ClassTimetableCourseMetadata.normalizeOfficialCourseCode(
                (obj?.get("courseCode") as? JsonPrimitive)?.contentOrNull,
            )
            val room = ClassTimetableCourseMetadata.normalizeRoomFromDetail(
                (obj?.get("room") as? JsonPrimitive)?.contentOrNull,
            )
            if (day == null || day !in 1..6 ||
                period == null || period !in 1..7 ||
                name.isBlank() || name.length > MAX_COURSE_NAME_LENGTH
            ) {
                invalidSlots++
                return@forEach
            }
            val slot = Slot(day, period, name, code, room)
            slots.putIfAbsent(SlotKey(day, period, normalizeKey(name), code, room), slot)
        }

        if (invalidSlots > 0) {
            warnings += "形式が不正なコマを${invalidSlots}件スキップしました。"
        }
        if (slots.isEmpty()) {
            return Result(emptyList(), warnings, ErrorCode.NO_SLOTS)
        }
        return Result(slots.values.toList(), warnings, null)
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

    private data class SlotKey(
        val dayOfWeek: Int,
        val period: Int,
        val nameKey: String,
        val courseCode: String?,
        val room: String?,
    )

    private fun JsonObject.stringArray(key: String): List<String> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { element ->
            (element as? JsonPrimitive)?.contentOrNull
                ?.replace('\u00a0', ' ')
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }

    private fun normalizeDisplayName(value: String?): String =
        (value ?: "")
            .replace('\u00a0', ' ')
            .trim()
            .replace(Regex("\\s+"), " ")

    /**
     * 照合用の名前。全角/半角は揃えるが、内部の空白は削らない。
     * 「合成 科目A」と「合成科目A」は別の科目名として扱う。
     */
    private fun normalizeKey(value: String): String =
        java.text.Normalizer.normalize(normalizeDisplayName(value), java.text.Normalizer.Form.NFKC)
            .replace('\u00a0', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * ボタン表示・取り込みクリック前の再検証に使う固定スクリプト。
     *
     * 対象 URL（https・完全一致ホスト・完全一致パス）に加えて、実際に表示中の
     * `.rishuArea` の表と月〜土ヘッダーを確認する。科目名などは返さず、
     * 成功/失敗とエラーコードだけを返す。
     */
    internal val VERIFY_SCRIPT: String = """
        (function () {
          'use strict';
          $pageAndTableGuardJs

          function result(ok, code) {
            return JSON.stringify({ ok: ok, error: code });
          }

          try {
            var error = pageError();
            if (error) return result(false, error);

            var info = timetableTable();
            if (info.error) return result(false, info.error);

            return result(true, null);
          } catch (e) {
            return result(false, 'SCRIPT_FAILED');
          }
        })();
    """.trimIndent()

    /**
     * 検証スクリプトの戻り値を検証する。成功なら null、失敗なら [ErrorCode] を返す。
     * 形式が不正な場合は [ErrorCode.INVALID_RESULT]。
     */
    fun parseVerificationResult(raw: String?): ErrorCode? {
        val decoded = decode(raw) ?: return ErrorCode.INVALID_RESULT
        val root = runCatching { json.parseToJsonElement(decoded) as? JsonObject }.getOrNull()
            ?: return ErrorCode.INVALID_RESULT

        val okValue = root["ok"] as? JsonPrimitive ?: return ErrorCode.INVALID_RESULT
        if (okValue.isString) return ErrorCode.INVALID_RESULT

        val errorCode = (root["error"] as? JsonPrimitive)?.contentOrNull
        if (okValue.booleanOrNull == true) {
            // ok=true とエラーコードが同時に返る矛盾した結果は成功扱いしない。
            return if (errorCode == null) null else ErrorCode.INVALID_RESULT
        }
        return ErrorCode.fromJsCode(errorCode)
    }

    /**
     * 固定スクリプト。対象 URL・表示状態・対象表のヘッダーを確認したうえで、
     * `.rishuArea` の最初の table にある `a.jugyo-info` の textContent（科目名）と、
     * 対応する `div.jugyoInfoArea` の直接子から科目コード・教室だけを返す。
     * 詳細の開閉状態には依存しない。
     * Cookie / Storage / hidden input / form value / ページ全体の HTML は参照しない。
     */
    internal val SCRIPT: String = """
        (function () {
          'use strict';
          $pageAndTableGuardJs

          function fail(code) {
            return JSON.stringify({ ok: false, error: code, slots: [], warnings: [] });
          }

          // 全角/半角は揃えるが、内部の空白は削らない。
          // 「合成 科目A」と「合成科目A」を同じ科目として扱わない。
          function nameKey(text) {
            var normalized = normalize(text);
            return normalized.normalize ? normalized.normalize('NFKC') : normalized;
          }

          // onclick="scrollToRisyuInfo('2', '1', '1',1010996498)" の最後の数値。
          function detailIdFromLink(link) {
            var onclick = link.getAttribute('onclick');
            if (!onclick) return null;
            var call = /scrollToRisyuInfo\s*\(([^)]*)\)/.exec(onclick);
            if (!call) return null;
            var args = call[1].split(',');
            if (args.length < 4) return null;
            var last = normalize(args[args.length - 1])
              .replace(/^['"]/, '')
              .replace(/['"]$/, '');
            return /^\d+$/.test(last) ? last : null;
          }

          function emptyFields(structure) {
            return {
              names: [],
              codes: [],
              rooms: [],
              structure: structure === true
            };
          }

          // 実機で確認した科目詳細の直接子構造だけを受け付ける。
          // span.jugyoName / div(教員) / div(教室: キャンパス＋：) /
          // div(科目コード) / div(単位) の 5 要素がこの順に並ぶ場合だけ、
          // 科目コードと教室を取り出す。7 桁英数字の div を位置で推測して
          // 拾うことはしない。入れ子のボタンやアイコン領域は構造判定から除外する。
          function detailFields(detail) {
            var elements = [];
            var children = detail.children || [];
            for (var i = 0; i < children.length; i++) {
              var child = children[i];
              var tag = child.tagName ? child.tagName.toLowerCase() : '';
              if (tag === 'div' && child.classList &&
                  (child.classList.contains('iconArea') ||
                   child.classList.contains('jugyo-btnArea'))) {
                continue;
              }
              if (tag !== 'span' && tag !== 'div') {
                return emptyFields(false);
              }
              elements.push(child);
            }

            if (elements.length !== 5) return emptyFields(false);

            var nameElement = elements[0];
            var instructorElement = elements[1];
            var roomElement = elements[2];
            var codeElement = elements[3];
            var creditElement = elements[4];

            var nameTag = nameElement.tagName ? nameElement.tagName.toLowerCase() : '';
            if (nameTag !== 'span' || !nameElement.classList ||
                !nameElement.classList.contains('jugyoName')) {
              return emptyFields(false);
            }
            for (var d = 1; d < elements.length; d++) {
              var divTag = elements[d].tagName ? elements[d].tagName.toLowerCase() : '';
              if (divTag !== 'div') return emptyFields(false);
            }

            var displayName = normalize(nameElement.textContent);
            var instructor = normalize(instructorElement.textContent);
            var room = normalize(roomElement.textContent);
            var code = normalize(codeElement.textContent);
            var credits = normalize(creditElement.textContent);

            var structureOk =
              displayName !== '' &&
              instructor !== '' &&
              /^[神葛野]：.+$/.test(room) &&
              /^[0-9A-Z]{7}$/.test(code) &&
              /^[0-9]+(?:\.[0-9]+)?\s*単位$/.test(credits);
            if (!structureOk) return emptyFields(false);

            return {
              names: [displayName],
              codes: [code],
              rooms: [room],
              structure: true
            };
          }

          try {
            var pageFailure = pageError();
            if (pageFailure) return fail(pageFailure);

            var tableInfo = timetableTable();
            if (tableInfo.error) return fail(tableInfo.error);

            var table = tableInfo.table;
            var dayColumn = tableInfo.dayColumn;
            var slots = [];
            var warnings = [];
            var seen = {};
            var detailLookupFailures = 0;
            var detailStructureMismatches = 0;
            var detailNameMismatches = 0;

            for (var r = 1; r < table.rows.length; r++) {
              var row = table.rows[r];
              var periodCell = null;
              var periodIndex = -1;
              var lunchRow = false;

              for (var c = 0; c < row.cells.length; c++) {
                var cell = row.cells[c];
                if (cell.colSpan >= 6) lunchRow = true;
                if (periodCell === null && cell.classList &&
                    cell.classList.contains('colJigen')) {
                  periodCell = cell;
                  periodIndex = c;
                }
              }

              // 昼休みなどの colspan 行は時限を持たない。想定どおり除外する。
              if (periodCell === null) {
                if (!lunchRow) warnings.push('時限セルのない行をスキップしました。');
                continue;
              }

              var periodText = normalize(periodCell.textContent);
              var periodMatch = /^([1-7])(?:限|時限)?$/.exec(periodText);
              if (!periodMatch) {
                warnings.push('時限を解釈できない行をスキップしました。');
                continue;
              }
              var period = parseInt(periodMatch[1], 10);

              // ヘッダーに先頭の空セルがある場合は 0、無い場合は +1 して曜日セルを合わせる。
              var offset = dayColumn[WEEKDAYS[0]] <= periodIndex ? 1 : 0;

              for (var d = 0; d < WEEKDAYS.length; d++) {
                var dayIndex = dayColumn[WEEKDAYS[d]] + offset;
                if (dayIndex >= row.cells.length) {
                  warnings.push('曜日セルが不足している行をスキップしました。');
                  continue;
                }

                var links = row.cells[dayIndex].querySelectorAll('a.jugyo-info');
                for (var a = 0; a < links.length; a++) {
                  var link = links[a];
                  var name = normalize(link.textContent);
                  if (!name) {
                    warnings.push('科目名が空のリンクをスキップしました。');
                    continue;
                  }

                  var courseCode = null;
                  var room = null;
                  var detailId = detailIdFromLink(link);
                  if (detailId === null) {
                    detailLookupFailures++;
                  } else {
                    var detail = document.getElementById('jugyoInfo' + detailId);
                    if (!detail || !detail.classList ||
                        !detail.classList.contains('jugyoInfoArea')) {
                      detailLookupFailures++;
                    } else {
                      var fields = detailFields(detail);
                      if (!fields.structure) {
                        // 直接子の並びが実機で確認した構造と異なる場合は、
                        // コード・教室を位置から推測しない。
                        detailStructureMismatches++;
                      } else if (fields.names.length !== 1 ||
                          nameKey(fields.names[0]) !== nameKey(name)) {
                        // 表示中の科目名と詳細が一致しないときは、対応を推測しない。
                        detailNameMismatches++;
                      } else {
                        // detailFields は構造を確認できた場合だけ 1 件ずつ返す。
                        if (fields.codes.length === 1) {
                          courseCode = fields.codes[0];
                        }
                        if (fields.rooms.length === 1) {
                          room = fields.rooms[0];
                        }
                      }
                    }
                  }

                  var key = String(d + 1) + ':' + String(period) + ':' + nameKey(name) +
                    ':' + (courseCode || '') + ':' + (room || '');
                  if (seen[key]) continue;
                  seen[key] = true;
                  slots.push({
                    dayOfWeek: d + 1,
                    period: period,
                    courseName: name,
                    courseCode: courseCode,
                    room: room
                  });
                }
              }
            }

            if (detailLookupFailures > 0) {
              warnings.push('科目詳細を確認できないリンクが' + detailLookupFailures + '件あります。');
            }
            if (detailStructureMismatches > 0) {
              warnings.push('科目詳細の項目配置を確認できないリンクが' +
                detailStructureMismatches +
                '件あります。科目コード・教室は推測しません。');
            }
            if (detailNameMismatches > 0) {
              warnings.push('科目名と詳細が一致しないリンクが' + detailNameMismatches +
                '件あります。科目コード・教室は推測しません。');
            }
            if (slots.length === 0) return fail('NO_SLOTS');
            return JSON.stringify({ ok: true, error: null, slots: slots, warnings: warnings });
          } catch (e) {
            return fail('SCRIPT_FAILED');
          }
        })();
    """.trimIndent()
}
