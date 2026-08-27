plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.agentdroid.core.research"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "1.8"
}

dependencies {
    implementation(project(":core:agent"))
    implementation(libs.kotlinx.coroutines)
    implementation(libs.serialization.json)
    api(libs.okhttp)
    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.kotlinx.coroutines)
    testImplementation(libs.mockwebserver)
}
