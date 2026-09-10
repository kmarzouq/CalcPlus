import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Release signing is read from an untracked keystore.properties at the android/
// project root. Absent (CI without secrets, contributors) -> unsigned release.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

val ndkPinned = "27.2.12479018"

android {
    namespace = "io.github.kmarzouq.calcplus"
    compileSdk = 36
    ndkVersion = ndkPinned

    defaultConfig {
        applicationId = "io.github.kmarzouq.calcplus"
        minSdk = 31
        targetSdk = 36
        versionCode = 3
        versionName = "0.2.0"

        // GrapheneOS / Pixel are all arm64. One ABI = one small .so, no fat APK.
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig =
                if (keystorePropsFile.exists()) signingConfigs.getByName("release") else null
        }
        debug {
            applicationIdSuffix = ".debug"
            isPseudoLocalesEnabled = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        buildConfig = false
        viewBinding = true
    }

    androidResources {
        // Keep only the languages we actually ship translations for.
        localeFilters += listOf("en")
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/*.version",
                "/kotlin/**",
                "**/*.kotlin_module",
            )
        }
        jniLibs { useLegacyPackaging = false }
    }

    dependenciesInfo {
        // No Play dependency metadata blob (privacy + reproducibility).
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    // Intentionally almost nothing: no androidx.core, no AppCompat, no Compose,
    // no Material. Those drag in a self-signed permission, ProfileInstaller
    // components and an appComponentFactory — this app ships a manifest with
    // zero <uses-permission> and zero injected components.
    testImplementation(libs.junit)
}

configurations.all {
    // Belt and braces: if some future dependency pulls these transitively,
    // fail the build instead of silently growing the manifest.
    exclude(group = "androidx.profileinstaller")
    exclude(group = "androidx.startup")
}

// ---------------------------------------------------------------------------
// Rust: build libcalc.so via cargo-ndk and feed it into the APK's jniLibs.
// Requires `cargo` + `cargo-ndk` on PATH and a resolvable NDK.
// ---------------------------------------------------------------------------
val rustWorkspace = rootProject.file("../core")
val rustJniLibsDir = layout.buildDirectory.dir("rustJniLibs").get().asFile
val ndkHome = android.sdkDirectory.resolve("ndk/$ndkPinned")

val cargoNdkBuild = tasks.register<Exec>("cargoNdkBuild") {
    group = "rust"
    description = "Cross-compiles calc-ffi to libcalc.so (arm64-v8a)."
    workingDir = rustWorkspace

    inputs.dir(rustWorkspace.resolve("calc-core/src"))
    inputs.dir(rustWorkspace.resolve("calc-ffi/src"))
    inputs.files(
        rustWorkspace.resolve("Cargo.toml"),
        rustWorkspace.resolve("Cargo.lock"),
        rustWorkspace.resolve("calc-core/Cargo.toml"),
        rustWorkspace.resolve("calc-ffi/Cargo.toml"),
        rustWorkspace.resolve("rust-toolchain.toml"),
    )
    outputs.dir(rustJniLibsDir)

    environment("ANDROID_NDK_HOME", ndkHome.absolutePath)
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a",
        "-o", rustJniLibsDir.absolutePath,
        "build", "-p", "calc-ffi", "--release", "--locked",
    )
}

android.sourceSets["main"].jniLibs.srcDir(rustJniLibsDir)
tasks.named("preBuild") { dependsOn(cargoNdkBuild) }
