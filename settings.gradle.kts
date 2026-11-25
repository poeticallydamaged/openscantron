pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "OpenScantron"
include(":app")

// OpenCV Android SDK is an optional, locally installed native dependency. Point
// local.properties at the unpacked OpenCV-android-sdk directory to compile the
// accelerated OpenCV pipeline (src/opencv/java). Without it the identical pure-Kotlin
// PortableImageOps implementation is used and the app still builds and works offline.
val localProperties = rootProject.file("local.properties")
if (localProperties.exists()) {
    val properties = java.util.Properties()
    localProperties.inputStream().use { properties.load(it) }
    val sdkDir = properties.getProperty("opencv.sdk.dir")
    if (!sdkDir.isNullOrBlank()) {
        val sdkJava = file(sdkDir).resolve("sdk/java")
        if (sdkJava.exists()) {
            include(":opencv")
            project(":opencv").projectDir = sdkJava
        }
    }
}
