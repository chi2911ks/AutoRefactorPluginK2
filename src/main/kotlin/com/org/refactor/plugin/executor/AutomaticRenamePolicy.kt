package com.org.refactor.plugin.executor

import com.intellij.refactoring.rename.naming.AutomaticRenamer
import com.intellij.psi.PsiNamedElement

/** Applies every IntelliJ automatic rename suggestion without showing its selection dialog. */
internal object AutomaticRenamePolicy {

    fun acceptAll(
        renamer: AutomaticRenamer,
        shouldRename: (PsiNamedElement) -> Boolean = { true },
    ): Boolean {
        for (element in renamer.elements.toList()) {
            if (shouldRename(element)) {
                renamer.getNewName(element)?.let { suggestedName ->
                    renamer.setRename(element, suggestedName)
                }
            } else {
                // RenameProcessor reads getNewName() after this callback. Skipping
                // setRename() leaves an existing suggestion selected, so explicitly
                // remove it from the automatic renamer.
                renamer.doNotRename(element)
            }
        }
        return true
    }
}
