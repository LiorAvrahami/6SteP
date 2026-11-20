import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The dance engine: Kotlin with no Android and no browser in it. The Android app uses the JVM library, the web front
// the JavaScript one; the tests run on the JVM (gradle :shared:jvmTest).
plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    js(IR) {
        browser {
            testTask { enabled = false }   // the tests run on the JVM; a browser run would need a headless Chrome
        }
    }
    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
