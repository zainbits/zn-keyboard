pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Temporary: Meniscus 0.1.0 is tested from a local publish before it goes to Maven Central.
        mavenLocal {
            content { includeGroup("io.github.zainbits") }
        }
    }
}

rootProject.name = "ZnKeyboard"
include(":app")
