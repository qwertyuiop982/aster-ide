plugins {
    id("com.android.application")
}

android {
    namespace = "com.liulanqi.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.liulanqi.app"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "1.0-beta.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
}