plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val pittechVersionCode = providers.gradleProperty("pittechVersionCode").orNull?.toIntOrNull() ?: 1
val pittechVersionName = providers.gradleProperty("pittechVersionName").orNull ?: "0.1.0"
val pittechLiveAds = providers.gradleProperty("pittechLiveAds").orNull?.toBooleanStrictOrNull() ?: false
// Owner-gated: the monthly PitTech Pro product ID. Empty until the store
// product exists; the paywall treats empty as not-configured.
val pittechProProductId = providers.gradleProperty("pittechProProductId").orNull ?: ""
// Defaults preserve the disabled online rollout. Staging is confined to Dev/Debug installs.
val pittechOnlineEnvironment = providers.gradleProperty("pittechOnlineEnvironment").orNull ?: "production"
require(pittechOnlineEnvironment in setOf("production", "staging")) { "Unsupported online environment." }
val pittechOnlineEnabled = providers.gradleProperty("pittechOnlineEnabled").orNull?.let {
    requireNotNull(it.toBooleanStrictOrNull()) { "pittechOnlineEnabled must be true or false." }
} ?: false
require(pittechProProductId.matches(Regex("[A-Za-z0-9._-]{0,200}"))) { "Invalid monthly product ID." }
val pittechStoreSandboxBuild = providers.gradleProperty("pittechStoreSandboxBuild").orNull?.let {
    requireNotNull(it.toBooleanStrictOrNull()) { "pittechStoreSandboxBuild must be true or false." }
} ?: false
require(!pittechStoreSandboxBuild || pittechOnlineEnvironment == "staging" && !pittechLiveAds) {
    "Store sandbox builds require staging and test ads."
}
val pittechPublicHost = if (pittechOnlineEnvironment == "staging") "staging.pittech.app" else "pittech.app"
val pittechLinkScheme = if (pittechOnlineEnvironment == "staging") "pittech-staging" else "pittech"
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
        buildConfigField("String", "PITTECH_NATIVE_AD_UNIT_ID", "\"$nativeAdUnitId\"")
        buildConfigField("String", "PITTECH_PRO_PRODUCT_ID", "\"$pittechProProductId\"")
        buildConfigField("String", "PITTECH_ONLINE_ENVIRONMENT", "\"${pittechOnlineEnvironment}\"")
        buildConfigField("boolean", "PITTECH_ONLINE_ENABLED", pittechOnlineEnabled.toString())
        manifestPlaceholders["pittechPublicHost"] = pittechPublicHost
        manifestPlaceholders["pittechLinkScheme"] = pittechLinkScheme
        buildConfigField("boolean", "CONTROLLER_TESTING_ENABLED", "false")
        buildConfigField("boolean", "TELEMETRY_BETA_ENABLED", "true")
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // Debug installs and UI tests must never request paid inventory, even if
            // a developer accidentally passes -PpittechLiveAds=true.
            buildConfigField("String", "PITTECH_NATIVE_AD_UNIT_ID", "\"$testNativeAdUnitId\"")
            buildConfigField("boolean", "CONTROLLER_TESTING_ENABLED", "true")
            buildConfigField("boolean", "TELEMETRY_BETA_ENABLED", "true")
        }

        // Firebase Dev can be installed beside both the Play app and local debug builds.
        create("dev") {
            initWith(getByName("release"))
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            buildConfigField("boolean", "CONTROLLER_TESTING_ENABLED", "true")
            buildConfigField("boolean", "TELEMETRY_BETA_ENABLED", "true")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets.getByName("debug").kotlin.srcDir("src/internal/java")
    sourceSets.getByName("dev").kotlin.srcDir("src/internal/java")
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
    sourceSets.getByName("test").resources.srcDir("$rootDir/contracts/fixtures")

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    testImplementation("com.squareup.okhttp3:mockwebserver3:5.5.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation("com.github.combustion-inc:combustion-android-ble:v3.5.4")
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)

    implementation("com.google.android.libraries.ads.mobile.sdk:ads-mobile-sdk:1.4.0")
    implementation("com.google.android.ump:user-messaging-platform:4.0.0")

    // Play Billing for the one monthly PitTech Pro product. Only long-stable
    // base-artifact callback APIs are used (wrapped in coroutines); Play
    // sandbox/test tracks use the same product ID as production.
    implementation("com.android.billingclient:billing-ktx:9.1.0")

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")

    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    // Pinned to the verified earlier Maven Central release after the clean public-artifact
    // audit could not resolve 3.5.4; release artifact auditing must prove this pin.
    implementation("com.google.zxing:core:3.5.3")

    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")

    androidTestImplementation(platform(composeBom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}

tasks.matching { it.name == "mergeDebugAndroidTestAssets" }.configureEach { dependsOn("kspDebugKotlin") }

// Produce inspectable evidence from the resolved production graph, independent of hidden UI routes.
tasks.register("recordPublicRuntimeDependencies") {
    doLast {
        val output = layout.buildDirectory.file("reports/telemetry/release-runtime-dependencies.txt").get().asFile
        output.parentFile.mkdirs()
        output.writeText(configurations.getByName("releaseRuntimeClasspath").resolvedConfiguration.resolvedArtifacts
            .map { it.moduleVersion.id.toString() }.distinct().sorted().joinToString("\n", postfix = "\n"))
    }
}

// Using the registered Play package against staging requires a deliberate sandbox build.
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    doFirst { check(pittechOnlineEnvironment != "staging" || pittechStoreSandboxBuild) { "Staging requires Dev/Debug or an explicit store sandbox build." } }
}
