import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "com.org.refactor.plugin.k2"
version = "1.0.4"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2025.1")
        // Kotlin plugin bundles the K2 Analysis API (org.jetbrains.kotlin.analysis.api.*)
        bundledPlugins("com.intellij.java", "org.jetbrains.kotlin")
        testFramework(TestFrameworkType.Platform)
        instrumentationTools()
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.10.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

intellijPlatform {
    buildSearchableOptions = false
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "251"
            untilBuild = "262.*"
        }
    }
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
    systemProperty("idea.headless.statistics.device.id", "000000000000000-0000-0000-0000-000000000000")
    systemProperty("idea.headless.statistics.salt", "autorefactor-plugin-test")
}
