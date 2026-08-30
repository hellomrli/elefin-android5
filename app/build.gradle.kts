plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.flex.elefin"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.flex.elefin"
        minSdk = 21
        targetSdk = 36

        // Version code: major * 10000 + minor * 100 + patch
        // Must stay in sync with the release tag: UpdateService.parseVersion("v1.2.1")
        // computes 10201 and offers an update only when that beats this value.
        versionCode = 10201
        versionName = "1.2.1-zh-hw"

        ndk {
            // No separate native symbol bundle - nothing here is uploaded to Play.
            // NOTE: this does NOT strip the prebuilt jniLibs. AGP's stripDebugSymbols
            // task does that, and only when a local NDK is installed; otherwise it
            // logs "Unable to strip ..." and packages them as-is. libc++_shared.so
            // ships unstripped ("with debug_info", 7-9 MB vs ~1 MB stripped), so if
            // that warning appears in the build log, run llvm-strip on jniLibs by hand.
            debugSymbolLevel = "none"
        }
    }

    // ARM only: this fork targets Android TV boxes; x86/x86_64 would only ever run
    // in an emulator and cost ~40 MB of prebuilt FFmpeg/mpv .so files. One APK per
    // ABI instead of a single fat binary - armeabi-v7a also installs on arm64
    // devices, so it is the safe pick if you are unsure which one your box needs.
    // (ndk.abiFilters and splits.abi are mutually exclusive; the include() below
    // is what limits the packaged ABIs.)
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = false
        }
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("elefin-android5.keystore")
            storePassword = "elefin-android5"
            keyAlias = "elefin-android5"
            keyPassword = "elefin-android5"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    androidComponents {
        onVariants { variant ->
            if (variant.buildType == "release") {
                variant.outputs.forEach { output ->
                    val out = output as com.android.build.api.variant.impl.VariantOutputImpl
                    val abi = out.filters
                        .firstOrNull {
                            it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI
                        }
                        ?.identifier
                    out.outputFileName.set(
                        if (abi != null) "elefin-release-$abi.apk" else "elefin-release.apk"
                    )
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        // Enable core library desugaring so java.time etc. work on Android 5.0 (API 21)
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            // Required for API < 23: native libs must be extracted at install time.
            useLegacyPackaging = true
        }
    }

}

dependencies {

    // Core library desugaring (java.time etc. on Android 5.0 / API 21)
    coreLibraryDesugaring(libs.desugar.jdk.libs)


    // -------------------------------------------------------------
    // AndroidX Core + Leanback
    // -------------------------------------------------------------
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.palette)
    implementation(libs.androidx.leanback)

    // -------------------------------------------------------------
    // Images - Coil
    // -------------------------------------------------------------
    // Glide was removed in this fork: it loaded zero images (all 135 image call sites
    // use Coil) yet pulled in a kapt annotation processor and a second in-memory
    // bitmap cache sized at 2x the default - real memory pressure on 1 GB boxes.
    implementation(libs.coil.compose)

    // -------------------------------------------------------------
    // Compose BOM + UI
    // -------------------------------------------------------------
    implementation(platform(libs.compose.bom))
    implementation(libs.ui)
    implementation(libs.foundation)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.activity.compose)
    implementation("androidx.compose.material3:material3:1.3.0")
    implementation("androidx.compose.material:material-icons-extended")

    // TV Compose
    implementation(libs.tv.foundation)
    implementation(libs.tv.material)

    // -------------------------------------------------------------
    // Navigation
    // -------------------------------------------------------------
    implementation(libs.navigation.compose)

    // -------------------------------------------------------------
    // JSON / Networking - Ktor + Gson
    // -------------------------------------------------------------
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.android)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    implementation("com.google.code.gson:gson:2.11.0")

    // GitHub updater + YouTube trailer extraction
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(project(":newpipe-extractor"))

    // -------------------------------------------------------------
    // Lottie
    // -------------------------------------------------------------
    implementation(libs.lottieCompose)

    // -------------------------------------------------------------
    // Media3 / ExoPlayer - COMPLETE JELLYFIN CLIENT EXTENSIONS
    // -------------------------------------------------------------

    // Core ExoPlayer
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.common)

    // Adaptive Streaming (HLS, DASH, SmoothStreaming)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.smoothstreaming)

    // UI Components
    implementation(libs.media3.ui)
    implementation(libs.media3.ui.compose)
    implementation(libs.media3.ui.leanback) // Android TV optimized UI

    // Extractors (Subtitle support: SRT, ASS, VTT, TTML, MKV internal subs)
    implementation(libs.media3.extractor)

    // MediaSession (TV remote controls, Next Episode, Media keys)
    implementation(libs.media3.session)

    // Network DataSources
    implementation(libs.media3.datasource.okhttp) // REQUIRED for Jellyfin API reliability
    implementation(libs.media3.datasource.cronet) // Optional: Ultra-low latency streaming

    // Transformer (Optional: Advanced media processing)
    implementation(libs.media3.transformer)

    // -------------------------------------------------------------
    // FFmpeg Decoder (Jellyfin-provided)
    // -------------------------------------------------------------
    // Audio: DTS, DTS-HD, TrueHD, AC3, E-AC3, FLAC, ALAC, Vorbis, Opus
    // Licensed under GPLv3 (compatible with Jellyfin ecosystem)
    implementation(libs.jellyfin.media3.ffmpeg.decoder)

    // -------------------------------------------------------------
    // AV1 Video Decoder
    // -------------------------------------------------------------
    // Removed in this fork: the upstream decoder_av1 (libgav1) module had
    // incomplete sources (missing cpu_features/libgav1 submodules) and failed
    // to build. AV1 software decoding is still available through MPV (dav1d)
    // and the Jellyfin FFmpeg decoder above.

    // -------------------------------------------------------------
    // MPV Player (Optional - can be enabled in settings)
    // -------------------------------------------------------------
    // Uses embedded libmpv.so and libplayer.so from jniLibs folder
    // Requires .so files to be placed in app/src/main/jniLibs/{abi}/
}
