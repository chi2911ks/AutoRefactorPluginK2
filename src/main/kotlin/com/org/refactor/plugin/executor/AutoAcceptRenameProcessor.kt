package com.org.refactor.plugin.executor

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.refactoring.rename.naming.AutomaticRenamer
import com.intellij.refactoring.rename.naming.AutomaticRenamerFactory
import com.intellij.usageView.UsageInfo

/** Rename processor that accepts all related rename suggestions without opening a dialog. */
internal class AutoAcceptRenameProcessor(
    project: Project,
    element: PsiElement,
    newName: String,
    private val explicitElements: Collection<PsiElement> = emptyList(),
    private val acceptAutomaticRenames: Boolean = true,
) : RenameProcessor(project, element, newName, false, false) {

    private val psiManager = PsiManager.getInstance(project)

    override fun isPreviewUsages(usages: Array<UsageInfo>): Boolean = false

    override fun showAutomaticRenamingDialog(renamer: AutomaticRenamer): Boolean =
        AutomaticRenamePolicy.acceptAll(renamer) { candidate ->
            acceptAutomaticRenames && explicitElements.none { explicit ->
                psiManager.areElementsEquivalent(explicit, candidate)
            }
        }

    /** Matches RenameRefactoring.respectAllAutomaticRenames without using its default processor. */
    fun respectAllAutomaticRenames(
        elements: Collection<PsiElement>,
        includeTestRenames: Boolean = false,
    ) {
        for (factory in AutomaticRenamerFactory.EP_NAME.extensionList) {
            // Test factories parse test frameworks and may traverse unitTest/androidTest PSI for
            // every production class. Tests are not rename targets in this plugin; ordinary test
            // usages are still updated by RenameProcessor's reference search.
            if (!includeTestRenames && factory.javaClass.simpleName.contains("TestRenamer")) continue
            if (factory.optionName != null && elements.any(factory::isApplicable)) {
                addRenamerFactory(factory)
            }
        }
    }
}
