package org.cabetus.data.classweb

/**
 * Opens only the CLASS smartphone entrance or the read-only student timetable menu.
 *
 * The timetable menu accepts an exact read-only label inside `#menuPanel` without relying
 * on jQuery Mobile enhancer classes. It also accepts a direct link to a known timetable path
 * on the exact CLASS origin, and the original enhanced menu selector for 学生時間割表. When the panel is closed, a unique `href="#menuPanel"` opener is clicked
 * at most once per document and `false` is returned so that the service polls again.
 */
internal object ClassTimetableEntryDom {
    private val targetPathsJsLiteral: String =
        ClassTimetableDomExtractor.TARGET_PATHS.joinToString(separator = ", ") { "'$it'" }

    val SCRIPT = """
        (function () {
          if (location.protocol !== 'https:' || location.hostname !== 'class.admin.tus.ac.jp' ||
              (location.port && location.port !== '443')) return false;
          var isEntrance = location.pathname === '/';
          if (!isEntrance && ['/uprx/up/bs/bsa501/Bsa50101.xhtml',
              '/uprx/up/pk/pky501/Pky50102.xhtml'].indexOf(location.pathname) < 0) return false;
          var timetablePaths = [$targetPathsJsLiteral];

          function visible(element) {
            return !!element && element.getClientRects().length > 0 &&
                getComputedStyle(element).visibility === 'visible';
          }
          function label(link) {
            return link.textContent.replace(/\s+/g, '');
          }
          function targetsTimetable(link) {
            // 同一文書内の断片リンクは、解決後の pathname が時間割パスと一致してしまうため対象外。
            var raw = (link.getAttribute('href') || '').trim();
            if (!raw || raw.charAt(0) === '#') return false;
            var url;
            try { url = new URL(raw, location.href); } catch (ignored) { return false; }
            if (url.protocol !== 'https:' || url.origin !== location.origin ||
                url.username || url.password || url.hash) return false;
            return timetablePaths.indexOf(url.pathname) >= 0;
          }

          if (isEntrance) {
            var entrances = Array.prototype.filter.call(document.querySelectorAll('a'), function (link) {
              return visible(link) && label(link) === 'スマートフォンENTER' &&
                  link.href === 'https://class.admin.tus.ac.jp/uprx/MobileShibbolethAuthServlet';
            });
            if (entrances.length !== 1) return false;
            entrances[0].click();
            return true;
          }

          var panel = document.getElementById('menuPanel');
          var timetableLabels = ['学生時間割表', '学生時間割', '時間割表'];
          var targets = Array.prototype.filter.call(document.querySelectorAll('a'), function (link) {
            if (!visible(link) || link.closest('[aria-hidden="true"]')) return false;
            var isTimetableLabel = timetableLabels.indexOf(label(link)) >= 0;
            if (targetsTimetable(link) && (isTimetableLabel ||
                new URL(link.href).pathname !== location.pathname)) return true;
            if (link.getAttribute('href') !== '#') return false;
            return (panel && panel.contains(link) && isTimetableLabel) ||
                (link.matches('a.ui-link.ui-btn.ui-icon-carat-r') && label(link) === '学生時間割表');
          });
          if (targets.length > 1) return false;
          if (targets.length === 1) {
            targets[0].click();
            return true;
          }

          var closed = panel && (!panel.classList.contains('ui-panel-open') ||
              panel.getAttribute('aria-hidden') === 'true');
          if (!closed || window.__cabetusTimetableMenuPanel) return false;
          var openers = Array.prototype.filter.call(document.querySelectorAll('a[href="#menuPanel"]'),
              function (link) { return visible(link) && link.href === location.href.split('#')[0] + '#menuPanel'; });
          if (openers.length !== 1) return false;
          window.__cabetusTimetableMenuPanel = true;
          openers[0].click();
          return false;
        })();
    """.trimIndent()
}
