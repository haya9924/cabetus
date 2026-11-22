package org.cabetus.data.classweb

/** 実機確認した読取専用メニューのみ操作する。認証・出席送信は操作しない。 */
object ClassAttendanceEntryDom {
    val SCRIPT = """
      (function(){
        if(location.protocol!=='https:'||location.hostname!=='class.admin.tus.ac.jp'||
          (location.port&&location.port!=='443'))return 'waiting';
        if(location.pathname==='/') {
          var enter=Array.prototype.filter.call(document.querySelectorAll('a'),function(e){
            return e.getClientRects().length && e.textContent.replace(/\\s/g,'')==='スマートフォンENTER' &&
              e.href==='https://class.admin.tus.ac.jp/uprx/MobileShibbolethAuthServlet';
          });
          if(enter.length===1){enter[0].click();return 'enter';}
          return 'waiting';
        }
        if(['/uprx/up/bs/bsa501/Bsa50101.xhtml','/uprx/up/pk/pky501/Pky50102.xhtml'].indexOf(location.pathname)<0)return 'waiting';
        var links=Array.prototype.filter.call(document.querySelectorAll('a.ui-link.ui-btn.ui-icon-carat-r'),function(e){
          return e.getClientRects().length && e.textContent.trim()==='学生出欠状況確認';
        });
        if(links.length!==1)return 'waiting';
        links[0].click(); return 'opened';
      })();
    """.trimIndent()
}
