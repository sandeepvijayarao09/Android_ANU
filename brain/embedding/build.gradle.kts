plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.sandeepvijayarao09.anu.brain.embedding"
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

// Search by meaning (ADR-055): EmbeddingGemma through Google's AI Edge RAG library, which runs the model's
// .tflite with its SentencePiece tokenizer. Only its Gemma embedder is used, so the parts that would pull in
// MediaPipe's LLM runtime, a second OkHttp and a copy of Android's org.json are left out; the app packages
// only the Gemma embedder's native library.
dependencies {
    api(project(":core:model"))
    implementation(libs.localagents.rag) {
        exclude(group = "com.google.mediapipe", module = "tasks-genai")
        exclude(group = "org.json", module = "json")
        exclude(group = "com.squareup.okhttp3", module = "okhttp")
    }
    implementation(libs.protobuf.javalite)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)

    // Spike S3's eval runs memory's own search path on the device.
    androidTestImplementation(project(":core:memory"))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
