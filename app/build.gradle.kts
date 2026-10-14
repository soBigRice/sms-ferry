plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.sobigrice.smsrelay"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.sobigrice.smsrelay"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "0.2.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    packaging.resources.merges += listOf("META-INF/LICENSE.md", "META-INF/NOTICE.md")
    testOptions { unitTests.isReturnDefaultValues = false }
}

dependencies {
    // Compatible with compileSdk 36 and AGP's built-in Kotlin 2.2.10.
    val composeBom = platform("androidx.compose:compose-bom:2025.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    implementation("androidx.core:core:1.16.0")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("org.eclipse.angus:jakarta.mail:2.0.5")
    implementation("org.eclipse.angus:angus-activation:2.0.3")
    implementation("jakarta.activation:jakarta.activation-api:2.1.4")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
}
