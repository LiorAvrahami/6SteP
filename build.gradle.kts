// The Kotlin plugin, and the Android plugin unless SIXSTEP_SKIP_ANDROID is set (see settings.gradle.kts), are on the
// build's classpath here, so that every module gets the same ones. Google's repository comes last and only for its own
// groups: nothing else is ever asked of it.
buildscript {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.21")
        if (System.getenv("SIXSTEP_SKIP_ANDROID") == null) classpath("com.android.tools.build:gradle:8.5.2")
    }
}

allprojects {
    repositories {
        mavenCentral()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
    }
}
