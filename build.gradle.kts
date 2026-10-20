// Top-level build file
// See Compose setup guide: https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.jetbrains.kotlin.android) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.compose.compiler) apply false
}

