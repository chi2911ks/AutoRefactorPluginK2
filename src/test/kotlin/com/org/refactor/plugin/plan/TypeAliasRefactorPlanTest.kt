package com.org.refactor.plugin.plan

import com.org.refactor.plugin.model.RefactorOptions
import com.org.refactor.plugin.model.TypeAliasInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TypeAliasRefactorPlanTest {

    @Test
    fun `typealias uses shared class suffix transformation`() {
        val options = RefactorOptions(
            suffixToAdd = "Dn12",
            suffixToRemove = "Inv124",
            refactorClasses = false,
            refactorDrawables = false,
            refactorLayouts = false,
        )

        val plan = RefactorPlanGenerator(options).generate(
            emptyList(), emptyList(), emptyList(), emptyList(),
            listOf(alias("ChapterAudioLaneInvInv124")),
        )

        val rename = plan.typeAliasRenames.single()
        assertEquals("ChapterAudioLaneInvDn12", rename.newName)
        assertTrue(rename.checked)
    }

    @Test
    fun `existing alias target is skipped`() {
        val options = RefactorOptions(
            suffixToAdd = "Dn12",
            refactorClasses = false,
            refactorDrawables = false,
            refactorLayouts = false,
        )

        val plan = RefactorPlanGenerator(options).generate(
            emptyList(), emptyList(), emptyList(), emptyList(),
            listOf(alias("Track"), alias("TrackDn12", offset = 20)),
        )

        val rename = plan.typeAliasRenames.single()
        assertFalse(rename.checked)
        assertTrue(rename.skipReason.orEmpty().contains("already exists"))
    }

    @Test
    fun `typealias option can be disabled independently`() {
        val options = RefactorOptions(
            suffixToAdd = "Dn12",
            refactorClasses = false,
            refactorTypeAliases = false,
            refactorDrawables = false,
            refactorLayouts = false,
        )

        val plan = RefactorPlanGenerator(options).generate(
            emptyList(), emptyList(), emptyList(), emptyList(), listOf(alias("Callback")),
        )

        assertTrue(plan.typeAliasRenames.isEmpty())
    }

    private fun alias(name: String, offset: Int = 0) = TypeAliasInfo(
        name = name,
        fqn = "demo.$name",
        sourceFile = "Aliases.kt",
        declarationOffset = offset,
        ownerScope = "demo",
    )
}
