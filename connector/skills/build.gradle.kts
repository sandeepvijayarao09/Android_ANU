plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.sandeepvijayarao09.anu.connector.skills"
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

// Agent Skills as AI Edge Gallery defines them (ADR-120): SKILL.md folders, JS skills in a hidden WebView, and intents.
// Gallery's built-in skills are in assets/skills, under its Apache 2.0 licence. Connectors implement core:agent's Tool
// contract and never call brains (§4.2).
dependencies {
    api(project(":core:agent"))
    api(project(":core:security"))
    implementation(project(":connector:phone"))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
