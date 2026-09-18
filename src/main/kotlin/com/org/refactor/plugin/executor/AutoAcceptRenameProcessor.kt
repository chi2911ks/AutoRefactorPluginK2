package com.org.refactor.plugin.executor

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.refactoring.rename.naming.AutomaticRenamer
import com.intellij.refactoring.rename.naming.AutomaticRenamerFactory
import com.intellij.usageView.UsageInfo

/** Rename processor that accepts all related rename suggestions without opening a dialog. */
internal class AutoAcceptRenameProcessor(
    project: Project,
    element: PsiElement,
    newName: String,
) : RenameProcessor(project, element, newName, false, false) {

    override fun isPreviewUsages(usages: Array<UsageInfo>): Boolean = false

    override fun showAutomaticRenamingDialog(renamer: AutomaticRenamer): Boolean =
        AutomaticRenamePolicy.acceptAll(renamer)

    /** Matches RenameRefactoring.respectAllAutomaticRenames without using its default processor. */
    fun respectAllAutomaticRenames(elements: Collection<PsiElement>) {
        for (factory in AutomaticRenamerFactory.EP_NAME.extensionList) {
            if (factory.optionName != null && elements.any(factory::isApplicable)) {
                addRenamerFactory(factory)
            }
        }
    }
}
