plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.sandeepvijayarao09.anu.feature.settings"
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

// Depends on contracts only (§4.2): OnDeviceModels, the ledger's entries, CloudKeySettings. Styles itself
// through core:ui (DESIGN.md).
dependencies {
    api(project(":core:model"))
    api(project(":core:security"))
    api(project(":core:agent")) // ToolAuditEntry for the Activity list
    api(project(":core:ui"))
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
