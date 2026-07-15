package com.org.refactor.plugin.plan

import com.org.refactor.plugin.model.RefactorOptions
import com.org.refactor.plugin.model.StringResourceInfo
import com.org.refactor.plugin.model.AndroidResourceType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StringResourceRefactorPlanTest {

    private val generator = RefactorPlanGenerator(
        RefactorOptions(suffixToAdd = "INV125", suffixToRemove = "INV069"),
    )

    @Test
    fun `removes text anywhere then adds target prefix`() {
        assertEquals("inv125_tv_content", generator.transformStringResourceName("inv069_tv_content"))
        assertEquals("inv125_tv_content", generator.transformStringResourceName("tv_inv069_content"))
        assertEquals("inv125_tv_content", generator.transformStringResourceName("tv_content"))
        assertEquals("inv125_tv_content", generator.transformStringResourceName("inv125_tv_content"))
    }

    @Test
    fun `preserves style hierarchy and casing while adding prefix`() {
        assertEquals(
            "inv125_AppTheme.AdAttribution",
            generator.transformValueResourceName(AndroidResourceType.STYLE, "inv069_AppTheme.AdAttribution"),
        )
        assertEquals(
            "inv125_ThemeText.Subtext.12",
            generator.transformValueResourceName(AndroidResourceType.STYLE, "ThemeText.Subtext.12"),
        )
    }

    @Test
    fun `groups all locale variants and selects valid strings by default`() {
        val plan = generator.generate(
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
            listOf(
                string("inv069_title", "values", "values/strings.xml"),
                string("inv069_title", "values-ja", "values-ja/strings.xml"),
                string("inv069_title", "values-vi", "values-vi/strings.xml"),
            ),
        )

        val rename = plan.stringResourceRenames.single()
        assertEquals("inv125_title", rename.newName)
        assertEquals(3, rename.variants.size)
        assertTrue(rename.checked)
        assertTrue(rename.selectable)
    }

    @Test
    fun `target key in one locale locks the logical string`() {
        val plan = generator.generate(
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
            listOf(
                string("inv069_title", "values", "values/strings.xml"),
                string("inv125_title", "values-ja", "values-ja/strings.xml"),
            ),
        )

        val rename = plan.stringResourceRenames.single()
        assertFalse(rename.checked)
        assertFalse(rename.selectable)
        assertTrue(rename.skipReason.orEmpty().contains("already exists"))
    }

    @Test
    fun `read only locale locks the logical string`() {
        val plan = generator.generate(
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
            listOf(string("inv069_title", "values-ja", "values-ja/strings.xml", isWritable = false)),
        )

        val rename = plan.stringResourceRenames.single()
        assertFalse(rename.checked)
        assertFalse(rename.selectable)
        assertTrue(rename.skipReason.orEmpty().contains("read-only"))
    }

    private fun string(
        name: String,
        directory: String,
        file: String,
        isWritable: Boolean = true,
    ) = StringResourceInfo(
        name = name,
        moduleName = "app.main",
        sourceFile = file,
        valuesDirectory = directory,
        isWritable = isWritable,
    )
}
