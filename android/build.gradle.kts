// Top-level build file.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.ktlint) apply false
}

// Static analysis: detekt = smells/bugs (per-module baseline, new findings only);
// ktlint = formatting (no baseline). Never regenerate a baseline to hide a new finding.
subprojects {
    apply(plugin = "dev.detekt")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    extensions.configure<dev.detekt.gradle.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        baseline = file("detekt-baseline.xml")
        source.setFrom("src")
        parallel = true
    }
    tasks.withType<dev.detekt.gradle.Detekt>().configureEach {
        exclude("**/build/**", "**/generated/**")
    }
    extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        filter { exclude { it.file.path.contains("/build/") } }
    }
}
