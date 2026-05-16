plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}
android {
    namespace = "dev.atvremote.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "dev.atvremote.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    sourceSets["main"].kotlin.srcDir("src/main/kotlin")
    sourceSets["test"].kotlin.srcDir("src/test/kotlin")
    sourceSets["androidTest"].kotlin.srcDir("src/androidTest/kotlin")
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.useJUnitPlatform() }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes { release { isMinifyEnabled = false } }
}
dependencies {
    implementation(project(":protocol"))
    implementation(libs.coroutines.core)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.androidx.datastore.preferences)
    // DURABLE :app-wide decision (Plan-3 T4) — do NOT revert per-task: kotlin.test
    // must bind the JUnit4 backend so kotlin.test.Test == org.junit.Test; Robolectric's
    // @RunWith(RobolectricTestRunner) is a JUnit4 runner (bare kotlin("test") resolves to
    // kotlin-test-junit5 and silently runs Robolectric methods OUTSIDE the sandbox).
    // This underpins ALL :app Robolectric tests across the remaining 9 Plan-3 tasks.
    //
    // kotlin.test must bind to the JUnit4 backend so kotlin.test.Test maps to
    // org.junit.Test: Robolectric's @RunWith(RobolectricTestRunner) is a JUnit4
    // runner and only discovers JUnit4 @Test methods. The bare kotlin("test")
    // transitively resolves to kotlin-test-junit5 (junit-jupiter is on the
    // classpath via junit-vintage-engine), which would (a) make Robolectric see
    // "no runnable methods" and (b) let the Jupiter engine run the methods
    // OUTSIDE the Robolectric sandbox. junit-vintage-engine then bridges the
    // JUnit4 classes (plain or @RunWith) into useJUnitPlatform(). See Task-4.
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.junit4)
    testImplementation(libs.junit.vintage.engine)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
