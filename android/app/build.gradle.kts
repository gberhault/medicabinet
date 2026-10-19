plugins {
    id("com.android.application")
    // AGP 9 built-in Kotlin — do not apply org.jetbrains.kotlin.android
    alias(libs.plugins.kotlin.compose)
    // Declared here only (not in the root file): the SQLDelight plugin pins org.jetbrains:annotations
    // to 13.0, which conflicts with AGP 9's plugin classpath when both share the root classpath.
    alias(libs.plugins.sqldelight)
}

android {
    namespace = "com.medicabinet.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.medicabinet.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
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

sqldelight {
    databases {
        create("AppDatabase") {
            packageName.set("com.medicabinet.app.persistence")
            // v1 schema is identical to iOS v1; migrations live in src/main/sqldelight/migrations.
            verifyMigrations.set(true)
        }
    }
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
    implementation(libs.sqldelight.android.driver)
    implementation(libs.sqldelight.coroutines)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
