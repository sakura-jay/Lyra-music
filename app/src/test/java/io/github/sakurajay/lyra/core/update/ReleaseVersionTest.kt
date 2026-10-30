package io.github.sakurajay.lyra.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseVersionTest {

    @Test
    fun parsesStrictThreePartTags() {
        assertEquals(ReleaseVersion(0, 10, 0), ReleaseVersion.fromTag("v0.10.0"))
        assertEquals(ReleaseVersion(12, 3, 4), ReleaseVersion.fromTag("v12.3.4"))
        assertEquals(ReleaseVersion(0, 10, 0), ReleaseVersion.fromName("0.10.0"))
    }

    @Test
    fun rejectsNonReleaseTags() {
        assertNull(ReleaseVersion.fromTag("0.10.0"))
        assertNull(ReleaseVersion.fromTag("v0.10"))
        assertNull(ReleaseVersion.fromTag("v0.10.0-beta"))
        assertNull(ReleaseVersion.fromTag("v0.10.0.1"))
        assertNull(ReleaseVersion.fromTag(""))
    }

    @Test
    fun comparesEachPartNumerically() {
        assertTrue(ReleaseVersion(0, 9, 10) < ReleaseVersion(0, 10, 0))
        assertTrue(ReleaseVersion(1, 0, 0) > ReleaseVersion(0, 99, 99))
        assertEquals(0, ReleaseVersion(1, 2, 3).compareTo(ReleaseVersion(1, 2, 3)))
    }
}
