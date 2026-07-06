package org.cabetus.domain

/** CLASS の公開シラバスURL（認証不要）。 */
object Syllabus {
    fun url(year: Int, courseCode: String): String =
        "https://class.admin.tus.ac.jp/slResult/$year/japanese/syllabusHtml/SyllabusHtml.$year.$courseCode.html"
}
