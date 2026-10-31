plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    // Declared here so it's available to apply, but NOT applied anywhere by
    // default — app/build.gradle.kts only applies it once a real
    // google-services.json exists (see CLOUD-SETUP.md), so a build with no
    // Firebase project configured yet is completely unaffected.
    id("com.google.gms.google-services") version "4.4.2" apply false
}
