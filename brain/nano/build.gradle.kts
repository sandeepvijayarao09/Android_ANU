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

// Spike S10 (§16): Gemini Nano through AICore, with ML Kit's GenAI Prompt API. Only the spike's device test uses
// it, so nothing here reaches the app: the library brings Google's usage-log transport, which would bypass the
// egress pipeline (I3) until the spike shows what it sends.
dependencies {
    androidTestImplementation(libs.mlkit.genai.prompt)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
