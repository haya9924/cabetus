package org.cabetus.domain

import org.cabetus.data.letus.LetusConstants
import java.net.URI
import java.text.Normalizer

/** Resolves a specific LETUS course, never the LETUS top page. */
internal object LetusClassLinkResolver {
    data class Course(val name: String, val url: String)

    private val suffix = Regex("\\(([^()]+)\\)$")
    private val idPattern = Regex("[1-9][0-9]{0,11}")
    private val space = Regex("[\\s\\p{Z}]+")

    private fun normalize(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .replace(space, " ").trim()

    fun resolve(courseCode: String, courseName: String, courses: List<Course>): String? {
        val code = normalize(courseCode)
        val name = normalize(courseName)
        val candidates = courses.map { normalize(it.name) to it.url }
        val byCode = if (code.isEmpty()) emptyList() else candidates.filter { (title, _) ->
            suffix.find(title)?.groupValues?.get(1)?.trim() == code
        }
        val matches = byCode.ifEmpty {
            if (name.isEmpty() || name == code) return null
            candidates.filter { (title, _) -> title == name }
        }
        return matches.map { (_, url) -> canonicalCourseUrl(url) ?: return null }.distinct().singleOrNull()
    }

    private fun canonicalCourseUrl(url: String): String? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            !uri.host.equals(LetusConstants.HOST, ignoreCase = true) || uri.userInfo != null ||
            uri.port !in listOf(-1, 443) || uri.rawPath != LetusConstants.COURSE_VIEW
        ) return null
        val ids = uri.query.orEmpty().split('&').filter { it.substringBefore('=') == "id" }
        val id = ids.singleOrNull()?.substringAfter('=', "")?.takeIf(idPattern::matches) ?: return null
        return "${LetusConstants.BASE}${LetusConstants.COURSE_VIEW}?id=$id"
    }
}
