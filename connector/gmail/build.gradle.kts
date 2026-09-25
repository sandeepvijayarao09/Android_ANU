plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.sandeepvijayarao09.anu.connector.gmail"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Connectors implement core:agent's Tool contract and never call brains (§4.2).
dependencies {
    api(project(":core:agent"))
    api(project(":core:security"))
    api(libs.okhttp)
    implementation(libs.play.services.auth)
    implementation(libs.kotlinx.coroutines.play.services)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
