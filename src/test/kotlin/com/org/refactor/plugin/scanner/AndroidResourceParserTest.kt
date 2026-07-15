package com.org.refactor.plugin.scanner

import com.org.refactor.plugin.model.AndroidResourceType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AndroidResourceParserTest {

    @Test
    fun `parses qualifier layouts`() {
        val resource = AndroidResourceParser.parse(
            "D:/project/app/src/main/res/layout-land/activity_home.xml",
            "app.main",
        )

        assertEquals(AndroidResourceType.LAYOUT, resource?.type)
        assertEquals("layout-land", resource?.qualifierDirectory)
        assertEquals("activity_home", resource?.resourceName)
    }

    @Test
    fun `preserves nine patch suffix`() {
        val resource = AndroidResourceParser.parse(
            "D:/project/app/src/main/res/drawable-night/panel.9.png",
            "app.main",
        )

        assertEquals(AndroidResourceType.DRAWABLE, resource?.type)
        assertEquals("panel", resource?.resourceName)
        assertEquals(".9.png", resource?.fileSuffix)
    }

    @Test
    fun `ignores mipmap resources`() {
        assertNull(
            AndroidResourceParser.parse(
                "D:/project/app/src/main/res/mipmap/ic_launcher.webp",
                "app.main",
            ),
        )
    }
}
