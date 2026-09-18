package com.org.refactor.plugin.executor

import com.intellij.refactoring.rename.naming.AutomaticRenamer

/** Applies every IntelliJ automatic rename suggestion without showing its selection dialog. */
internal object AutomaticRenamePolicy {

    fun acceptAll(renamer: AutomaticRenamer): Boolean {
        for (element in renamer.elements) {
            renamer.getNewName(element)?.let { suggestedName ->
                renamer.setRename(element, suggestedName)
            }
        }
        return true
    }
}
