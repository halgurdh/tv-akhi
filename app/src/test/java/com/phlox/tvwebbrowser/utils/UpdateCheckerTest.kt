package com.phlox.tvwebbrowser.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun versionsCompareByNumber() {
        assertTrue(UpdateChecker.compareVersions("1.1.0", "1.0.10") > 0)
        assertTrue(UpdateChecker.compareVersions("1.0.10", "1.0.9") > 0)
        assertTrue(UpdateChecker.compareVersions("1.0.9", "1.1.0") < 0)
        assertEquals(0, UpdateChecker.compareVersions("1.1", "1.1.0"))
        assertEquals(0, UpdateChecker.compareVersions("1.1.0", "1.1.0"))
    }
}
