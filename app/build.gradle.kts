plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val pittechVersionCode = providers.gradleProperty("pittechVersionCode").orNull?.toIntOrNull() ?: 1
val pittechVersionName = providers.gradleProperty("pittechVersionName").orNull ?: "0.1.0"
val pittechLiveAds = providers.gradleProperty("pittechLiveAds").orNull?.toBooleanStrictOrNull() ?: false
val admobAppId = "ca-app-pub-2708638041809482~5194145527"
val testNativeAdUnitId = "ca-app-pub-3940256099942544/2247696110"
val liveNativeAdUnitId = "ca-app-pub-2708638041809482/6602595967"
val nativeAdUnitId = if (pittechLiveAds) liveNativeAdUnitId else testNativeAdUnitId

require(pittechVersionCode in 1..2_100_000_000) {
    "pittechVersionCode must be between 1 and 2100000000."
}

android {
    namespace = "com.pittech"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.pittech"
        minSdk = 26
        targetSdk = 36
        versionCode = pittechVersionCode
        versionName = pittechVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resValue("string", "admob_app_id", admobAppId)
        buildConfigField("String", "PITTECH_NATIVE_AD_UNIT_ID", "\"$nativeAdUnitId\"")
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // Debug installs and UI tests must never request paid inventory, even if
            // a developer accidentally passes -PpittechLiveAds=true.
            buildConfigField("String", "PITTECH_NATIVE_AD_UNIT_ID", "\"$testNativeAdUnitId\"")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)

    implementation("com.google.android.libraries.ads.mobile.sdk:ads-mobile-sdk:1.4.0")
    implementation("com.google.android.ump:user-messaging-platform:4.0.0")

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")

    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")

    androidTestImplementation(platform(composeBom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
