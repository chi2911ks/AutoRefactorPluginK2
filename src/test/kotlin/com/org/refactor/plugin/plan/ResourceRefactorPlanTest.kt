package com.org.refactor.plugin.plan

import com.org.refactor.plugin.model.AndroidResourceFile
import com.org.refactor.plugin.model.AndroidResourceType
import com.org.refactor.plugin.model.RefactorOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ResourceRefactorPlanTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `resource suffix is lowercase and removes configured text`() {
        val replace = RefactorPlanGenerator(
            RefactorOptions(suffixToAdd = "Inv124", suffixToRemove = "Dn12"),
        )
        val append = RefactorPlanGenerator(
            RefactorOptions(suffixToAdd = "Dn12", suffixToRemove = "Other"),
        )

        assertEquals("activity_home_inv124", replace.transformResourceName("activity_home_dn12"))
        assertEquals("activity_home_xx14_dn12", append.transformResourceName("activity_home_xx14"))
        assertEquals("activity_home_dn12", append.transformResourceName("activity_home"))
    }

    @Test
    fun `resource removes text anywhere and normalizes underscores`() {
        val generator = RefactorPlanGenerator(
            RefactorOptions(suffixToAdd = "INV125", suffixToRemove = "INV069"),
        )

        assertEquals("bg_12_top_inv125", generator.transformResourceName("inv069_bg_12_top"))
        assertEquals("icon_bg_inv125", generator.transformResourceName("icon__inv069__bg"))
        assertEquals("bg_12_top_inv125", generator.transformResourceName("bg_12_top_inv125"))
    }

    @Test
    fun `view binding name follows layout PascalCase`() {
        val generator = RefactorPlanGenerator(RefactorOptions(suffixToAdd = "dn12"))

        assertEquals("MainActivityInv124Binding", generator.bindingClassName("main_activity_inv124"))
    }

    @Test
    fun `view binding ignored layouts do not plan a generated type rename`() {
        val layout = createResource(
            "layout",
            "activity_home.xml",
            content = "<FrameLayout tools:viewBindingIgnore=\"true\" />",
        )
        val options = RefactorOptions(
            suffixToAdd = "inv124",
            refactorClasses = false,
            refactorDrawables = false,
        )

        val rename = RefactorPlanGenerator(options).generate(
            emptyList(), emptyList(), emptyList(), listOf(layout),
        ).resourceRenames.single()

        assertEquals(null, rename.oldBindingName)
        assertEquals(null, rename.newBindingName)
    }

    @Test
    fun `qualifier variants are grouped into one layout rename`() {
        val base = createResource("layout", "activity_home.xml")
        val land = createResource("layout-land", "activity_home.xml")
        val options = RefactorOptions(
            suffixToAdd = "Inv124",
            refactorClasses = false,
            refactorDrawables = false,
        )

        val plan = RefactorPlanGenerator(options).generate(
            emptyList(), emptyList(), emptyList(), listOf(base, land),
        )

        val rename = plan.resourceRenames.single()
        assertTrue(rename.checked)
        assertEquals(2, rename.variants.size)
        assertEquals("activity_home_inv124.xml", rename.variants[0].newFileName)
        assertEquals("ActivityHomeInv124Binding", rename.newBindingName)
    }

    @Test
    fun `nine patch suffix is preserved`() {
        val drawable = createResource("drawable", "panel.9.png", ".9.png")
        val options = RefactorOptions(
            suffixToAdd = "Dn12",
            refactorClasses = false,
            refactorLayouts = false,
        )

        val plan = RefactorPlanGenerator(options).generate(
            emptyList(), emptyList(), emptyList(), listOf(drawable),
        )

        assertEquals("panel_dn12.9.png", plan.resourceRenames.single().variants.single().newFileName)
    }

    @Test
    fun `existing target skips logical resource`() {
        val source = createResource("layout", "activity_home.xml")
        createResource("layout", "activity_home_inv124.xml")
        val target = source.copy(
            absolutePath = source.absolutePath.replace("activity_home.xml", "activity_home_inv124.xml"),
            resourceName = "activity_home_inv124",
        )
        val options = RefactorOptions(
            suffixToAdd = "inv124",
            refactorClasses = false,
            refactorDrawables = false,
        )

        val plan = RefactorPlanGenerator(options).generate(
            emptyList(), emptyList(), emptyList(), listOf(source, target),
        )

        val rename = plan.resourceRenames.single()
        assertFalse(rename.checked)
        assertTrue(rename.skipReason.orEmpty().contains("already exists"))
    }

    private fun createResource(
        directory: String,
        fileName: String,
        suffix: String = ".${fileName.substringAfterLast('.')}",
        content: String = "<resource />",
    ): AndroidResourceFile {
        val folder = tempDir.resolve("app/src/main/res/$directory")
        Files.createDirectories(folder)
        val file = folder.resolve(fileName)
        Files.writeString(file, content)
        return AndroidResourceFile(
            absolutePath = file.toString().replace('\\', '/'),
            moduleName = "app.main",
            type = if (directory.startsWith("layout")) AndroidResourceType.LAYOUT else AndroidResourceType.DRAWABLE,
            resourceName = fileName.dropLast(suffix.length),
            qualifierDirectory = directory,
            fileSuffix = suffix,
        )
    }
}
