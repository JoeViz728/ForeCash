plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.forecash.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.forecash.app"
        minSdk = 29 // Android 10 - aligns with design decision
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }

    // =====================================================================
    // Compose Compiler — Official Kotlin 2.0+ Setup
    // https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler
    //
    // • Plugin: org.jetbrains.kotlin.plugin.compose
    //   Declared in gradle/libs.versions.toml with the **same version** as 'kotlin'
    // • Applied in the plugins block at the top of this file.
    //
    // The plugin automatically provides the correct Compose compiler.
    //
    // We deliberately do NOT use the legacy/deprecated block:
    //     composeOptions {
    //         kotlinCompilerExtensionVersion = "..."
    //     }
    //
    // The composeCompiler {} block below is optional (advanced options only).
    // =====================================================================
    composeCompiler {
        // reportsDestination = layout.buildDirectory.dir("compose_compiler")
        // enableStrongSkippingMode = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
