import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin (ADR-093): the user's notes, kept in Anu's own encrypted store, as tools for the Tool Broker. Neither
// Android nor iOS lets an app write to the system's notes, so the same tools serve both.
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

// Connectors implement core:agent's Tool contract and never call brains (§4.2).
dependencies {
    api(project(":core:agent"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
