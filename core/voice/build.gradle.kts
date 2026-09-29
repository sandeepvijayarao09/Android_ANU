plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.sandeepvijayarao09.anu.core.voice"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Speech in and out, on the phone only (ADR-043), and voice notes (ADR-078). The contracts are plain Kotlin, so
// screens and their tests use fakes; the Android engines live beside them.
dependencies {
    api(project(":core:model"))
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
