package io.github.sakurajay.lyra.core.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {

    @Test
    fun ignoredVersionOnlySuppressesThatExactAutomaticPrompt() {
        val ignored = "0.10.0"
        assertTrue(UpdatePolicy.ignores(ignored, ReleaseVersion(0, 10, 0)))
        assertFalse(UpdatePolicy.ignores(ignored, ReleaseVersion(0, 10, 1)))
        assertFalse(UpdatePolicy.ignores(ignored, ReleaseVersion(0, 9, 7)))
        assertTrue(UpdatePolicy.shouldAutoPrompt(ignored, ReleaseVersion(0, 11, 0)))
    }

    @Test
    fun noIgnoredVersionAlwaysPrompts() {
        assertFalse(UpdatePolicy.ignores(null, ReleaseVersion(0, 10, 0)))
        assertTrue(UpdatePolicy.shouldAutoPrompt(null, ReleaseVersion(0, 10, 0)))
    }

    @Test
    fun manualCheckIgnoresThePolicy() {
        val version = ReleaseVersion(0, 10, 0)
        assertFalse(UpdatePolicy.shouldShowDialog(manual = false, ignoredVersion = "0.10.0", version = version))
        assertTrue(UpdatePolicy.shouldShowDialog(manual = true, ignoredVersion = "0.10.0", version = version))
    }
}
