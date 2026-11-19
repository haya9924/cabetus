package org.cabetus.data.classweb

import kotlinx.serialization.json.*

/** 同期専用の文脈・正式コード検証。入力値、認証値には触れない。 */
object ClassAttendanceSyncDom {
    data class Context(val year: Int, val term: String, val codes: List<String>)
    fun courseCode(label: String): String? = Regex("(?<![A-Za-z0-9])[A-Z0-9]{7}(?![A-Za-z0-9])")
        .findAll(label).map { it.value }.toList().singleOrNull()

    val SCRIPT = """
        (function(){
          var context = (${ClassAcademicContextDomExtractor.SCRIPT.trim().removeSuffix(";")});
          context = JSON.parse(context);
          if (!context.ok) return JSON.stringify({ok:false});
          var roots=document.querySelectorAll('.mainContent');
          if(roots.length!==1) return JSON.stringify({ok:false});
          var root=roots[0];
          // 未検証のページ送りを全件完了と扱わない。
          if(root.querySelector('.ui-paginator,[data-role="pagination"],.ui-datalist-paginator'))
            return JSON.stringify({ok:false});
          var codes=[];
          var elements=root.querySelectorAll('div.ui-outputpanel');
          for(var i=0;i<elements.length;i++) {
            var e=elements[i]; if(!e.getClientRects().length)continue;
            for(var j=0;j<e.childNodes.length;j++) {
              if(e.childNodes[j].nodeType!==3)continue;
              var direct=e.childNodes[j].textContent.trim();
              if(/^[A-Z0-9]{7}${'$'}/.test(direct)) codes.push(direct);
            }
          }
          return JSON.stringify({ok:true,context:context,codes:codes});
        })();
    """.trimIndent()

    fun parse(raw: String?): Context? = runCatching {
        var value = Json.parseToJsonElement(raw ?: return null)
        if (value is JsonPrimitive && value.isString) value = Json.parseToJsonElement(value.content)
        val obj = value.jsonObject
        if (obj["ok"]?.jsonPrimitive?.booleanOrNull != true) return null
        val context = ClassAcademicContextDomExtractor.parseEvaluationResult(obj["context"].toString())
        val candidate = context.context ?: return null
        Context(candidate.year, candidate.term.label, obj["codes"]!!.jsonArray.map { it.jsonPrimitive.content })
    }.getOrNull()
}
