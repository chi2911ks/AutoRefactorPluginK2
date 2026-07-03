package com.org.refactor.plugin.references

import com.org.refactor.plugin.model.*

data class DependencyNode(
    val symbol: SymbolInfo,
    val references: MutableList<ResolvedReference> = mutableListOf(),
)

class DependencyGraph {

    private val nodes = mutableMapOf<String, DependencyNode>()

    fun addSymbol(symbol: SymbolInfo) {
        nodes.getOrPut(symbol.fqn) { DependencyNode(symbol) }
    }

    fun addReference(symbolFqn: String, reference: ResolvedReference) {
        nodes[symbolFqn]?.references?.add(reference)
    }

    fun toposort(): List<SymbolInfo> {
        val visited = mutableSetOf<String>()
        val result = mutableListOf<SymbolInfo>()

        fun dfs(fqn: String) {
            if (fqn in visited) return
            visited.add(fqn)
            nodes[fqn]?.let { result.add(it.symbol) }
        }

        for (fqn in nodes.keys) {
            dfs(fqn)
        }

        return result
    }

    fun getAllSymbols(): List<SymbolInfo> = nodes.values.map { it.symbol }

    fun getNode(fqn: String): DependencyNode? = nodes[fqn]

    fun size(): Int = nodes.size
}
