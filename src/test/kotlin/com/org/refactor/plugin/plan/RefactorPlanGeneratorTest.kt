package com.org.refactor.plugin.plan

import com.org.refactor.plugin.model.RefactorOptions
import com.org.refactor.plugin.model.SymbolInfo
import com.org.refactor.plugin.model.SymbolKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RefactorPlanGeneratorTest {

    @Test
    fun `defaults to class and resource refactor`() {
        val options = RefactorOptions(suffixToAdd = "Ref")

        assertTrue(options.refactorClasses)
        assertFalse(options.refactorFunctions)
        assertFalse(options.refactorVariables)
        assertTrue(options.refactorTypeAliases)
        assertTrue(options.refactorStrings)
        assertTrue(options.refactorDrawables)
        assertTrue(options.refactorLayouts)
        assertFalse(options.hasShuffleOperation)
        assertEquals(null, options.selectedModuleNames)
    }

    @Test
    fun `class-only options do not require symbol collection`() {
        val classOnly = RefactorOptions(
            suffixToAdd = "Ref",
            refactorTypeAliases = false,
            refactorStrings = false,
            refactorColors = false,
            refactorStyles = false,
            refactorDrawables = false,
            refactorLayouts = false,
        )

        assertFalse(classOnly.hasSymbolOperation)
        assertTrue(classOnly.copy(refactorFunctions = true).hasSymbolOperation)
        assertTrue(classOnly.copy(refactorVariables = true).hasSymbolOperation)
    }

    @Test
    fun `removes old suffix before adding shared suffix`() {
        val generator = RefactorPlanGenerator(
            RefactorOptions(suffixToAdd = "Inv125", suffixToRemove = "Inv124"),
        )

        assertEquals("MainActivityInv125", generator.transformName("MainActivityInv124"))
        assertEquals("loadUserInv125", generator.transformName("loadUser"))
        assertEquals("nameInv125", generator.transformName("nameInv124"))
    }

    @Test
    fun `does not append the target suffix twice`() {
        val generator = RefactorPlanGenerator(
            RefactorOptions(suffixToAdd = "Inv125", suffixToRemove = "Inv124"),
        )

        assertEquals("MainActivityInv125", generator.transformName("MainActivityInv125"))
    }

    @Test
    fun `removes all matching text anywhere without case sensitivity`() {
        val generator = RefactorPlanGenerator(
            RefactorOptions(suffixToAdd = "INV125", suffixToRemove = "inv069"),
        )

        assertEquals(
            "CoreRecyclerAdapterINV125",
            generator.transformName("CoreRecyclerINV069Adapter"),
        )
        assertEquals(
            "CoreAdapterINV125",
            generator.transformName("CoreInv069INV069Adapter"),
        )
    }

    @Test
    fun `target suffix check ignores case`() {
        val generator = RefactorPlanGenerator(
            RefactorOptions(suffixToAdd = "INV125", suffixToRemove = "inv069"),
        )

        assertEquals("CoreAdapterinv125", generator.transformName("CoreAdapterinv125"))
    }

    @Test
    fun `plans functions without requiring class rename`() {
        val options = RefactorOptions(
            suffixToAdd = "Ref",
            refactorClasses = false,
            refactorFunctions = true,
        )
        val symbol = SymbolInfo(
            name = "loadUser",
            fqn = "Sample.kt@10:loadUser",
            kind = SymbolKind.FUNCTION,
            psiElementClass = "KtNamedFunction",
            sourceFile = "Sample.kt",
            lineNumber = 1,
            declarationOffset = 10,
            parentClassFqn = "Sample.kt#0",
        )

        val plan = RefactorPlanGenerator(options).generate(emptyList(), listOf(symbol), listOf("Sample.kt"))

        assertTrue(plan.componentRenames.isEmpty())
        assertEquals("loadUserRef", plan.symbolRenames.single().newName)
    }

    @Test
    fun `shuffle-only plan targets all Kotlin files`() {
        val options = RefactorOptions(
            suffixToAdd = "",
            refactorClasses = false,
            refactorTypeAliases = false,
            refactorStrings = false,
            refactorColors = false,
            refactorStyles = false,
            refactorDrawables = false,
            refactorLayouts = false,
            shuffleFunctions = true,
        )

        val plan = RefactorPlanGenerator(options).generate(
            emptyList(),
            emptyList(),
            listOf("A.kt", "B.kt", "A.kt"),
        )

        assertEquals(listOf("A.kt", "B.kt"), plan.shuffleFilePaths)
    }

    @Test
    fun `shuffle targets remain Kotlin-only when Java refactors are selected`() {
        val options = RefactorOptions(
            suffixToAdd = "Ref",
            refactorClasses = false,
            refactorFunctions = true,
            refactorTypeAliases = false,
            refactorStrings = false,
            refactorColors = false,
            refactorStyles = false,
            refactorDrawables = false,
            refactorLayouts = false,
            shuffleFunctions = true,
        )
        val javaSymbol = SymbolInfo(
            name = "loadData",
            fqn = "Feature.java@10:loadData",
            kind = SymbolKind.FUNCTION,
            psiElementClass = "PsiMethod",
            sourceFile = "Feature.java",
            lineNumber = 1,
            declarationOffset = 10,
            parentClassFqn = "sample.Feature",
        )

        val plan = RefactorPlanGenerator(options).generate(
            emptyList(),
            listOf(javaSymbol),
            listOf("Other.kt"),
        )

        assertTrue(plan.shuffleFilePaths.isEmpty())
    }
}
