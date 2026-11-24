plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "ae.livechat.example"
    compileSdk = 36

    defaultConfig {
        applicationId = "ae.livechat.example"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":livechat"))
}
