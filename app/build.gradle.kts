plugins { id("com.android.application") }

android {
    namespace = "com.sobigrice.smsrelay"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.sobigrice.smsrelay"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.1.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { viewBinding = true }
    packaging.resources.merges += listOf("META-INF/LICENSE.md", "META-INF/NOTICE.md")
    testOptions { unitTests.isReturnDefaultValues = false }
}

dependencies {
    implementation("androidx.core:core:1.16.0")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("org.eclipse.angus:jakarta.mail:2.0.5")
    implementation("org.eclipse.angus:angus-activation:2.0.3")
    implementation("jakarta.activation:jakarta.activation-api:2.1.4")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
}
