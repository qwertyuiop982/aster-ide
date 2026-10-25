plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "dev.aster.agent"
    compileSdk = 35
    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    testOptions { unitTests.isReturnDefaultValues = true }
    lint { abortOnError = true; disable += "MissingTranslation" }
}
// Keep the checked-in local IIFE in sync for ordinary Gradle builds as well as preview exports.
val bundleAgentUi by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("node", "scripts/build_agent_ui.mjs")
    inputs.files(fileTree("src/main/assets/agent") {
        include("**/*.js")
        exclude("app.bundle.js", "boot.js")
    })
    inputs.file(rootProject.file("scripts/build_agent_ui.mjs"))
    inputs.file(rootProject.file("research/pi-agent/package-lock.json"))
    outputs.file(file("src/main/assets/agent/app.bundle.js"))
    outputs.file(rootProject.file("agent/design/UI-BUNDLE.json"))
}
tasks.named("preBuild").configure { dependsOn(bundleAgentUi) }

dependencies {

    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.preview)
    implementation(libs.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")


    debugImplementation(libs.compose.tooling)
    implementation(project(":core:app"))
}
