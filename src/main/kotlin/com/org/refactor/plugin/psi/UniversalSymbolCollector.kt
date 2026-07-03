package com.org.refactor.plugin.psi

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.*
import com.org.refactor.plugin.model.*
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty

class UniversalSymbolCollector(private val project: Project) {

    fun collectAll(components: List<ComponentInfo>): List<SymbolInfo> {
        return ReadAction.compute<List<SymbolInfo>, RuntimeException> {
            components.flatMap { collectOne(it) }
        }
    }

    private fun collectOne(component: ComponentInfo): List<SymbolInfo> {
        val psiClass = resolveClass(component) ?: return emptyList()
        val symbols = mutableListOf<SymbolInfo>()
        val fqn = psiClass.qualifiedName ?: psiClass.name ?: ""

        // Pre-compute Kotlin property names to skip synthetic getters/setters from the light class.
        val kotlinPropNames = collectKotlinPropNames(component)

        // 1. Java light-class methods (skip getter/setter that shadows a Kotlin property).
        for (m in psiClass.methods) {
            if (m.isConstructor) continue
            val mn = m.name
            if (isCallback(mn)) continue
            if (m.findSuperMethods().isNotEmpty()) continue
            if (m.hasModifierProperty(PsiModifier.NATIVE)) continue
            if (isAccessorFor(mn, kotlinPropNames)) continue
            symbols.add(SymbolInfo(mn, "$fqn.$mn", SymbolKind.FUNCTION,
                "PsiMethod", component.file.absolutePath, getLine(m), parentClassFqn = fqn))
        }

        // 2. Java light-class fields.
        for (f in psiClass.fields) {
            if (f.hasModifierProperty(PsiModifier.STATIC)) continue
            symbols.add(SymbolInfo(f.name, "$fqn.${f.name}", SymbolKind.PROPERTY,
                "PsiField", component.file.absolutePath, getLine(f), parentClassFqn = fqn))
        }

        // 3. Kotlin PSI — real KtProperty / KtNamedFunction declarations.
        collectKotlinSymbols(component, fqn, symbols)

        // 4. Inner classes.
        for (inner in psiClass.innerClasses) {
            collectNestedJavaPsi(inner, component.file.absolutePath, symbols)
        }

        return symbols.distinctBy { it.name }
    }

    /** Real Kotlin property names declared in the target class body. */
    private fun collectKotlinPropNames(component: ComponentInfo): Set<String> {
        val names = mutableSetOf<String>()
        val ktClass = findKtClass(component) ?: return names
        for (m in ktClass.declarations) {
            if (m is KtProperty) m.name?.let { names.add(it) }
        }
        return names
    }

    private fun isAccessorFor(methodName: String, propNames: Set<String>): Boolean {
        val prefix = when {
            methodName.startsWith("is") -> "is"
            methodName.startsWith("get") -> "get"
            methodName.startsWith("set") -> "set"
            else -> return false
        }
        val stripped = methodName.removePrefix(prefix)
        val propName = stripped.replaceFirstChar { it.lowercase() }
        return propNames.contains(propName) ||
            propNames.any { it.replaceFirstChar { c -> c.uppercase() } == stripped }
    }

    private fun collectKotlinSymbols(component: ComponentInfo, fqn: String, out: MutableList<SymbolInfo>) {
        val ktClass = findKtClass(component) ?: return
        for (m in ktClass.declarations) {
            if (m !is KtNamedDeclaration) continue
            val name = m.name ?: continue
            if (name.isBlank() || isCallback(name)) continue
            if (out.any { it.name == name }) continue

            // Skip overrides — they are renamed transitively when the base declaration is renamed.
            if (m.modifierList?.text?.contains("override") == true) continue

            val kind = when (m) {
                is KtProperty -> {
                    // Skip computed properties (no backing field) — asked of the K2 frontend.
                    if (!K2Analysis.hasBackingField(m)) continue
                    SymbolKind.PROPERTY
                }
                is KtNamedFunction -> SymbolKind.FUNCTION
                else -> continue
            }

            out.add(SymbolInfo(name, "$fqn.$name", kind,
                m.javaClass.simpleName, component.file.absolutePath, getLine(m), parentClassFqn = fqn))
        }
    }

    private fun collectNestedJavaPsi(cls: PsiClass, file: String, out: MutableList<SymbolInfo>) {
        val fqn = cls.qualifiedName ?: ""
        for (m in cls.methods) {
            if (m.isConstructor || isCallback(m.name)) continue
            if (m.findSuperMethods().isNotEmpty()) continue
            out.add(SymbolInfo(m.name, "$fqn.${m.name}", SymbolKind.FUNCTION,
                "PsiMethod", file, getLine(m), parentClassFqn = fqn))
        }
        for (f in cls.fields) {
            if (f.hasModifierProperty(PsiModifier.STATIC)) continue
            out.add(SymbolInfo(f.name, "$fqn.${f.name}", SymbolKind.PROPERTY,
                "PsiField", file, getLine(f), parentClassFqn = fqn))
        }
        for (inner in cls.innerClasses) collectNestedJavaPsi(inner, file, out)
    }

    /** Locate the KtClass declaration matching the component inside its source file. */
    private fun findKtClass(component: ComponentInfo): KtClassOrObject? {
        val vFile = LocalFileSystem.getInstance()
            .findFileByPath(component.file.absolutePath) ?: return null
        val ktFile = PsiManager.getInstance(project).findFile(vFile) as? KtFile ?: return null
        return ktFile.declarations.filterIsInstance<KtClass>()
            .firstOrNull { it.name == component.className }
    }

    private fun resolveClass(component: ComponentInfo): PsiClass? {
        val vFile = LocalFileSystem.getInstance()
            .findFileByPath(component.file.absolutePath) ?: return null
        val psiFile = PsiManager.getInstance(project).findFile(vFile)
            as? PsiClassOwner ?: return null
        return psiFile.classes.firstOrNull { it.name == component.className }
    }

    private val cb = setOf(
        "onCreate","onStart","onResume","onPause","onStop","onDestroy",
        "onCreateView","onViewCreated","onDestroyView","onAttach","onDetach",
        "onActivityResult","onRequestPermissionsResult","onConfigurationChanged",
        "onSaveInstanceState","onRestoreInstanceState","onBackPressed",
        "onCreateOptionsMenu","onOptionsItemSelected","onNewIntent",
        "onPostCreate","onPostResume","onWindowFocusChanged","onLowMemory",
        "onTrimMemory","onCreateDialog",
        // SDK callback overridden in anonymous objects (also guarded semantically at rename time
        // by K2Analysis.overridesProjectDeclarationNamed). Only names that are clearly framework
        // contracts, never typical user method names, belong here.
        "handleOnBackPressed",
    )
    private fun isCallback(n: String) = n in cb

    private fun getLine(e: PsiElement): Int {
        val doc = PsiDocumentManager.getInstance(project)
            .getDocument(e.containingFile) ?: return -1
        return doc.getLineNumber(e.textRange.startOffset) + 1
    }
}
