package org.cabetus.data.classweb

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.net.URI
import java.time.LocalDate

/**
 * CLASS スマートフォン版の、実機レスポンスを Jsoup で読むだけの狭いパーサ。
 *
 * 通信・Cookie / localStorage / sessionStorage / IndexedDB の参照・保存は行わない。
 * フォーム値は送信せず、[postFields] で返すだけである。
 * 例外にはページ内容（URL・科目名・トークン・入力値）を含めない。
 */
internal class ClassAttendanceNetworkPage(
    html: String,
    path: String,
) {
    private val document: Document = Jsoup.parse(html)
    private val responsePath: String = pathOf(path) ?: fail()

    init {
        require(responsePath.startsWith("/")) { STRUCTURE_ERROR }
    }

    data class NetworkRow(
        val label: String,
        val code: String,
        val componentId: String,
    )

    override fun toString(): String = "ClassAttendanceNetworkPage"

    /**
     * ログイン画面か。ログインフォームと、認証 Servlet へのリンクの両方を要求する。
     */
    fun isLogin(): Boolean {
        if (document.select("form#loginForm").size != 1) return false
        return document.select("a[href]").any { anchor ->
            val href = pathOf(anchor.attr("href")) ?: return@any false
            href.endsWith("/MobileShibbolethAuthServlet") ||
                href.endsWith("/ShibbolethAuthServlet")
        }
    }

    /**
     * `.mainContent` 内の唯一の select の、selected option 属性から年度・学期を読む。
     * option 全体の value / text を走査しない。
     */
    fun context(): Pair<Int, String> {
        val main = mainContent()
        val selects = main.select("select")
        require(selects.size == 1) { STRUCTURE_ERROR }
        val selected = selects.single().select("option[selected]")
        require(selected.size == 1) { STRUCTURE_ERROR }
        val text = normalizeText(selected.single().text())
        val match = CONTEXT_REGEX.matchEntire(text) ?: fail()
        val year = match.groupValues[1].toInt()
        require(year in MIN_YEAR..MAX_YEAR) { STRUCTURE_ERROR }
        return year to match.groupValues[2]
    }

    /**
     * 出欠一覧の現在表示中の行だけを読む。ページ送りが存在する場合は読まない。
     */
    fun roster(): List<NetworkRow> {
        // 未検証のページ送りを全件完了と扱わない。
        require(document.select(".ui-paginator").isEmpty()) { STRUCTURE_ERROR }

        val main = mainContent()
        val lists = main.select("ul").filter { it.id().endsWith(LIST_ID_SUFFIX) }
        require(lists.size == 1) { STRUCTURE_ERROR }
        val list = lists.single()

        val anchors = list.children()
            .filter { it.tagName() == "li" }
            .map { item ->
                val links = item.children().filter {
                    it.tagName() == "a" && it.hasClass("ui-commandlink")
                }
                require(links.size == 1) { STRUCTURE_ERROR }
                links.single()
            }

        require(anchors.size in 1..MAX_ROWS) { STRUCTURE_ERROR }

        val seenCodes = mutableSetOf<String>()
        return anchors.map { anchor ->
            val label = normalizeText(anchor.text())
            require(label.isNotEmpty()) { STRUCTURE_ERROR }
            val code = ClassAttendanceSyncDom.courseCode(label) ?: fail()
            require(seenCodes.add(code)) { STRUCTURE_ERROR }

            val componentId = anchor.id()
            require(componentId.isNotEmpty()) { STRUCTURE_ERROR }
            val match = ONCLICK_REGEX.matchEntire(anchor.attr("onclick")) ?: fail()
            require(
                match.groupValues[1] == componentId &&
                    match.groupValues[2] == componentId,
            ) { STRUCTURE_ERROR }

            NetworkRow(label = label, code = code, componentId = componentId)
        }
    }

    /**
     * `funcForm` の送信候補を組み立てる。送信・ログ出力はしない。
     *
     * hidden input の重複は observed どおり保持する。未知の hidden、text/password、
     * その他の送信フィールドは推測せず拒否する。
     */
    fun postFields(component: String): List<Pair<String, String>> {
        require(component.isNotBlank() && component.length <= MAX_COMPONENT_LENGTH) {
            STRUCTURE_ERROR
        }

        val forms = document.select("form").filter { it.id() == "pmPage:funcForm" }
        require(forms.size == 1) { STRUCTURE_ERROR }
        val form = forms.single()
        val action = URI(form.attr("action"))
        require(!action.isAbsolute || (action.scheme == "https" && action.host == ClassAttendanceHttpsTransport.HOST && (action.port == -1 || action.port == 443) && action.userInfo == null)) { STRUCTURE_ERROR }
        require(action.rawQuery == null && action.rawFragment == null && pathOf(form.attr("action")) == responsePath) { STRUCTURE_ERROR }

        val hiddenFields = mutableListOf<Pair<String, String>>()
        val dateFields = mutableListOf<Pair<String, String>>()

        form.select("input").forEach { input ->
            val type = input.attr("type").trim().lowercase().ifEmpty { "text" }
            val name = input.attr("name")
            when {
                type == "hidden" -> {
                    require(name in ALLOWED_HIDDEN_NAMES) { STRUCTURE_ERROR }
                    hiddenFields += name to input.attr("value")
                }

                type in IGNORED_INPUT_TYPES -> Unit

                DATE_INPUT_NAME.matches(name) -> {
                    require(type == "text" || type == "date") { STRUCTURE_ERROR }
                    val value = input.attr("value")
                    require(ISO_DATE.matches(value)) { STRUCTURE_ERROR }
                    require(runCatching { LocalDate.parse(value) }.isSuccess) {
                        STRUCTURE_ERROR
                    }
                    dateFields += name to value
                }

                else -> fail()
            }
        }

        require(form.select("textarea").isEmpty()) { STRUCTURE_ERROR }

        val selects = form.select("select")
        require(selects.all { it.select("option[selected]").size <= 1 }) {
            STRUCTURE_ERROR
        }
        val selectedSelects = selects.filter { it.select("option[selected]").size == 1 }
        require(selectedSelects.size <= 1) { STRUCTURE_ERROR }

        val selectedFields = selectedSelects.map { select ->
            val selected = select.select("option[selected]").single()
            val name = select.attr("name")
            val value = selected.attr("value")
            require(name == "pmPage:funcForm:kaikoNendoGakkiList" && value.isNotBlank()) { STRUCTURE_ERROR }
            name to value
        }

        return hiddenFields + selectedFields + dateFields + listOf(
            component to component,
            "rx.sync.source" to component,
        )
    }

    /**
     * `.attendanceInfo` の唯一の table を既存 planner でプレビューへ変換する。
     * 見出しは画面から読まず、中立値（null）にする。保存はしない。
     */
    fun detail(): ClassAttendanceImportPreview {
        val main = mainContent()
        val containers = main.select(".attendanceInfo")
        require(containers.size == 1) { STRUCTURE_ERROR }
        val container = containers.single()
        val tables = if (container.tagName() == "table") {
            listOf(container)
        } else {
            container.select("table")
        }
        require(tables.size == 1) { STRUCTURE_ERROR }
        val table = tables.single()
        val plan = ClassAttendanceDetailImportPlanner.plan(
            ClassAttendanceDetailImportPlanner.wrapExtractedTable(table.outerHtml()),
        )
        return ClassAttendanceImportPreview(
            heading = null,
            rows = plan.previewRows,
            warnings = plan.warnings,
            errors = plan.errors,
            provisional = false,
        )
    }

    /**
     * `.mainContent` の可視テキストノード（table/script/input/select を除く）から
     * 単独の正式科目コードを読む。複数・0件なら拒否する。
     */
    fun detailCode(): String {
        val texts = mutableListOf<String>()
        collectTextNodes(mainContent(), texts)
        val codes = texts.flatMap { text ->
            CODE_REGEX.findAll(normalizeText(text)).map { it.value }.toList()
        }.distinct()
        require(codes.size == 1) { STRUCTURE_ERROR }
        return codes.single()
    }

    /**
     * 戻る操作の実機スクリプトは未確認のため、候補の存在だけを確認して拒否する。
     */
    fun backComponent(): String {
        val forms = document.select("form").filter { it.id() == "pmPage:funcForm" }
        require(forms.size == 1) { STRUCTURE_ERROR }
        val candidates = forms.single().select("button, a").filter {
            normalizeText(it.text()) == BACK_LABEL
        }
        require(candidates.size == 1) { STRUCTURE_ERROR }
        throw UnsupportedOperationException("戻る操作は実機未確認のため対応していません。")
    }

    private fun mainContent(): Element {
        val mains = document.select(".mainContent")
        require(mains.size == 1) { STRUCTURE_ERROR }
        return mains.single()
    }

    private fun collectTextNodes(element: Element, output: MutableList<String>) {
        if (isHidden(element)) return
        if (element.tagName().lowercase() in EXCLUDED_TEXT_TAGS) return
        element.childNodes().forEach { node ->
            when (node) {
                is TextNode -> output += node.text()
                is Element -> collectTextNodes(node, output)
            }
        }
    }

    private fun isHidden(element: Element): Boolean {
        if (element.hasAttr("hidden")) return true
        if (element.attr("aria-hidden").equals("true", ignoreCase = true)) return true
        val style = element.attr("style").replace(Regex("\\s+"), "").lowercase()
        return style.contains("display:none") || style.contains("visibility:hidden")
    }

    private fun normalizeText(text: String?): String =
        (text ?: "")
            .replace('\u00a0', ' ')
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun fail(): Nothing = throw IllegalArgumentException(STRUCTURE_ERROR)

    private fun pathOf(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val uri = runCatching { URI(text) }.getOrNull()
        val path = if (uri?.isAbsolute == true) {
            uri.rawPath
        } else {
            text.substringBefore('?').substringBefore('#')
        }
        return path?.takeIf { it.isNotEmpty() }
    }

    private companion object {
        const val STRUCTURE_ERROR = "CLASS のページ構造が想定と異なります。"
        const val LIST_ID_SUFFIX = "_list"
        const val MAX_ROWS = 200
        const val MAX_COMPONENT_LENGTH = 200
        const val MIN_YEAR = 2000
        const val MAX_YEAR = 2100
        const val BACK_LABEL = "戻る"

        val CONTEXT_REGEX = Regex("^(\\d{4})\\s*年度\\s*(前期|後期)$")
        val CODE_REGEX = Regex("(?<![A-Za-z0-9])[A-Z0-9]{7}(?![A-Za-z0-9])")
        val DATE_INPUT_NAME = Regex("^pmPage:funcForm:baseDateList:.+_input$")
        val ISO_DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")

        /**
         * 実機で確認した onclick 形式。パラメータのキー・値は要素自身の id と一致する。
         * `syncTransition(this);` の直後のセミコロンは観測表記の揺れだけ許容する。
         */
        val ONCLICK_REGEX = Regex(
            "^syncTransition\\(this\\);{1,2}" +
                "PrimeFaces\\.addSubmitParam\\('pmPage:funcForm'," +
                "\\{'([^']+)':'([^']+)'\\}\\)" +
                "\\.submit\\('pmPage:funcForm'\\);return false;$",
        )

        val ALLOWED_HIDDEN_NAMES = setOf(
            "pmPage:funcForm",
            "rx-token",
            "rx-loginKey",
            "rx-deviceKbn",
            "rx-loginType",
            "javax.faces.ViewState",
            "javax.faces.RenderKitId",
        )

        val IGNORED_INPUT_TYPES = setOf("submit", "button", "reset", "image")
        val EXCLUDED_TEXT_TAGS = setOf("table", "script", "input", "select")
    }
}
