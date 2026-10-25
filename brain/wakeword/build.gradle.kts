plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.sandeepvijayarao09.anu.brain.wakeword"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The models are read straight from the APK, so they aren't compressed.
    androidResources { noCompress += "tflite" }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// "Hey Anu" from the user's own samples (ADR-121): openWakeWord's mel and speech-embedding models on LiteRT's
// CompiledModel, on the CPU. The same LiteRT as live camera's detector, without Play's AI pack delivery: the models
// ship in the APK, and nothing here may reach the network (I3).
dependencies {
    api(project(":core:voice"))
    implementation(libs.litert.gpu) {
        exclude(group = "com.google.android.play", module = "ai-delivery")
    }

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
