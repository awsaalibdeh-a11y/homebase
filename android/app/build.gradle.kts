plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "app.homebase.family"
    compileSdk = 34
    defaultConfig {
        applicationId = "app.homebase.family"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "1.6.1"
        ndk { abiFilters += (if (project.hasProperty("withX86")) listOf("x86_64") else listOf("arm64-v8a", "armeabi-v7a")) }
        buildConfigField("String", "DEFAULT_SERVER", "\"${project.findProperty("homebaseServer") ?: "https://homebase-family.onrender.com"}\"")
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    packaging { resources { excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES") } }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-process:2.8.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.osmdroid:osmdroid-android:6.1.18")
    implementation("io.coil-kt:coil-compose:2.6.0")          // pictures in chat, and GIFs that move
    implementation("io.coil-kt:coil-gif:2.6.0")
    implementation("androidx.camera:camera-core:1.3.4")           // the in-app camera for photos and profile pictures
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("com.google.mlkit:face-detection:16.1.6")      // checks a profile picture really shows a face (on the phone, no upload)
    implementation("io.getstream:stream-webrtc-android:1.1.1")
}
