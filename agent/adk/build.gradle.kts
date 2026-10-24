import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Sub-agents, workflows and agent-to-agent calls on Google's ADK for Kotlin (ADR-104). Pure Kotlin: ADK's common API
// only, so the app gets ADK's Android build and these tests its JVM build. Every tool an ADK agent calls goes through
// core:agent's Tool Broker, and every model call to an on-device brain.
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
    implementation(libs.google.adk.core)
    testImplementation(testFixtures(project(":core:model")))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
