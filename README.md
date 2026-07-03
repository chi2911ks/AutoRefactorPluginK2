# Android Auto Refactor (K2)

This is an IntelliJ IDEA / Android Studio plugin designed to automate the refactoring of Android UI components. It intelligently appends a configurable suffix to UI-related classes, functions, and properties using semantic PSI-based renaming.

This plugin specifically targets the **Kotlin K2 mode**, leveraging the new **Kotlin Analysis API** directly (without reflection) for robust property/getter disambiguation and precise override resolution.

## Features

- **Automated Refactoring**: Appends a specified suffix to UI components.
- **Supported Android Components**:
  - `Activity`
  - `Fragment`
  - `Dialog`
  - `DialogFragment`
  - `BottomSheetDialogFragment`
- **K2 Ready**: Fully compatible with the Kotlin K2 compiler and IDE plugin mode.
- **Semantic Renaming**: Uses IntelliJ's powerful PSI (Program Structure Interface) and the K2 Analysis API to ensure references are updated accurately across your codebase.

## Prerequisites

- IntelliJ IDEA Community/Ultimate 2025.1+ or compatible Android Studio versions based on 2025.1+.
- Kotlin Plugin with K2 mode support.

## Building the Plugin

To build the plugin from source, you need JDK 21.

1. Clone or download the repository.
2. Open a terminal in the root directory of the project.
3. Run the Gradle build command:

   ```bash
   # On macOS/Linux
   ./gradlew buildPlugin

   # On Windows
   gradlew.bat buildPlugin
   ```

4. Once the build completes successfully, the plugin archive will be generated at:
   `build/distributions/AutoRefactorPluginK2-1.0.0-SNAPSHOT.zip`

## Installation

You can install the compiled plugin directly into your IDE:

1. Open IntelliJ IDEA or Android Studio.
2. Navigate to **Settings / Preferences** -> **Plugins**.
3. Click the gear icon ⚙️ and select **Install Plugin from Disk...**.
4. Choose the `.zip` file generated in the `build/distributions/` directory.
5. Click **Apply** and **Restart IDE** when prompted.

Alternatively, for development and testing, you can launch a sandbox IDE with the plugin installed by running:
```bash
./gradlew runIde
```

## How to Use

1. Open your Android project in the IDE.
2. Ensure your project has indexed properly.
3. From the top menu bar, go to **Tools** -> **Android Refactor (K2)**.
4. (Follow the on-screen prompts or dialogs, if any, to provide the desired suffix and confirm the refactoring scope).
5. The plugin will analyze your codebase, find subclasses of the supported Android components, and perform safe, semantic renaming on the classes, functions, and properties.
6. Review the changes in the "Find Refactoring Preview" tool window (if prompted) or check your version control diff to verify the automated changes.

## Development

- **Language**: Kotlin 2.1.0
- **Framework**: IntelliJ Platform Plugin Template (`org.jetbrains.intellij.platform`)
- **Analysis**: Kotlin Analysis API (K2)

### Important Notes
Because this plugin uses the K2 Analysis API natively, it avoids the overhead and fragility of reflection-based solutions that were common in K1, providing much faster and more accurate refactoring results.
