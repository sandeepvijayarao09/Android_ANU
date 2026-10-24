plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// A made-up shop for testing app control on Anu's emulator (ADR-098). Nothing in it is real: no account, no network,
// and "Place order" only says it would have ordered. Never shipped; install it on anu_api36 only.
android {
    namespace = "io.github.sandeepvijayarao09.anu.testshop"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.sandeepvijayarao09.anu.testshop"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.0.1"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
}
