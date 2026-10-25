plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.android) }
android {
    namespace = "dev.aster.termux"
    compileSdk = 35
    ndkVersion = "29.0.14206865"
    defaultConfig { minSdk = 24; ndk { abiFilters += "arm64-v8a" } }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.28.3" } }
    sourceSets.getByName("main").jniLibs.srcDir(rootProject.file("build/native/runtime"))
    sourceSets.getByName("main").assets.srcDir(rootProject.file("build/native/runtime-assets"))
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs { useLegacyPackaging = true; keepDebugSymbols += "**/libaster_*.so" } }
}

val prepareRuntimeNative by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("python3", "-B", "scripts/prepare_native.py", "--component", "runtime", "--force")
    inputs.files(rootProject.file("scripts/prepare_native.py"), rootProject.file("core/termux-runtime/src/main/assets/runtime/checksums.json"))
    outputs.dir(rootProject.file("build/native/runtime"))
    outputs.dir(rootProject.file("build/native/runtime-assets"))
}
tasks.named("preBuild").configure { dependsOn(prepareRuntimeNative) }
