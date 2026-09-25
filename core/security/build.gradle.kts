import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin: egress, redaction and secret handles. Android crypto lives in core:vault (ARCHITECTURE §4.1).
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
    api(project(":core:model"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
