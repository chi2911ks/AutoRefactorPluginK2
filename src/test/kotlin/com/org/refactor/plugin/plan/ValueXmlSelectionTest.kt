package com.org.refactor.plugin.plan

import com.org.refactor.plugin.model.RefactorOptions
import com.org.refactor.plugin.model.StringResourceInfo
import com.org.refactor.plugin.model.ValueXmlFileInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ValueXmlSelectionTest {
    private val generator = RefactorPlanGenerator(
        RefactorOptions(suffixToAdd = "INV125", suffixToRemove = "INV069"),
    )

    @Test
    fun `groups the same values filename across all qualifiers and selects it by default`() {
        val plan = generator.generate(
            components = emptyList(),
            symbols = emptyList(),
            allKotlinPaths = emptyList(),
            valueXmlFiles = listOf(
                file("custom_strings.xml", "values", "res/values/custom_strings.xml"),
                file("custom_strings.xml", "values-night", "res/values-night/custom_strings.xml"),
                file("custom_strings.xml", "values-v31", "res/values-v31/custom_strings.xml"),
                file("empty_custom.xml", "values", "res/values/empty_custom.xml"),
            ),
        )

        assertEquals(2, plan.valueXmlFileGroups.size)
        val custom = plan.valueXmlFileGroups.single { it.fileName == "custom_strings.xml" }
        assertTrue(custom.checked)
        assertEquals(3, custom.variants.size)
        assertTrue(plan.valueXmlFileGroups.any { it.fileName == "empty_custom.xml" })
    }

    @Test
    fun `unchecking one file group blocks the whole logical value key and rechecking restores it`() {
        val plan = generator.generate(
            components = emptyList(),
            symbols = emptyList(),
            allKotlinPaths = emptyList(),
            strings = listOf(
                string("res/values/strings.xml", "values"),
                string("res/values-night/custom_strings.xml", "values-night"),
            ),
            valueXmlFiles = listOf(
                file("strings.xml", "values", "res/values/strings.xml"),
                file("custom_strings.xml", "values-night", "res/values-night/custom_strings.xml"),
            ),
        )
        val unchecked = plan.valueXmlFileGroups.map { group ->
            if (group.fileName == "custom_strings.xml") group.copy(checked = false) else group
        }

        val excluded = ValueXmlSelection.apply(plan, unchecked)
        val blockedRename = excluded.stringResourceRenames.single()
        assertFalse(blockedRename.checked)
        assertFalse(blockedRename.selectable)
        assertTrue(blockedRename.blockedByValueFileSelection)

        val restored = ValueXmlSelection.apply(
            excluded,
            unchecked.map { it.copy(checked = true) },
        ).stringResourceRenames.single()
        assertTrue(restored.checked)
        assertTrue(restored.selectable)
        assertFalse(restored.blockedByValueFileSelection)
        assertNull(restored.skipReason)
    }

    private fun string(path: String, directory: String) = StringResourceInfo(
        name = "inv069_title",
        moduleName = "app.main",
        sourceFile = path,
        valuesDirectory = directory,
    )

    private fun file(fileName: String, directory: String, path: String) = ValueXmlFileInfo(
        moduleName = "app.main",
        fileName = fileName,
        sourceFile = path,
        valuesDirectory = directory,
    )
}
