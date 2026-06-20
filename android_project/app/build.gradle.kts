import java.util.Properties

val localProperties = Properties().apply {
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) {
        localFile.inputStream().use(::load)
    }
}

fun configuredValue(key: String, fallback: String): String {
    return localProperties.getProperty(key)
        ?: providers.environmentVariable(key).orNull
        ?: fallback
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.navigation.safeargs)
    alias(libs.plugins.google.services)
}

android {
    namespace   = "com.photoconnect"
    compileSdk  = 34

    defaultConfig {
        applicationId   = "com.photoconnect"
        minSdk          = 24
        targetSdk       = 34
        versionCode     = 2
        versionName     = "2.0.0"

        buildConfigField("String", "BASE_URL", "\"https://supriyadigitals.store/phpapp/\"")
        resValue("string", "google_server_client_id", configuredValue("GOOGLE_SERVER_CLIENT_ID", "YOUR_SERVER_CLIENT_ID"))

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        javaCompileOptions {
            annotationProcessorOptions {
                arguments += mapOf(
                    "room.schemaLocation" to "$projectDir/schemas",
                    "room.incremental" to "true",
                )
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled   = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    kapt { correctErrorTypes = true }
    lint {
        disable += "MissingTranslation"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.swiperefresh)
    implementation(libs.viewpager2)
    implementation(libs.splashscreen)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.livedata.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.navigation.fragment.ktx)
    implementation(libs.navigation.ui.ktx)
    implementation(libs.retrofit.core)
    implementation(libs.retrofit.moshi)
    implementation(libs.okhttp.logging)
    implementation(libs.moshi.kotlin)
    implementation(libs.security.crypto)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    kapt(libs.room.compiler)
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    implementation(libs.glide.core)
    kapt(libs.glide.compiler)
    implementation(libs.coroutines.android)
    implementation(libs.lottie)
    implementation(libs.shimmer)
    implementation(libs.circleImageView)
    implementation(libs.image.cropper)
    implementation(libs.work.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext)
    implementation(libs.play.services.auth)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.mlkit.translate)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
}
