// One build, three modules:
//   shared  the dance engine, in Kotlin that knows nothing of Android or of the browser (a JVM library for the Android
//           app and for the tests, JavaScript for the web front)
//   app     the Android app
//   web     the web front, in Kotlin compiled to JavaScript
// The plugins are put on the build's classpath in build.gradle.kts, so no plugin management is needed here.
rootProject.name = "6SteP"
include(":shared")
include(":web")
// The Android app needs Google's package site and the Android SDK. Where they are not at hand (the cloud session that
// maintains this project has neither), SIXSTEP_SKIP_ANDROID=1 leaves the app out; the shared engine and the web front
// still build and test.
if (System.getenv("SIXSTEP_SKIP_ANDROID") == null) include(":app")
