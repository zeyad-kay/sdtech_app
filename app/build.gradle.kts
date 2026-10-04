plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.sdtech_android_native"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.sdtech_android_native"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    androidResources {
        // The bundled Parakeet TDT .tflite must stay uncompressed in the APK so
        // LiteRT can open it directly from the asset manager (mmap-style), rather
        // than needing to inflate it into a temp file first.
        noCompress += "tflite"
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.video)
    implementation(libs.androidx.camera.view)

    implementation(libs.kotlinx.coroutines.android)

    // litert-api's JNI shim (liblitert_jni.so) loads the actual LiteRT engine
    // (libLiteRt.so) at runtime. That .so ships in the separate "litert" AAR,
    // but that AAR's AndroidManifest.xml declares the exact same package name
    // as litert-api's, which this AGP version's namespace-uniqueness check
    // rejects as a hard build error. Since "litert" contributes nothing else
    // we need (its classes.jar is just the legacy org.tensorflow.lite
    // Interpreter API), its two .so files are vendored directly into
    // src/main/jniLibs instead of depending on the AAR — see README note
    // there for how to update them.
    implementation(libs.litert.api)
}
