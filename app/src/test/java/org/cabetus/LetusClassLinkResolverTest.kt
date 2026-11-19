package org.cabetus

import org.cabetus.domain.LetusClassLinkResolver
import org.cabetus.domain.LetusClassLinkResolver.Course
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LetusClassLinkResolverTest {
    private val base = "https://letus.ed.tus.ac.jp/course/view.php?id="

    @Test fun officialCodeWinsOverName() {
        assertEquals(base + "208226", LetusClassLinkResolver.resolve("9943439", "ReadingⅠb B組", listOf(
            Course("ReadingⅠb B組", base + "77"), Course("ReadingⅠbB組 （９９４３４３９）", base + "208226"),
        )))
    }

    @Test fun matchesAlphanumericCodeAndDeduplicatesSameDestination() {
        assertEquals(base + "42", LetusClassLinkResolver.resolve("994321G", "授業", listOf(
            Course("講義 (994321G)", base + "42"), Course("講義 (994321G)", base + "42&section=1"),
        )))
    }

    @Test fun ambiguousCodesDoNotFallBackToName() {
        assertNull(LetusClassLinkResolver.resolve("994321G", "授業", listOf(
            Course("講義 (994321G)", base + "42"), Course("別講義 (994321G)", base + "43"), Course("授業", base + "44"),
        )))
    }

    @Test fun wholeNormalizedNameMatchesOnlyWhenUnique() {
        val a = Course("物理 ２", base + "42")
        assertEquals(base + "42", LetusClassLinkResolver.resolve("X", "物理　2", listOf(a)))
        assertNull(LetusClassLinkResolver.resolve("X", "物理 2", listOf(a, a.copy(url = base + "43"))))
    }

    @Test fun rejectsInvalidDestinationsAndCodeSubstrings() {
        for (url in listOf(base + "0", base + "42&id=43", base + "42&%69d=43",
            base.replace("https:", "http:") + "42", base.replace(".ac.jp", ".ac.jp.example.org") + "42",
            "https://letus.ed.tus.ac.jp/", base.replace(".ac.jp/", ".ac.jp:444/") + "42")) {
            assertNull(LetusClassLinkResolver.resolve("X", "授業", listOf(Course("授業", url))))
        }
        assertNull(LetusClassLinkResolver.resolve("994321G", "授業", listOf(Course("講義 (1994321G)", base + "42"))))
        assertNull(LetusClassLinkResolver.resolve("X", "授業", emptyList()))
    }
}
