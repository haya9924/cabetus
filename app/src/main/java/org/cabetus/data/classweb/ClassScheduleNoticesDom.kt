package org.cabetus.data.classweb

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Reads only visible entries from the schedule-change bulletin list. */
object ClassScheduleNoticesDom {
    data class Snapshot(val titles: List<String>, val hasMore: Boolean)

    val SCRIPT = """
        (function () {
          if (location.origin !== 'https://class.admin.tus.ac.jp' || document.readyState !== 'complete') return null;
          const visible = e => !!e && e.getClientRects().length > 0 && getComputedStyle(e).visibility === 'visible';
          const label = e => (e.innerText || '').replace(/\s+/g, ' ').trim();
          const find = (root, selector, text) => Array.from(root.querySelectorAll(selector))
              .filter(e => visible(e) && label(e) === text);

          if (location.pathname === '/') {
            const entries = Array.from(document.querySelectorAll('a')).filter(e => visible(e) &&
                label(e).replace(/\s/g, '') === 'スマートフォンENTER' &&
                e.href === 'https://class.admin.tus.ac.jp/uprx/MobileShibbolethAuthServlet');
            if (entries.length === 1 && !window.__cabetusScheduleEntered) {
              window.__cabetusScheduleEntered = true;
              entries[0].click();
            }
            return null;
          }
          const boardPaths = ['/uprx/up/bs/bsd507/Bsd50701.xhtml', '/uprx/up/bs/bsa501/Bsa50101.xhtml'];
          const portalPath = '/uprx/up/pk/pky501/Pky50102.xhtml';
          const onBoardPath = boardPaths.includes(location.pathname);
          if (!onBoardPath && location.pathname !== portalPath) return null;
          const main = document.querySelector('.mainContent');
          if (!visible(main)) return null;
          const isList = onBoardPath && find(document, 'h2.ui-title', '掲示一覧').length === 1;
          if (!isList) {
            const menu = find(document, '#menuPanel a.ui-link.ui-btn.ui-icon-carat-r[href="#"]', '掲示板');
            if (menu.length === 1 && !window.__cabetusScheduleBoard) {
              window.__cabetusScheduleBoard = true;
              menu[0].click();
            }
            return null;
          }

          const tabs = find(main, '.ui-tabs .ui-tabs-nav li[role="tab"] > a.ui-tabs-anchor', '時間割変更');
          if (tabs.length !== 1) return null;
          const tab = tabs[0];
          const fragment = tab.getAttribute('href');
          if (!fragment || !/^#[\w:-]+${'$'}/.test(fragment)) return null;
          const panel = document.getElementById(fragment.slice(1));
          if (!panel || panel.getAttribute('role') !== 'tabpanel' || !tab.closest('.ui-tabs').contains(panel)) return null;
          if (!tab.classList.contains('ui-btn-active') || tab.parentElement.getAttribute('aria-expanded') !== 'true') {
            if (!window.__cabetusScheduleTab) {
              window.__cabetusScheduleTab = true;
              tab.click();
            }
            return null;
          }
          if (!visible(panel)) return null;
          const lists = Array.from(panel.querySelectorAll('ul[data-role="listview"].ui-listview'));
          if (lists.length !== 1) return null;
          const entries = Array.from(lists[0].children);
          // CLASS renders a verified empty schedule tab as a bare, enhanced list.
          // A hidden or unrecognized row is not evidence of an empty list.
          if (!entries.length) return { titles: [], hasMore: false };
          if (!visible(lists[0])) return null;
          const rows = entries.filter(visible);
          if (rows.length !== entries.length) return null;
          const titles = [];
          for (const row of rows.slice(0, 20)) {
            if (!row.matches('li.listIndent')) return null;
            const anchors = Array.from(row.querySelectorAll('a.ui-commandlink.ui-icon-carat-r')).filter(visible);
            if (anchors.length !== 1) return null;
            const title = label(anchors[0]);
            if (!title || title.length > 500) return null;
            titles.push(title);
          }
          const hasMore = rows.length > 20 || Array.from(panel.querySelectorAll('button.ui-icon-bullets')).some(visible);
          return { titles, hasMore };
        })();
    """.trimIndent()

    fun parse(raw: String?): Snapshot? {
        if (raw.isNullOrBlank() || raw.length > 32_768) return null
        val root = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        val hasMore = (root["hasMore"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: return null
        val items = (root["titles"] as? JsonArray)?.takeIf { it.size <= 20 } ?: return null
        val titles = items.map { item ->
            val text = (item as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim() ?: return null
            if (text.isEmpty() || text.length > 500) return null
            text
        }
        return Snapshot(titles.distinct(), hasMore)
    }
}
