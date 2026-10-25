plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.sandeepvijayarao09.anu.brain.nano"
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

// AI Writing on Gemini Nano through AICore (ADR-116): ML Kit's GenAI rewriting, proofreading and summarization.
// Each brings Google's usage-log transport. ML Kit won't start without its classes, so this module's manifest takes
// out its upload backend and jobs instead, and Anu's process sends nothing (I3). Spike S10's test uses the Prompt API.
dependencies {
    api(project(":core:model"))
    implementation(libs.mlkit.genai.rewriting)
    implementation(libs.mlkit.genai.proofreading)
    implementation(libs.mlkit.genai.summarization)
    implementation(libs.kotlinx.coroutines.guava)
    androidTestImplementation(libs.mlkit.genai.prompt)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
