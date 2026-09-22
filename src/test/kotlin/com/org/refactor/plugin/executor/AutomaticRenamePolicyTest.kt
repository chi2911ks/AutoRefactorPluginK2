package com.org.refactor.plugin.executor

import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiNamedElement
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.refactoring.rename.naming.AutomaticRenamer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AutomaticRenamePolicyTest : BasePlatformTestCase() {

    @BeforeEach
    override fun setUp() {
        super.setUp()
    }

    @AfterEach
    override fun tearDown() {
        super.tearDown()
    }

    @Test
    fun `acceptAll selects every automatic rename suggestion`() {
        val file = myFixture.configureByText(
            "AutomaticRename.java",
            """
                package sample;

                class Primary {}
                class Inherited {}
            """.trimIndent(),
        ) as PsiJavaFile
        val declarations = ReadAction.compute<List<PsiClass>, RuntimeException> {
            file.classes.toList()
        }
        val suggestions = linkedMapOf<PsiNamedElement, String>(
            declarations[0] to "PrimaryRef",
            declarations[1] to "InheritedRef",
        )
        val renamer = RecordingAutomaticRenamer(suggestions)

        assertTrue(AutomaticRenamePolicy.acceptAll(renamer))
        assertEquals("PrimaryRef", renamer.getNewName(declarations[0]))
        assertEquals("InheritedRef", renamer.getNewName(declarations[1]))
    }

    @Test
    fun `acceptAll skips explicit batch elements`() {
        val file = myFixture.configureByText(
            "AutomaticRename.java",
            """
                package sample;

                class Primary {}
                class Inherited {}
            """.trimIndent(),
        ) as PsiJavaFile
        val declarations = ReadAction.compute<List<PsiClass>, RuntimeException> {
            file.classes.toList()
        }
        val suggestions = linkedMapOf<PsiNamedElement, String>(
            declarations[0] to "PrimaryRef",
            declarations[1] to "InheritedRef",
        )
        val renamer = RecordingAutomaticRenamer(suggestions)

        assertTrue(
            AutomaticRenamePolicy.acceptAll(renamer) { it !== declarations[0] },
        )
        assertNull(renamer.getNewName(declarations[0]))
        assertEquals("InheritedRef", renamer.getNewName(declarations[1]))
    }

    private class RecordingAutomaticRenamer(
        suggestions: Map<PsiNamedElement, String>,
    ) : AutomaticRenamer() {

        private val selected = suggestions.toMutableMap()

        init {
            myElements.addAll(suggestions.keys)
        }

        override fun getNewName(element: PsiNamedElement): String? = selected[element]

        override fun setRename(element: PsiNamedElement, newName: String) {
            selected[element] = newName
        }

        override fun doNotRename(element: PsiNamedElement) {
            selected.remove(element)
        }

        override fun getDialogTitle(): String = "Automatic rename"

        override fun getDialogDescription(): String = "Select related declarations"

        override fun entityName(): String = "declaration"
    }
}
