package com.org.refactor.plugin.plan

import com.org.refactor.plugin.model.RefactorPlan
import com.org.refactor.plugin.model.ValueXmlFileGroup

internal object ValueXmlSelection {
    fun apply(plan: RefactorPlan, groups: List<ValueXmlFileGroup>): RefactorPlan {
        val excludedPaths = groups.asSequence()
            .filterNot { it.checked }
            .flatMap { it.variants.asSequence() }
            .map { normalizePath(it.sourceFile) }
            .toSet()
        val renames = plan.stringResourceRenames.map { rename ->
            val excluded = rename.variants.any { normalizePath(it.sourceFile) in excludedPaths }
            when {
                excluded && rename.blockedByValueFileSelection -> rename
                excluded && rename.selectable -> rename.copy(
                    checked = false,
                    selectable = false,
                    skipReason = "Excluded by values XML selection",
                    blockedByValueFileSelection = true,
                    checkedBeforeValueFileExclusion = rename.checked,
                )
                !excluded && rename.blockedByValueFileSelection -> rename.copy(
                    checked = rename.checkedBeforeValueFileExclusion ?: true,
                    selectable = true,
                    skipReason = null,
                    blockedByValueFileSelection = false,
                    checkedBeforeValueFileExclusion = null,
                )
                else -> rename
            }
        }
        return plan.copy(valueXmlFileGroups = groups, stringResourceRenames = renames)
    }

    private fun normalizePath(path: String): String = path.replace('\\', '/').lowercase()
}
