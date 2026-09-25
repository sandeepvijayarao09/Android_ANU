plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.sandeepvijayarao09.anu"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.sandeepvijayarao09.anu"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // ADR-012: `personal` is the owner's build (SMS, own Gemini key, sideloaded models).
    // `play` must build without Play-restricted modules. The suffix lets both be installed side by side.
    flavorDimensions += "distribution"
    productFlavors {
        create("personal") {
            dimension = "distribution"
            applicationIdSuffix = ".personal"
        }
        create("play") {
            dimension = "distribution"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":feature:home"))
    implementation(project(":feature:settings"))
    implementation(project(":brain:litertlm"))
    implementation(project(":brain:gemini"))
    implementation(project(":core:vault"))
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
