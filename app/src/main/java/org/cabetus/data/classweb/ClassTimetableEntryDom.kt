package org.cabetus.data.classweb

/** Opens only the CLASS smartphone entrance or the read-only student timetable menu. */
internal object ClassTimetableEntryDom {
    val SCRIPT = """
        (function () {
          if (location.protocol !== 'https:' || location.hostname !== 'class.admin.tus.ac.jp' ||
              (location.port && location.port !== '443')) return false;
          var isEntrance = location.pathname === '/';
          if (!isEntrance && ['/uprx/up/bs/bsa501/Bsa50101.xhtml',
              '/uprx/up/pk/pky501/Pky50102.xhtml'].indexOf(location.pathname) < 0) return false;
          var links = Array.prototype.filter.call(document.querySelectorAll(
              isEntrance ? 'a' : 'a.ui-link.ui-btn.ui-icon-carat-r'), function (link) {
            if (!link.getClientRects().length || getComputedStyle(link).visibility !== 'visible') return false;
            var label = link.textContent.replace(/\s+/g, '');
            return isEntrance
                ? label === 'スマートフォンENTER' &&
                  link.href === 'https://class.admin.tus.ac.jp/uprx/MobileShibbolethAuthServlet'
                : label === '学生時間割表';
          });
          if (links.length !== 1) return false;
          links[0].click();
          return true;
        })();
    """.trimIndent()
}
