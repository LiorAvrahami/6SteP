// The web front: Kotlin compiled to JavaScript, on the shared dance engine.
// gradle :web:jsBrowserDistribution writes the page to web/build/dist/js/productionExecutable.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
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
        jsMain.dependencies {
            implementation(project(":shared"))
        }
    }
}
