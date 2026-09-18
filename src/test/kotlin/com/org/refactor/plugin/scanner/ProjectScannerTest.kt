package com.org.refactor.plugin.scanner

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProjectScannerTest {

    @Test
    fun `skips test source sets but keeps production source sets`() {
        assertTrue(ProjectScanner.isTestSourcePath("D:/project/app/src/test/java/sample/Feature.java"))
        assertTrue(ProjectScanner.isTestSourcePath("D:/project/app/src/debugUnitTest/kotlin/sample/Feature.kt"))
        assertTrue(ProjectScanner.isTestSourcePath("D:/project/app/src/androidTest/java/sample/Feature.java"))
        assertTrue(ProjectScanner.isTestSourcePath("D:/project/app/src/testFixtures/kotlin/sample/Feature.kt"))
        assertFalse(ProjectScanner.isTestSourcePath("D:/project/app/src/main/java/sample/Feature.java"))
        assertFalse(ProjectScanner.isTestSourcePath("D:/project/app/src/debug/kotlin/sample/Feature.kt"))
    }
}
