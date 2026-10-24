plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.sandeepvijayarao09.anu.brain.vision"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The model is read straight from the APK, so it isn't compressed.
    androidResources { noCompress += "tflite" }
    // The NPU runtime looks for its dispatch and QNN libraries as files in the native library folder (spike S15).
    packaging { jniLibs { useLegacyPackaging = true } }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Live object detection (ADR-111): YOLOX-Nano on LiteRT's CompiledModel, on the GPU or the CPU. LiteRT's Play
// delivery of AI packs is left out: the model ships in the APK, and nothing here may reach the network (I3).
dependencies {
    api(project(":core:model"))
    implementation(libs.litert.gpu) {
        exclude(group = "com.google.android.play", module = "ai-delivery")
    }

    testImplementation(libs.junit)

    // Spike S15: the Snapdragon's NPU through LiteRT's Qualcomm dispatch and compiler plugin, with Qualcomm's QNN
    // libraries (HTP v81 for the SM8850). Device tests only until the spike passes.
    androidTestImplementation(libs.litert.npu.qualcomm)
    androidTestImplementation(libs.qnn.runtime)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
