plugins { alias(libs.plugins.android.application); alias(libs.plugins.kotlin.android); alias(libs.plugins.kotlin.serialization); alias(libs.plugins.compose.compiler); id("com.chaquo.python") }

android { namespace = "com.agentdroid"; compileSdk = 35
    defaultConfig {
        applicationId = "com.agentdroid"; minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "1.0.0"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach { kotlinOptions.jvmTarget = "1.8" }
    buildTypes { debug { applicationIdSuffix = ".debug" }; release { isMinifyEnabled = true; isShrinkResources = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") } }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

chaquopy { defaultConfig { version = "3.13" } }

dependencies {
    implementation(project(":core:model")); implementation(project(":core:ai")); implementation(project(":core:agent")); implementation(project(":core:permissions")); implementation(project(":core:workspace")); implementation(project(":core:runtime")); implementation(project(":core:terminal")); implementation(project(":core:git")); implementation(project(":core:browser")); implementation(project(":core:tasks")); implementation(project(":core:research")); implementation(project(":core:artifacts")); implementation(project(":core:subagents")); implementation(project(":core:localai")); implementation(project(":core:mcp")); implementation(project(":data:database"))
    implementation(platform(libs.androidx.compose.bom)); implementation(libs.androidx.compose.ui); implementation(libs.androidx.compose.ui.tooling.preview); debugImplementation(libs.androidx.compose.ui.tooling); debugImplementation("androidx.compose.ui:ui-test-manifest"); implementation(libs.androidx.compose.material3); implementation(libs.androidx.compose.icons)
    implementation(libs.androidx.activity.compose); implementation(libs.androidx.core.ktx); implementation(libs.androidx.lifecycle.runtime); implementation(libs.androidx.lifecycle.viewmodel); implementation(libs.androidx.navigation.compose); implementation(libs.appcompat); implementation(libs.material); implementation(libs.androidx.room.runtime); implementation(libs.androidx.room.ktx); implementation(libs.dataStore.preferences); implementation(libs.commonmark); implementation(libs.commonmark.tables); implementation(libs.kotlinx.coroutines); implementation(libs.serialization.json)
    implementation("androidx.work:work-runtime:2.11.2")
    testImplementation(libs.mockwebserver); testImplementation("junit:junit:4.13.2"); testImplementation(libs.kotlinx.coroutines)
    androidTestImplementation(libs.androidx.test.core); androidTestImplementation("androidx.test:runner:1.6.2"); androidTestImplementation(libs.espresso.core); androidTestImplementation(platform(libs.androidx.compose.bom)); androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
