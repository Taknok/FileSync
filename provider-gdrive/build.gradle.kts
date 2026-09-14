plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.kunzisoft.filesync.provider.gdrive"
    compileSdk = 37

    defaultConfig {
        minSdk = 21
    }

    kotlin {
        compilerOptions {
            languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        }
    }
}

dependencies {
    implementation(project(":common"))
    implementation(project(":provider-api"))
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
