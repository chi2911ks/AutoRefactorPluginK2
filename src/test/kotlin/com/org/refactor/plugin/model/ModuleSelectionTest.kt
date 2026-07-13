package com.org.refactor.plugin.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ModuleSelectionTest {

    @Test
    fun `groups IntelliJ main source set under logical module`() {
        assertEquals(
            "4_ShortDrama_V2.app-data",
            ModuleSelection.logicalName("4_ShortDrama_V2.app-data.main"),
        )
    }

    @Test
    fun `keeps logical module name unchanged`() {
        assertEquals(
            "4_ShortDrama_V2.app-domain",
            ModuleSelection.logicalName("4_ShortDrama_V2.app-domain"),
        )
    }

    @Test
    fun `groups platform source sets and displays short module name`() {
        val logicalName = ModuleSelection.logicalName("4_ShortDrama_V2.shared.androidMain")

        assertEquals("4_ShortDrama_V2.shared", logicalName)
        assertEquals("shared", ModuleSelection.shortDisplayName(logicalName))
    }
}
