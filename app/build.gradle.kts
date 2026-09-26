import com.android.build.api.artifact.SingleArtifact

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
    implementation(project(":core:ui"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:memory"))
    implementation(project(":feature:writing"))
    implementation(project(":brain:litertlm"))
    implementation(project(":brain:gemini"))
    implementation(project(":core:vault"))
    implementation(project(":core:data"))
    implementation(project(":connector:calendar"))
    implementation(project(":connector:contacts"))
    implementation(project(":connector:gmail"))
    implementation(project(":connector:messages"))
    implementation(project(":connector:mcp"))
    implementation(project(":connector:shopping"))
    // SMS is Play-restricted, so only the owner's build links it (ADR-012, ADR-032).
    "personalImplementation"(project(":connector:sms"))
    implementation(libs.androidx.work.runtime) // memory learning (ADR-037)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

/** Fails the build if a Play variant's merged manifest declares any SMS permission (ADR-012). */
abstract class VerifyNoSmsPermissions : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifest: RegularFileProperty

    @TaskAction
    fun verify() {
        val text = manifest.get().asFile.readText()
        val found = listOf("READ_SMS", "RECEIVE_SMS", "SEND_SMS", "RECEIVE_MMS", "RECEIVE_WAP_PUSH").filter { "android.permission.$it" in text }
        check(found.isEmpty()) { "The play flavor must not declare SMS permissions, but its manifest has $found (ADR-012)" }
    }
}

androidComponents {
    onVariants(selector().withFlavor("distribution" to "play")) { variant ->
        val name = variant.name.replaceFirstChar(Char::titlecase)
        val verify = tasks.register<VerifyNoSmsPermissions>("verify${name}HasNoSms") {
            manifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
        }
        // Every way of building a Play variant runs it, including CI's assemblePlayDebug.
        val builds = setOf("check", "assemble$name", "bundle$name")
        tasks.named { it in builds }.configureEach { dependsOn(verify) }
    }
}
