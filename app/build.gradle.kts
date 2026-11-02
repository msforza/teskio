plugins {
    id("com.android.application")
}

android {
    namespace = "eu.ztor.keyman"
    compileSdk = 35

    defaultConfig {
        applicationId = "eu.ztor.keyman"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}
