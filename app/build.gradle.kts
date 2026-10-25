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

    // The AI Edge RAG library ships four native libraries; search by meaning uses only the Gemma embedder's
    // (ADR-055). The others (Gecko embedder, vector store, text chunker) would add about 47 MB.
    packaging {
        jniLibs {
            excludes += setOf("**/libgecko_embedding_model_jni.so", "**/libsqlite_vector_store_jni.so", "**/libtext_chunker_jni.so")
            // Qualcomm's QNN runtime (ADR-115) carries every Hexagon generation and its DSP and GPU backends; live
            // camera needs only the HTP for the Snapdragon 8 Elite Gen 5 (v81), which saves about 60 MB.
            excludes += setOf(
                "**/libQnnDsp.so", "**/libQnnDspV*.so", "**/libQnnGpu.so",
                "**/libQnnHtpV6*.so", "**/libQnnHtpV73*.so", "**/libQnnHtpV75*.so", "**/libQnnHtpV79*.so",
            )
            // Native libraries are extracted on install, because LiteRT-LM looks for the TPU's dispatch library as a
            // file in the app's native library folder (ADR-103).
            useLegacyPackaging = true
        }
        // ADK's Google libraries (ADR-104) each carry these build listings, which nothing reads at run time.
        resources {
            excludes += setOf("META-INF/INDEX.LIST", "META-INF/DEPENDENCIES", "META-INF/io.netty.versions.properties")
        }
    }
}

dependencies {
    testImplementation(libs.junit)

    implementation(project(":feature:home"))
    implementation(project(":core:ui"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:memory"))
    implementation(project(":feature:writing"))
    implementation(project(":feature:keyboard"))
    implementation(project(":feature:camera")) // live camera (ADR-111)
    implementation(project(":brain:vision"))
    // AI Writing on Gemini Nano through AICore, without ML Kit's usage-log uploader (ADR-116).
    implementation(project(":brain:nano"))
    implementation(project(":brain:litertlm"))
    implementation(project(":brain:gemini"))
    implementation(project(":brain:ollama"))
    implementation(project(":brain:embedding"))
    implementation(project(":core:vault"))
    implementation(project(":core:data"))
    implementation(project(":connector:calendar"))
    implementation(project(":connector:contacts"))
    implementation(project(":connector:gmail"))
    implementation(project(":connector:messages"))
    implementation(project(":connector:mcp"))
    implementation(project(":connector:shopping"))
    implementation(project(":connector:phone"))
    implementation(project(":connector:notes"))
    // Agent Skills as AI Edge Gallery defines them: SKILL.md, JS in a hidden WebView, intents (ADR-120).
    implementation(project(":connector:skills"))
    // Agents on the user's own network, over A2A (ADR-105).
    implementation(project(":connector:a2a"))
    // SMS is Play-restricted, so only the owner's build links it (ADR-012, ADR-032).
    "personalImplementation"(project(":connector:sms"))
    // So is app control's accessibility service (ADR-098).
    "personalImplementation"(project(":connector:apps"))
    // Live camera's YOLOX on the Snapdragon's NPU (ADR-115): LiteRT's Qualcomm dispatch library and compiler plugin
    // (Apache 2.0) and Qualcomm's QNN runtime (Qualcomm AI Hub Model License), in the owner's build only.
    "personalImplementation"(libs.litert.npu.qualcomm)
    "personalImplementation"(libs.qnn.runtime)
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

/** Fails the build if a Play variant's merged manifest declares any SMS permission (ADR-012) or an accessibility service (ADR-098). */
abstract class VerifyNoSmsPermissions : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifest: RegularFileProperty

    @TaskAction
    fun verify() {
        val text = manifest.get().asFile.readText()
        val found = listOf("READ_SMS", "RECEIVE_SMS", "SEND_SMS", "RECEIVE_MMS", "RECEIVE_WAP_PUSH").filter { "android.permission.$it" in text }
        check(found.isEmpty()) { "The play flavor must not declare SMS permissions, but its manifest has $found (ADR-012)" }
        check("android.permission.BIND_ACCESSIBILITY_SERVICE" !in text) { "The play flavor must not declare an accessibility service (ADR-098)" }
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
