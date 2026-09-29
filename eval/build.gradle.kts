import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The JVM eval harness (ARCHITECTURE §4.1, §15). Pure Kotlin: it scores the cores on made-up data, with separately
// authored held-out splits, and runs with the unit tests in CI.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core:router"))
    implementation(project(":core:security"))
    testImplementation(libs.junit)
}
