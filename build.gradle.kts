import com.android.build.api.dsl.CommonExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

val hostBuildToolsVersion = providers.gradleProperty("asterAndroidBuildToolsVersion").orNull
val hostCmakeVersion = providers.gradleProperty("asterAndroidCmakeVersion").orNull
if (hostBuildToolsVersion != null || hostCmakeVersion != null) {
    fun configureHostTools(dsl: CommonExtension<*, *, *, *, *, *>) {
        hostBuildToolsVersion?.let { dsl.buildToolsVersion = it }
        val cmake = dsl.externalNativeBuild.cmake
        hostCmakeVersion?.let { if (cmake.path != null) cmake.version = it }
    }

    subprojects {
        // Apply host overrides after the modules' desktop toolchain defaults.
        pluginManager.withPlugin("com.android.application") {
            extensions.configure<ApplicationAndroidComponentsExtension> {
                finalizeDsl { dsl -> configureHostTools(dsl) }
            }
        }
        pluginManager.withPlugin("com.android.library") {
            extensions.configure<LibraryAndroidComponentsExtension> {
                finalizeDsl { dsl -> configureHostTools(dsl) }
            }
        }
    }
}
