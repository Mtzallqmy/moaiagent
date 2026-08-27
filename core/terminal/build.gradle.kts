plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.android) }

android {
    namespace = "com.agentdroid.core.terminal"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
}
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach { kotlinOptions.jvmTarget = "1.8" }

dependencies {
    implementation(project(":core:runtime"))
    api(libs.termux.terminal.view)
    implementation(libs.kotlinx.coroutines)
    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.kotlinx.coroutines)
}
