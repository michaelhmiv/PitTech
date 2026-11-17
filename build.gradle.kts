plugins {
    id("com.android.application") version "9.4.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
    id("com.google.devtools.ksp") version "2.3.12" apply false
    // Applied by the app module only when app/google-services.json exists
    // (issue #322): CI/dev builds without Firebase client config must build
    // and run with analytics as a no-op.
    id("com.google.gms.google-services") version "4.6.0" apply false
}
