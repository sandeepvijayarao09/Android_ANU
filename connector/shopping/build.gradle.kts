import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin (ADR-047): shopping with stores that speak the Universal Commerce Protocol (UCP) over REST.
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
    api(project(":core:security"))
    api(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
