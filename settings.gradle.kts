import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "csiq-jetbrains"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Resolves the Java 25 toolchain the build needs. If a JDK 25 is already
    // installed, or listed in org.gradle.java.installations.paths, nothing is
    // downloaded. A JetBrains Runtime from an installed IDE qualifies: it ships
    // a full javac.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("org.jetbrains.intellij.platform.settings") version "2.19.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        intellijPlatform {
            defaultRepositories()
        }
    }
}
