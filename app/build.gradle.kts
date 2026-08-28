import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipFile

plugins { alias(libs.plugins.android.application); alias(libs.plugins.kotlin.android); alias(libs.plugins.kotlin.serialization); alias(libs.plugins.compose.compiler); id("com.chaquo.python") }

val nodeVersion = "18.20.4"
val nodeArchiveSha256 = "bd7321eaa1a7602fbe0bb87302df2d79d87835cf4363fbdd17c350dbb485c2af"
val nodeArchiveUrl = "https://github.com/nodejs-mobile/nodejs-mobile/releases/download/v$nodeVersion/nodejs-mobile-v$nodeVersion-android.zip"
val nodeCacheDir = File(gradle.gradleUserHomeDir, "caches/agentdroid-node/$nodeVersion")
val nodeArchive = File(nodeCacheDir, "nodejs-mobile-$nodeVersion-android.zip")
val nodeJniDir = layout.buildDirectory.dir("generated/nodeJniLibs")

val prepareNodeRuntime by tasks.registering {
    outputs.dir(nodeJniDir)
    doLast {
        nodeCacheDir.mkdirs()
        if (!nodeArchive.exists()) {
            val connection = URI(nodeArchiveUrl).toURL().openConnection().apply {
                connectTimeout = 30_000; readTimeout = 120_000; setRequestProperty("User-Agent", "AgentDroid-build")
            }
            connection.getInputStream().use { input -> nodeArchive.outputStream().buffered().use { output -> input.copyTo(output) } }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        nodeArchive.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != nodeArchiveSha256) { nodeArchive.delete(); throw GradleException("Node.js Mobile archive SHA-256 mismatch: $actual") }
        val outputRoot = nodeJniDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        ZipFile(nodeArchive).use { zip ->
            mapOf("arm64-v8a" to "bin/arm64-v8a/libnode.so", "x86_64" to "bin/x86_64/libnode.so").forEach { (abi, path) ->
                val entry = zip.getEntry(path) ?: throw GradleException("Missing Node.js Mobile entry $path")
                val target = File(outputRoot, "$abi/libnode.so").apply { parentFile.mkdirs() }
                zip.getInputStream(entry).use { input -> target.outputStream().buffered().use { output -> input.copyTo(output) } }
            }
        }
    }
}

android { namespace = "com.agentdroid"; compileSdk = 35
    defaultConfig {
        applicationId = "com.agentdroid"; minSdk = 26; targetSdk = 35; versionCode = 2; versionName = "1.1.0-alpha2"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        kotlinOptions.jvmTarget = "1.8"
        kotlinOptions.freeCompilerArgs += "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
    }
    buildTypes { debug { applicationIdSuffix = ".debug" }; release { isMinifyEnabled = true; isShrinkResources = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") } }
    buildFeatures { compose = true; buildConfig = true }
    sourceSets.getByName("main").jniLibs.srcDir(nodeJniDir)
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"; jniLibs.useLegacyPackaging = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(prepareNodeRuntime) }

chaquopy { defaultConfig { version = "3.13" } }

dependencies {
    implementation(project(":core:model")); implementation(project(":core:ai")); implementation(project(":core:agent")); implementation(project(":core:permissions")); implementation(project(":core:workspace")); implementation(project(":core:runtime")); implementation(project(":core:terminal")); implementation(project(":core:git")); implementation(project(":core:browser")); implementation(project(":core:phone")); implementation(project(":core:tasks")); implementation(project(":core:research")); implementation(project(":core:artifacts")); implementation(project(":core:subagents")); implementation(project(":core:localai")); implementation(project(":core:mcp")); implementation(project(":data:database"))
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation(platform(libs.androidx.compose.bom)); implementation(libs.androidx.compose.ui); implementation(libs.androidx.compose.ui.tooling.preview); debugImplementation(libs.androidx.compose.ui.tooling); debugImplementation("androidx.compose.ui:ui-test-manifest"); implementation(libs.androidx.compose.material3); implementation(libs.androidx.compose.icons)
    implementation(libs.androidx.activity.compose); implementation(libs.androidx.core.ktx); implementation(libs.androidx.lifecycle.runtime); implementation(libs.androidx.lifecycle.viewmodel); implementation(libs.androidx.navigation.compose); implementation(libs.appcompat); implementation(libs.material); implementation(libs.androidx.room.runtime); implementation(libs.androidx.room.ktx); implementation(libs.dataStore.preferences); implementation(libs.commonmark); implementation(libs.commonmark.tables); implementation(libs.kotlinx.coroutines); implementation(libs.serialization.json)
    implementation("androidx.work:work-runtime:2.11.2")
    testImplementation(libs.mockwebserver); testImplementation("junit:junit:4.13.2"); testImplementation(libs.kotlinx.coroutines)
    androidTestImplementation(libs.androidx.test.core); androidTestImplementation("androidx.test:runner:1.6.2"); androidTestImplementation(libs.espresso.core); androidTestImplementation(platform(libs.androidx.compose.bom)); androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
