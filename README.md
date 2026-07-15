# Kotlin Auto Refactor (K2)

Kotlin Auto Refactor is an IntelliJ IDEA and Android Studio plugin for planning and applying project-wide Kotlin and Android resource renames. It uses Kotlin PSI and the K2 Analysis API, presents conflicts before execution, and applies valid renames without IntelliJ's secondary Refactoring Preview confirmation.

## Features

### Kotlin refactoring

- Rename top-level classes, interfaces, objects, enum classes, and annotations. Nested declarations and enum entries are excluded.
- Rename typealiases independently. The expanded type changes only when its referenced class is also renamed.
- Optionally rename functions and variables; parameters are never independent targets.
- Skip generated/read-only declarations, SDK or library overrides, and unsafe accessor collisions.
- Optionally shuffle functions and properties while preserving anchors, dependency blocks, and eager initialization order.

Classes and typealiases are enabled by default. Functions, variables, and shuffling are opt-in.

### Android resources

- Rename `drawable*` and `layout*` files together with all qualifier variants, such as `layout-land` and `drawable-night`.
- Update contextual Kotlin, Java, and XML references such as `R.layout.*`, `R.drawable.*`, `@layout/*`, and `@drawable/*`.
- Update View Binding type imports and usages after layout renames without editing generated files.
- Rename `<string>`, `<color>`, and `<style>` declarations across every `values*` variant.
- Update `R.string`, `R.color`, `R.style`, XML resource references, and explicit style parents. Android framework parents such as `android:Theme.Light` remain unchanged.
- Skip a complete logical resource when any variant collides, is missing, or is read-only.

Value resources are individually selectable and checked by default. Other `values*` declarations and resources under `font*`, `mipmap*`, and `raw*` are not renamed.

## Naming rules

Enter a new suffix and, optionally, text to remove. Removal is case-insensitive and applies at every position before repeated underscores are normalized.

- Kotlin: `CoreRecyclerINV069Adapter` → `CoreRecyclerAdapterINV125`
- Drawable/layout: `inv069_bg_12_top` → `bg_12_top_inv125`
- String/color: `inv069_tv_content` → `inv125_tv_content`
- Style: `inv069_AppTheme.AdAttribution` → `inv125_AppTheme.AdAttribution`

Resource suffixes are normalized to lowercase. Style casing and dot-separated hierarchy are preserved. If the target suffix/prefix is already present, it is not added twice.

## Module selection and preview

The module picker supports one or more modules and groups IntelliJ source sets such as `app.main` under the logical `app` module. **All modules** is equivalent to selecting every logical module.

The preview displays planned class, symbol, typealias, file-resource, and value-resource renames. Uncheck individual value resources to exclude them. Collision and read-only rows are disabled and reported before execution.

## Requirements

- JDK 21
- IntelliJ IDEA 2025.1+ or a compatible Android Studio version
- Kotlin plugin with K2 support

The plugin declares compatibility with IntelliJ platform builds `251` through `261.*`.

## Build and test

Use the included Gradle wrapper:

```powershell
# Windows
.\gradlew.bat buildPlugin
.\gradlew.bat test
.\gradlew.bat check
.\gradlew.bat runIde
```

```bash
# macOS/Linux
./gradlew buildPlugin
./gradlew test
./gradlew check
./gradlew runIde
```

The installable ZIP is generated in `build/distributions/`. `runIde` starts a sandbox IDE for manual testing.

## Installation and usage

1. Build the plugin or obtain its ZIP archive.
2. In Android Studio or IntelliJ IDEA, open **Settings → Plugins → ⚙ → Install Plugin from Disk** and select the ZIP.
3. Open a Kotlin project and wait for indexing to finish.
4. Select **Tools → Kotlin Project Refactor (K2)**.
5. Choose one or more modules, enter the suffix and optional removal text, then select operations.
6. Click **Scan Project**, review the preview and conflicts, and click **OK**.

Execution produces `refactor-report-<timestamp>.md` in the project root. Symbol diagnostics are written to `.autorefactor-symbols.log` when available.

## Development notes

K2 Analysis API access is centralized in `psi/K2Analysis.kt`. Scans and plan preparation run in background tasks; PSI mutations and IntelliJ rename processors run in the required write/EDT context. Resource references are updated contextually, qualifier variants are renamed atomically, and generated `build/` content is excluded.
