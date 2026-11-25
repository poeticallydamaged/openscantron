import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

val localProperties = rootProject.file("local.properties")
val localProps = Properties().apply {
    if (localProperties.exists()) localProperties.inputStream().use { load(it) }
}
val openCvSdkDir: String? = localProps.getProperty("opencv.sdk.dir")
val hasOpenCvSdk = openCvSdkDir != null && file(openCvSdkDir!!).resolve("sdk/java").exists()

android {
    namespace = "com.omrgrader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.omrgrader"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        debug {
            isDebuggable = true
            applicationIdSuffix = ""
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/java")
            // Real OpenCV implementation, compiled only when the SDK is installed.
            if (hasOpenCvSdk) java.srcDirs("src/opencv/java")
        }
    }
    if (hasOpenCvSdk) {
        buildConfigField("boolean", "HAS_OPENCV_SDK", "true")
    } else {
        buildConfigField("boolean", "HAS_OPENCV_SDK", "false")
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
            "META-INF/*.kotlin_module"
        )
    }
    testOptions {
        unitTests.isReturnDefaultValues = false
    }
    lint {
        abortOnError = true
        disable += setOf("ObsoleteLintCustomCheck")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.exifinterface)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.datastore.preferences)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.zxing.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    ksp(libs.room.compiler)
    ksp(libs.hilt.compiler)
    if (hasOpenCvSdk) implementation(project(":opencv"))

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.arch.core.testing)
    testImplementation(libs.kotlinx.coroutines.core)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.sqlite.framework)
    androidTestImplementation(libs.kotlinx.coroutines.core)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.room.testing)
}
