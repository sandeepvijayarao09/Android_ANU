plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.sandeepvijayarao09.anu.brain.litertlm"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The runtime looks for the TPU's dispatch library as a file in the native library folder, so the test APK
    // extracts its native libraries instead of loading them from inside the APK.
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    api(project(":core:model"))
    implementation(libs.litertlm.android)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)

    // Memory's extraction eval runs core:memory's extractor on the real model.
    androidTestImplementation(project(":core:memory"))
    // The voice note eval runs a real chat turn with the real calendar, contacts and alarm tools, approving into fakes.
    androidTestImplementation(project(":core:agent"))
    androidTestImplementation(project(":connector:calendar"))
    androidTestImplementation(project(":connector:contacts"))
    androidTestImplementation(project(":connector:phone"))
    androidTestImplementation(project(":connector:notes"))
    // The Pixel's TPU (Backend.NPU): Google's Tensor dispatch library, which the runtime looks for in the app's native
    // library folder. Device tests only until the TPU passes S1.
    androidTestImplementation(libs.litert.npu.google.tensor)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
