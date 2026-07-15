package com.org.refactor.plugin.executor

import com.org.refactor.plugin.model.AndroidResourceType
import com.org.refactor.plugin.model.ResourceRename
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ResourceTextUpdaterTest {

    @Test
    fun `updates contextual Kotlin resource and view binding references`() {
        val rename = layoutRename()
        val source = """
            import demo.databinding.MainActivityInv124Binding
            val binding: MainActivityInv124Binding
            val layout = R.layout.main_activity_inv124
            val untouched = "main_activity_inv124"
        """.trimIndent()

        val result = ResourceTextUpdater.update(source, "kt", listOf(rename))

        assertTrue("MainActivityDn12Binding" in result.text)
        assertTrue("R.layout.main_activity_dn12" in result.text)
        assertTrue("\"main_activity_inv124\"" in result.text)
        assertEquals(3, result.replacements)
        assertFalse(ResourceTextUpdater.containsOldReference(result.text, "kt", rename))
    }

    @Test
    fun `updates XML references but not similarly prefixed names`() {
        val rename = ResourceRename(
            type = AndroidResourceType.DRAWABLE,
            moduleName = "app",
            oldName = "ic_search",
            newName = "ic_search_inv124",
            variants = emptyList(),
        )
        val source = "@drawable/ic_search @drawable/ic_search_large"

        val result = ResourceTextUpdater.update(source, "xml", listOf(rename))

        assertEquals("@drawable/ic_search_inv124 @drawable/ic_search_large", result.text)
        assertEquals(1, result.replacements)
    }

    @Test
    fun `updates Kotlin and XML string references`() {
        val rename = ResourceRename(
            type = AndroidResourceType.STRING,
            moduleName = "app",
            oldName = "inv069_title",
            newName = "inv125_title",
            variants = emptyList(),
        )

        val kotlin = ResourceTextUpdater.update("getString(R.string.inv069_title)", "kt", listOf(rename))
        val xml = ResourceTextUpdater.update("android:text=\"@string/inv069_title\"", "xml", listOf(rename))

        assertEquals("getString(R.string.inv125_title)", kotlin.text)
        assertEquals("android:text=\"@string/inv125_title\"", xml.text)
    }

    @Test
    fun `updates color and hierarchical style references`() {
        val renames = listOf(
            ResourceRename(AndroidResourceType.COLOR, "app", "primary", "inv125_primary", emptyList()),
            ResourceRename(
                AndroidResourceType.STYLE,
                "app",
                "AppTheme.AdAttribution",
                "inv125_AppTheme.AdAttribution",
                emptyList(),
            ),
        )

        val kotlin = ResourceTextUpdater.update(
            "R.color.primary; R.style.AppTheme_AdAttribution",
            "kt",
            renames,
        )
        val xml = ResourceTextUpdater.update(
            "@color/primary @style/AppTheme.AdAttribution parent=\"AppTheme.AdAttribution\"",
            "xml",
            renames,
        )

        assertEquals("R.color.inv125_primary; R.style.inv125_AppTheme_AdAttribution", kotlin.text)
        assertEquals(
            "@color/inv125_primary @style/inv125_AppTheme.AdAttribution " +
                "parent=\"inv125_AppTheme.AdAttribution\"",
            xml.text,
        )
    }

    private fun layoutRename() = ResourceRename(
        type = AndroidResourceType.LAYOUT,
        moduleName = "app",
        oldName = "main_activity_inv124",
        newName = "main_activity_dn12",
        variants = emptyList(),
        oldBindingName = "MainActivityInv124Binding",
        newBindingName = "MainActivityDn12Binding",
    )
}
