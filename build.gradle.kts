plugins {
    id("com.android.application") version "8.5.2"
}

android {
    namespace = "com.walkiewifi"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.walkiewifi"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
