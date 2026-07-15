# Kotlin Auto Refactor (K2)

An IntelliJ IDEA / Android Studio plugin for applying suffix-based renames across writable Kotlin project sources. It uses Kotlin PSI and the K2 Analysis API to update declarations and references safely.

## Features

- Independently refactor classes, functions, and variables.
- Refactor Kotlin typealiases independently while preserving their expanded type unless class refactor also renames it.
- Refactor `drawable*` and `layout*` resources, including qualifier variants, while updating Kotlin/Java `R` references and XML references.
- Update Kotlin View Binding imports and type usages from renamed layouts; generated files under `build/` are never edited.
- Preview string, color, and style names from every `values-*` variant; update their `R.*` and `@type/*` references without changing values or filenames.
- Limit scan, preview, refactor, and shuffle targets to one or more selected modules, or use all modules.
- Group IntelliJ source-set modules such as `app.main` and `app.test` under the logical `app` module.
- Discover top-level classes plus top-level, member, nested-scope, and local functions and variables.
- Refactor only top-level class-like declarations, including classes, interfaces, objects, enum classes, and annotations. Nested classes and enum entries are never renamed.
- Exclude function parameters, generated sources, read-only declarations, and SDK/library overrides.
- Remove optional text anywhere in a name, case-insensitively, before adding the new suffix. For example, `CoreINV069Adapter` can become `CoreAdapterINV125`.
- Independently shuffle function and property declaration order while preserving anchors and property dependency blocks.
- Preview planned renames and conflicts before execution.

The default selection refactors classes, typealiases, drawables, layouts, strings, colors, and styles. Uncheck individual value resources in preview to exclude them. Function, variable, and shuffle options are opt-in.

## Prerequisites

- JDK 21
- IntelliJ IDEA 2025.1+ or a compatible Android Studio build
- Kotlin plugin with K2 mode support

## Build

Use the checked-in Gradle wrapper:

```bash
# macOS/Linux
./gradlew --no-daemon buildPlugin

# Windows
gradlew.bat --no-daemon buildPlugin
```

The installable archive is generated under `build/distributions/`.

Run tests and verification with:

```bash
./gradlew test
./gradlew check
```

Launch a sandbox IDE with:

```bash
./gradlew runIde
```

## Usage

1. Open a Kotlin project and wait for indexing to finish.
2. Select **Tools → Kotlin Project Refactor (K2)**.
3. Select **All modules** or use Ctrl/Shift to select multiple project modules.
4. Enter the suffix to add and, optionally, the text to remove from names.
5. Select refactor and shuffle operations, then click **Scan Project**.
6. Review classes, typealiases, symbols, resources, shuffle targets, and conflicts.
7. Click **OK** to execute the valid plan.

Class renames run immediately after confirmation in the plugin dialog; IntelliJ's secondary Find/Refactoring Preview is disabled.

The plugin writes a Markdown report to the project root. Per-symbol diagnostic details are available in `.autorefactor-symbols.log`.

## Development Notes

K2 Analysis API access is centralized in `psi/K2Analysis.kt`. Rename targets must resolve to real Kotlin PSI rather than name-only light methods. Declaration replacements are deduplicated and applied in descending offset order, and SDK/library override contracts must remain unchanged.
