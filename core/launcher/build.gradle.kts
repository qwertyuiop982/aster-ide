plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "dev.aster.ide"
    compileSdk = 35
    ndkVersion = "29.0.14206865"
    defaultConfig {
        applicationId = "dev.aster.account"
        minSdk = 24
        // API 28 permits compiler executables in app-private storage. Not a Play Store build.
        targetSdk = 28
        versionCode = 12
        versionName = "1.0.1"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    sourceSets.getByName("main").jniLibs.srcDir(rootProject.file("build/native/codex"))
    buildTypes {
        debug { versionNameSuffix = "-agent-fix2" }
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { jniLibs { useLegacyPackaging = true; keepDebugSymbols += "**/libaster_*.so" }; resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/INDEX.LIST", "META-INF/*.md") }
    // This direct-install runtime deliberately targets 28 to allow app-private compiler exec.
    lint { abortOnError = true; disable += setOf("MissingTranslation", "ExpiredTargetSdkVersion") }
    testOptions { unitTests.isReturnDefaultValues = true }
}
layout.buildDirectory.set(rootProject.layout.projectDirectory.dir("core/build/application"))
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
    implementation(project(":ide:app"))
    implementation(project(":agent:app"))
}

val prepareCodexNative by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("python3", "-B", "scripts/prepare_native.py", "--component", "codex", "--force")
    inputs.files(rootProject.file("scripts/prepare_native.py"), rootProject.file("core/termux-runtime/src/main/assets/runtime/checksums.json"))
    inputs.dir(rootProject.file("vendor/codex/codex-rs"))
    inputs.files(rootProject.file("scripts/build_codex_agent.py"), rootProject.file("scripts/build_codex_host.py"), rootProject.file("scripts/termux_agent_sysroot.py"))
    outputs.dir(rootProject.file("build/native/codex"))
}
tasks.named("preBuild").configure { dependsOn(prepareCodexNative) }
