package com.org.refactor.plugin.model

data class TypeAliasInfo(
    val name: String,
    val fqn: String,
    val sourceFile: String,
    val declarationOffset: Int,
    val ownerScope: String,
)
