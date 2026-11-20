// The web front: Kotlin compiled to JavaScript, on the shared dance engine.
// gradle :web:jsBrowserDistribution writes the whole page, with the dance data and the music, to
// web/build/dist/js/productionExecutable.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

// What the page needs beside its code, taken from where the Android app keeps it: the dance data and the music (the
// app's assets), the drums, the font, and the icons.
val webAssets by tasks.registering(Sync::class) {
    into(layout.buildDirectory.dir("webAssets"))
    from(rootProject.file("app/src/main/assets")) { include("dance/**", "music/**", "licenses/**") }
    from(rootProject.file("app/src/main/res/raw")) { into("sounds") }
    from(rootProject.file("app/src/main/res/font")) { into("font") }
    from(rootProject.file("design/lucide")) { include("*.svg"); into("icons") }
    // which build this is (the release number on GitHub; "dev" when built by hand), shown on the settings page
    val build = System.getenv("BUILD_NUMBER") ?: "dev"
    inputs.property("build", build)
    doLast { destinationDir.resolve("version.txt").writeText(build) }
}

kotlin {
    js(IR) {
        browser {
            commonWebpackConfig { outputFileName = "sixstep.js" }
            testTask { enabled = false }
        }
        binaries.executable()
    }
    sourceSets {
        jsMain {
            resources.srcDir(webAssets)
            dependencies {
                implementation(project(":shared"))
            }
        }
    }
}
