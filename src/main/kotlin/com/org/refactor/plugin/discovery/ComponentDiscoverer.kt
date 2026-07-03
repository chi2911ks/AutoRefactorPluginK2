package com.org.refactor.plugin.discovery

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.*
import com.org.refactor.plugin.model.*

class ComponentDiscoverer(private val project: Project) {

    fun discover(index: ProjectIndex): List<ComponentInfo> {
        return ReadAction.compute<List<ComponentInfo>, RuntimeException> {
            doDiscover(index)
        }
    }

    private fun doDiscover(index: ProjectIndex): List<ComponentInfo> {
        val components = mutableListOf<ComponentInfo>()

        for (sourceFile in index.allKotlinFiles + index.allJavaFiles) {
            try {
                val psiClasses = loadClasses(sourceFile.absolutePath)
                for (psiClass in psiClasses) {
                    if (isGenerated(psiClass)) continue
                    if (isExcluded(psiClass)) continue

                    val match = findMatch(psiClass)
                    if (match != null) {
                        components.add(ComponentInfo(
                            file = sourceFile,
                            className = psiClass.name ?: "Anonymous",
                            superClass = match.superFqn,
                            superClassShort = match.superFqn.substringAfterLast('.'),
                            packageName = psiClass.qualifiedName?.substringBeforeLast('.') ?: "",
                            componentType = match.type,
                            isAbstract = psiClass.isInterface || psiClass.hasModifierProperty(PsiModifier.ABSTRACT),
                        ))
                    }
                }
            } catch (_: Exception) {}
        }

        return components
    }

    private data class Match(val type: ComponentType, val superFqn: String)

    private fun findMatch(psiClass: PsiClass): Match? {
        // Walk the (single) superclass chain to identify the Android base type and capture its FQN.
        // K2-native: light classes resolve correctly under the Kotlin plugin dependency.
        var current: PsiClass? = psiClass.superClass ?: return null

        while (current != null) {
            val qn = current.qualifiedName ?: current.name ?: ""
            val name = current.name ?: ""

            // Check each type
            if (qn in AndroidComponentTypes.ACTIVITY_SUPERCLASSES ||
                name == "Activity" || name == "AppCompatActivity" || name == "ComponentActivity" ||
                name == "FragmentActivity") {
                return Match(ComponentType.ACTIVITY, qn)
            }
            if (qn == "com.google.android.material.bottomsheet.BottomSheetDialogFragment" ||
                name == "BottomSheetDialogFragment") {
                return Match(ComponentType.BOTTOM_SHEET_DIALOG_FRAGMENT, qn)
            }
            if (qn in AndroidComponentTypes.DIALOG_FRAGMENT_SUPERCLASSES ||
                (name == "DialogFragment" && qn.contains("fragment"))) {
                return Match(ComponentType.DIALOG_FRAGMENT, qn)
            }
            if (qn in AndroidComponentTypes.FRAGMENT_SUPERCLASSES ||
                (name == "Fragment" && !qn.contains("Dialog"))) {
                return Match(ComponentType.FRAGMENT, qn)
            }
            if (qn in AndroidComponentTypes.DIALOG_SUPERCLASSES ||
                name == "Dialog" || name == "AppCompatDialog") {
                return Match(ComponentType.DIALOG, qn)
            }

            current = current.superClass
        }
        return null
    }

    private fun loadClasses(path: String): List<PsiClass> {
        val vFile = LocalFileSystem.getInstance().findFileByPath(path) ?: return emptyList()
        val psiFile = PsiManager.getInstance(project).findFile(vFile)
            as? PsiClassOwner ?: return emptyList()
        return psiFile.classes.toList()
    }

    private fun isExcluded(psiClass: PsiClass): Boolean {
        var current: PsiClass? = psiClass.superClass ?: return false
        while (current != null) {
            val qn = current.qualifiedName ?: ""
            if (AndroidComponentTypes.isExcludedSuperclass(qn)) return true
            current = current.superClass
        }
        return false
    }

    private fun isGenerated(psiClass: PsiClass): Boolean {
        val path = psiClass.containingFile?.virtualFile?.path ?: return false
        return path.contains("build/generated") ||
            path.contains("/generated/") ||
            psiClass.name == "R" || psiClass.name == "BuildConfig" ||
            psiClass.qualifiedName?.startsWith("androidx.databinding") == true ||
            psiClass.qualifiedName?.startsWith("androidx.viewbinding") == true
    }
}
