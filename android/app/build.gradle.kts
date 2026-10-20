plugins {
    id("com.android.application")
    // AGP 9 built-in Kotlin — do not apply org.jetbrains.kotlin.android
    alias(libs.plugins.kotlin.compose)
    jacoco
}

android {
    namespace = "com.medicabinet.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.medicabinet.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            enableUnitTestCoverage = true
        }
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    sourceSets {
        // The BDPM reference database and its manifest, bundled once for both apps from reference-data/bundle.
        getByName("main").assets.directories.add("../../reference-data/bundle")
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// The shared fixtures at <repo>/fixtures (MEDICABINET_FIXTURES_DIR overrides, as for :core).
val fixturesDir = providers.environmentVariable("MEDICABINET_FIXTURES_DIR")
    .orElse(rootProject.layout.projectDirectory.dir("../fixtures").asFile.path)

tasks.withType<Test>().configureEach {
    systemProperty("medicabinet.fixtures", fixturesDir.get())
    inputs.dir(fixturesDir)
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.sqlite.framework)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.cpp.android)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.turbine)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.ui.test.junit4)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

jacoco {
    toolVersion = "0.8.15"
}

// Robolectric loads classes through its own sandbox classloader, which hides them from Jacoco's default
// location-based tracking (they would report 0%). includeNoLocationClasses fixes that; jdk.internal.* must be
// excluded alongside it or the agent crashes (see https://github.com/jacoco/jacoco/issues/1287).
tasks.withType<Test>().configureEach {
    configure<JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
    maxHeapSize = "2g"
}

// Classes that are not authored code or cannot be reached by JVM tests:
//  - R / BuildConfig / Manifest: generated
//  - ComposableSingletons$*: compiler-generated holders for cached composable lambdas
//  - MainActivity: Activity wiring (the Compose UI it hosts is tested directly)
//  - CameraXScanner: the camera hardware adapter (CameraX + zxing-cpp); it needs a device, the rest of the scan
//    flow is tested through the CodeScanner interface
private val jacocoExcludes = listOf(
    "**/R.class",
    "**/R$*.class",
    "**/BuildConfig.*",
    "**/Manifest*.*",
    "**/ComposableSingletons*.*",
    "**/MainActivity*.*",
    "**/features/scan/camera/CameraXScanner*.*"
)

// Unit-test coverage for the app (JVM + Robolectric), XML for scripts/android-coverage-gate.py.
tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn("testDebugUnitTest")
    group = "verification"
    description = "JaCoCo coverage report for the debug unit tests."
    reports {
        xml.required.set(true)
        html.required.set(false)
    }
    classDirectories.setFrom(
        fileTree("${layout.buildDirectory.get()}/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes") {
            exclude(jacocoExcludes)
        }
    )
    sourceDirectories.setFrom(files("src/main/kotlin"))
    executionData.setFrom(
        fileTree(layout.buildDirectory.get()) {
            include("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec")
        }
    )
}
