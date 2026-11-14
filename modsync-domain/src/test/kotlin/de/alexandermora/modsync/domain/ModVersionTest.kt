package de.alexandermora.modsync.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Verifies mod-aware version ordering and Maven-style range matching. */
class ModVersionTest {
    private fun order(left: String, right: String): VersionOrder = checkNotNull(ModVersion.parse(left)).order(checkNotNull(ModVersion.parse(right)))

    @Test fun `numeric segments compare numerically not lexicographically`() {
        assertEquals(VersionOrder.NEWER, order("1.10.0", "1.9.9"))
        assertEquals(VersionOrder.OLDER, order("2.0", "10.0"))
    }

    @Test fun `trailing zero segments and build metadata do not change the order`() {
        assertEquals(VersionOrder.EQUAL, order("1.0", "1.0.0.0"))
        assertEquals(VersionOrder.EQUAL, order("1.2.3+build.7", "1.2.3+other"))
        assertEquals(VersionOrder.EQUAL, order("v1.2.3", "1.2.3"))
    }

    @Test fun `prereleases sort before the release in qualifier order`() {
        assertEquals(VersionOrder.OLDER, order("1.0.0-alpha.1", "1.0.0-beta.1"))
        assertEquals(VersionOrder.OLDER, order("1.0.0-beta.2", "1.0.0-rc.1"))
        assertEquals(VersionOrder.OLDER, order("1.0.0-rc.1", "1.0.0"))
        assertEquals(VersionOrder.NEWER, order("1.0.0", "1.0.0-SNAPSHOT"))
        assertEquals(VersionOrder.OLDER, order("1.0.0-beta.2", "1.0.0-beta.10"))
        assertEquals(VersionOrder.OLDER, order("1.0.0-rc1", "1.0.1"))
    }

    @Test fun `minecraft prefixed versions compare by their mod part when the prefix matches`() {
        assertEquals(VersionOrder.NEWER, order("1.21.1-3.2.0", "1.21.1-3.1.9"))
        assertEquals(VersionOrder.NEWER, order("1.21.1-3.10.0", "1.21.1-3.9.0"))
    }

    @Test fun `post release qualifiers are newer than the base release`() {
        assertEquals(VersionOrder.NEWER, order("1.0.0-patch1", "1.0.0"))
    }

    @Test fun `a post release qualifier cannot be placed against a numeric segment`() {
        assertEquals(VersionOrder.INCOMPARABLE, order("1.0.0-patch1", "1.0.0.1"))
    }

    @Test fun `unparseable versions are rejected instead of guessed`() {
        assertNull(ModVersion.parse(null))
        assertNull(ModVersion.parse(""))
        assertNull(ModVersion.parse("   "))
        assertNull(ModVersion.parse("\${file.jarVersion}"))
        assertNull(ModVersion.parse("final"))
        assertNull(ModVersion.parse("1.0.0-unknownword"))
        assertNull(ModVersion.parse("99999999999999999999.1"))
    }

    @Test fun `prerelease flag follows the qualifier`() {
        assertTrue(checkNotNull(ModVersion.parse("1.0.0-rc.2")).isPrerelease)
        assertFalse(checkNotNull(ModVersion.parse("1.0.0")).isPrerelease)
        assertFalse(checkNotNull(ModVersion.parse("1.0.0-patch1")).isPrerelease)
    }

    @Test fun `raw text is preserved for explanations`() {
        assertEquals("1.21.1-3.2.0", checkNotNull(ModVersion.parse(" 1.21.1-3.2.0 ")).raw)
    }

    @Test fun `ranges follow Maven interval semantics`() {
        val range = assertNotNull(VersionRange.parse("[1.21,1.21.2)"))
        assertEquals(true, range.contains(checkNotNull(ModVersion.parse("1.21.1"))))
        assertEquals(true, range.contains(checkNotNull(ModVersion.parse("1.21"))))
        assertEquals(false, range.contains(checkNotNull(ModVersion.parse("1.21.2"))))
        assertEquals(false, range.contains(checkNotNull(ModVersion.parse("1.20.6"))))
    }

    @Test fun `open ended exact and multiple ranges are supported`() {
        assertEquals(true, assertNotNull(VersionRange.parse("[21.1,)")).contains(checkNotNull(ModVersion.parse("21.1.172"))))
        assertEquals(true, assertNotNull(VersionRange.parse("(,1.0]")).contains(checkNotNull(ModVersion.parse("1.0"))))
        assertEquals(true, assertNotNull(VersionRange.parse("[1.21.1]")).contains(checkNotNull(ModVersion.parse("1.21.1"))))
        assertEquals(false, assertNotNull(VersionRange.parse("[1.21.1]")).contains(checkNotNull(ModVersion.parse("1.21.2"))))
        val several = assertNotNull(VersionRange.parse("[1.20,1.21),[1.21.1,1.22)"))
        assertEquals(true, several.contains(checkNotNull(ModVersion.parse("1.21.1"))))
        assertEquals(false, several.contains(checkNotNull(ModVersion.parse("1.21"))))
    }

    @Test fun `unsupported range syntax is not guessed`() {
        assertNull(VersionRange.parse("1.21.1"))
        assertNull(VersionRange.parse(""))
        assertNull(VersionRange.parse("[1.0,2.0"))
        assertNull(VersionRange.parse("[x-y,2.0)"))
        assertNull(VersionRange.parse(null))
    }

    @Test fun `containment is unknown when the version cannot be ordered against a bound`() {
        val range = assertNotNull(VersionRange.parse("[1.0.0.1,2.0)"))
        assertNull(range.contains(checkNotNull(ModVersion.parse("1.0.0-patch1"))))
    }
}
