import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin: the pipeline's logic is tested on the JVM. Storage lives in core:data and scheduling
// in app, behind the contracts here (ARCHITECTURE §4.2, ADR-036).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(project(":core:agent"))
    testImplementation(testFixtures(project(":core:model")))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
