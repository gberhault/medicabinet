plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-test-fixtures`
}

// Bytecode for Java 17 (as Riding), built on whichever JDK >= 17 is installed.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)

    testFixturesApi(libs.kotlinx.coroutines.core)
    testFixturesApi(libs.kotlinx.serialization.json)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlin.test)
}

// The shared, language-neutral fixtures at <repo>/fixtures, also run by the Swift core tests.
// MEDICABINET_FIXTURES_DIR overrides the folder (the gate tests point it at a deliberately wrong copy).
val fixturesDir = providers.environmentVariable("MEDICABINET_FIXTURES_DIR")
    .orElse(rootProject.layout.projectDirectory.dir("../fixtures").asFile.path)

tasks.test {
    useJUnitPlatform()
    systemProperty("medicabinet.fixtures", fixturesDir.get())
    inputs.dir(fixturesDir)
}
