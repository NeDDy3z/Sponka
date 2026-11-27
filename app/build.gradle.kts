import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing is read from a gitignored keystore.properties when present.
// F-Droid builds without that file, producing an unsigned APK it verifies against
// the published (signed) binary for reproducible builds.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val hasReleaseSigning = keystorePropertiesFile.exists()
val keystoreProperties = Properties().apply {
    if (hasReleaseSigning) keystorePropertiesFile.inputStream().use { load(it) }
}

android {
    namespace = "com.xnotes"
    compileSdk = 36
    // Pinned toolchain for the vendored native builds (F-Droid reproducibility).
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.neddy.nnotes"
        minSdk = 26
        targetSdk = 36
        versionCode = 128
        // <upstream xnotes version>-<n-notes fork version>
        versionName = "0.9.11-1.16.5"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_static"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
            // The tree-sitter parser tables gzip ~6x: compressed packaging keeps the
            // APK at ~12MB instead of ~30MB, at the cost of extract-on-install.
            useLegacyPackaging = true
        }
    }

    // F-Droid rejects the AGP dependency-metadata block in the APK signing block.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
            // R8 shrinks/optimises release builds (see proguard-rules.pro for the few keeps). R8
            // output is reproducible (pinned AGP fixes the R8 version + mapping); the one
            // non-reproducible artefact, the baseline profile, is dropped below (see ArtProfile).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    // Only the github flavor has the self-updater and REQUEST_INSTALL_PACKAGES (src/github).
    flavorDimensions += "store"
    productFlavors {
        create("github") {
            dimension = "store"
            buildConfigField("boolean", "SELF_UPDATE", "true")
            buildConfigField("boolean", "OCR", "true")
            buildConfigField("boolean", "MATH_RUNTIME_BUNDLED", "false")
        }
        // Play forbids downloading native code, so ONNX Runtime ships inside the APK here.
        create("play") {
            dimension = "store"
            buildConfigField("boolean", "SELF_UPDATE", "false")
            buildConfigField("boolean", "OCR", "true")
            buildConfigField("boolean", "MATH_RUNTIME_BUNDLED", "true")
        }
        // F-Droid allows neither ML Kit (non-free) nor the downloaded ONNX Runtime, so OCR is left out.
        create("fdroid") {
            dimension = "store"
            buildConfigField("boolean", "SELF_UPDATE", "false")
            buildConfigField("boolean", "OCR", "false")
            buildConfigField("boolean", "MATH_RUNTIME_BUNDLED", "false")
        }
    }

    sourceSets {
        getByName("github").java.srcDir("src/mlkit/java")
        getByName("play").java.srcDirs("src/mlkit/java", "src/noupdate/java")
        getByName("fdroid").java.srcDir("src/noupdate/java")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Lists every values-* translation in the manifest so Android 13+ offers a per-app language.
    androidResources {
        generateLocaleConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// The GitHub build downloads ONNX Runtime's core library with the handwritten-maths add-on (see
// MathOcr); only its small JNI bridge ships in that APK.
androidComponents {
    onVariants(selector().withFlavor("store" to "github")) {
        it.packaging.jniLibs.excludes.add("**/libonnxruntime.so")
    }
}

// F-Droid runs verified builds: its from-source APK must byte-match the released one. AGP's
// auto-merged baseline profile (assets/dexopt/baseline.prof, built from the Compose/AndroidX
// library profiles) is not reproducible across build hosts, so omit it from release builds.
// Tradeoff: slightly slower first-run startup (no ART pre-warm profile).
tasks.configureEach {
    if (name.contains("ArtProfile")) enabled = false
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.pdfbox.android)
    implementation(libs.androidsvg)
    implementation(libs.argon2kt)
    implementation(libs.androidx.work.runtime)
    "githubImplementation"(libs.mlkit.digital.ink)
    "playImplementation"(libs.mlkit.digital.ink)
    implementation(libs.commonmark.autolink)
    implementation(libs.latex.base)
    implementation(libs.latex.parser)
    implementation(libs.latex.renderer)
    compileOnly(libs.onnxruntime.android)
    "githubImplementation"(libs.onnxruntime.android)
    "playImplementation"(libs.onnxruntime.android)
    implementation(libs.bundles.commonmark)
    implementation(libs.okhttp)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.kxml2)
}
