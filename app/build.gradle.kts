plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "cl.fernando.djnube"
    compileSdk = 35

    defaultConfig {
        applicationId = "cl.fernando.djnube"
        minSdk = 26
        targetSdk = 35
        versionCode = 18
        versionName = "0.12.0"

        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
        prefab = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    androidResources { noCompress += "onnx" }
    packaging { jniLibs.pickFirsts += "**/libc++_shared.so" }
}

dependencies {
    implementation("com.google.oboe:oboe:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
