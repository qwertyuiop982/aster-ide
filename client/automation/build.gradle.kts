plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.android) }
android {
    namespace = "dev.aster.client.automation"
    compileSdk = 35
    defaultConfig { minSdk = 24; consumerProguardFiles("consumer-rules.pro") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    api(project(":client:shizuku"))
    api(project(":client:browser"))
}
