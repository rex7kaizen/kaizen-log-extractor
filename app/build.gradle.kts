plugins {
    id("com.android.application")
}

android {
    namespace = "com.kaizen.logextractor"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kaizen.logextractor"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "1.4.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // TDLib is distributed as an Android AAR with native JNI libraries.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("com.github.capullo-tech:lib-tdlib-android:11850efeb5791d4fd386c0eeec1ebce4c5080435")
    implementation("com.github.junrar:junrar:8.1.2")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.tukaani:xz:1.12")
    testImplementation("junit:junit:4.13.2")
}
