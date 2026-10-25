plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.android) }
val qingzhouEngine by tasks.registering(Sync::class) {
    from(rootProject.file("vendor/qingzhou/app/src/main/java")) { include("com/liulanqi/app/BrowserEngine.kt", "com/liulanqi/app/WebViewEngine.kt") }
    into(layout.buildDirectory.dir("generated/qingzhouEngine"))
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(qingzhouEngine) }
android {
    namespace = "dev.aster.client.browser"
    compileSdk = 35
    defaultConfig { minSdk = 24; consumerProguardFiles("consumer-rules.pro") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    sourceSets.getByName("main").java.srcDir(layout.buildDirectory.dir("generated/qingzhouEngine"))

}
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

}
