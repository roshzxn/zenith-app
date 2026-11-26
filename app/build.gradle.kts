plugins {
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.secrets)
}

android {
  namespace = "com.zenith.app"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.zenith.app"
    minSdk = 24
    targetSdk = 36
    versionCode = 2
    versionName = "2.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    create("debugConfig") {
      val localKeystore = file("${rootDir}/debug.keystore")
      val homeKeystore = file("${System.getProperty("user.home")}/.android/debug.keystore")
      storeFile = if (localKeystore.exists()) localKeystore else homeKeystore
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      val uploadKey = file(keystorePath)
      val allowDebugFallback = (findProperty("zenith.allowDebugReleaseSigning") as? String)?.toBoolean() == true ||
        System.getenv("ALLOW_DEBUG_RELEASE_SIGNING")?.toBoolean() == true
      if (uploadKey.exists()) {
        storeFile = uploadKey
        storePassword = System.getenv("STORE_PASSWORD") ?: (findProperty("STORE_PASSWORD") as? String ?: "")
        keyAlias = System.getenv("KEY_ALIAS") ?: (findProperty("KEY_ALIAS") as? String ?: "upload")
        keyPassword = System.getenv("KEY_PASSWORD") ?: (findProperty("KEY_PASSWORD") as? String ?: "")
      } else if (allowDebugFallback) {
        val localKeystore = file("${rootDir}/debug.keystore")
        val homeKeystore = file("${System.getProperty("user.home")}/.android/debug.keystore")
        storeFile = if (localKeystore.exists()) localKeystore else homeKeystore
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
      }
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      val releaseSigning = signingConfigs.getByName("release")
      if (releaseSigning.storeFile != null) {
        signingConfig = releaseSigning
      }
    }
    debug { signingConfig = signingConfigs.getByName("debugConfig") }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
}

dependencies {
  constraints {
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("androidx.concurrent:concurrent-futures-ktx:1.2.0")
    implementation("androidx.concurrent:concurrent-futures:1.2.0")
    testImplementation("androidx.test.espresso:espresso-core:3.5.1")
  }

  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
  implementation("io.github.jan-tennert.supabase:postgrest-kt:3.0.1")
  implementation("io.github.jan-tennert.supabase:auth-kt:3.0.1")
  implementation("io.github.jan-tennert.supabase:realtime-kt:3.0.1")
  implementation("io.ktor:ktor-client-okhttp:3.0.0")
  implementation("io.ktor:ktor-client-core:3.0.0")
  implementation("io.ktor:ktor-client-websockets:3.0.0")
  implementation("io.ktor:ktor-client-content-negotiation:3.0.0")
  implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.0")

  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.camera.camera2)
  implementation(libs.androidx.camera.core)
  implementation(libs.androidx.camera.lifecycle)
  implementation(libs.androidx.camera.view)
  implementation("com.google.mlkit:barcode-scanning:17.3.0")
  implementation("com.google.mlkit:text-recognition:16.0.1")
  implementation("com.google.zxing:core:3.5.3")

  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)

  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)

  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)

  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)

  "ksp"(libs.androidx.room.compiler)
}
