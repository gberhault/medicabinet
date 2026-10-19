plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-test-fixtures`
    jacoco
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

jacoco {
    // 0.8.12 cannot instrument JDK 25 class files; 0.8.14+ supports them (as in Riding).
    toolVersion = "0.8.15"
}

// Coverage of :core main sources only (not tests, not the test fixtures); XML feeds scripts/android-coverage-gate.py.
tasks.test {
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    reports {
        xml.required.set(true)
        html.required.set(false)
    }
}
