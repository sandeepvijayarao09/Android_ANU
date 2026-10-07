plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "io.github.sandeepvijayarao09.anu.core.data"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

// Schemas are committed so every migration can be reviewed and tested (§14).
room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    api(project(":core:agent"))
    api(project(":core:memory"))
    api(project(":core:vault"))
    api(project(":connector:notes")) // RoomNoteStore implements its NoteStore (ADR-093)
    api(libs.androidx.room.runtime) // AnuDatabase extends RoomDatabase
    implementation(libs.androidx.sqlite)
    implementation(libs.sqlcipher.android)
    ksp(libs.androidx.room.compiler)

    testImplementation(testFixtures(project(":core:model")))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.room.testing)
}
