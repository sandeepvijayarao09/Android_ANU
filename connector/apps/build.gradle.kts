plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.sandeepvijayarao09.anu.connector.apps"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// App control (ADR-098): Anu's accessibility service, which reads and acts on another app's screen during an approved
// app task. It implements core:agent's AppDriver; the task's loop and its stops live in core:agent (§4.2). Google Play
// restricts accessibility services that aren't for accessibility, so only the personal build links this (ADR-012).
dependencies {
    api(project(":core:agent"))
    implementation(libs.kotlinx.coroutines.android)
}
