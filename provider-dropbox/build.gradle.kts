plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.kunzisoft.filesync.provider.dropbox"
    compileSdk = 35
    defaultConfig {
        minSdk = 21
    }
}

dependencies {
    implementation(project(":common"))
    implementation(project(":provider-api"))
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
