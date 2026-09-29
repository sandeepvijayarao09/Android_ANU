plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.sandeepvijayarao09.anu.connector.phone"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Phone controls (G6, ADR-076): tools for the clock, the flashlight and settings pages. Connectors implement
// core:agent's Tool contract and never call brains (§4.2).
dependencies {
    api(project(":core:agent"))
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
