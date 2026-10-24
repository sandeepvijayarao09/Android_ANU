plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.sandeepvijayarao09.anu.feature.keyboard"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Anu's keyboard (ADR-109). It reuses AI Writing's prompts and output checks, the one feature another feature
// depends on (§4.2); brains are bound in :app.
dependencies {
    api(project(":core:agent"))
    api(project(":core:ui"))
    implementation(project(":feature:writing"))
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)

    testImplementation(testFixtures(project(":core:model")))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
