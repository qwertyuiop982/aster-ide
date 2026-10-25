pluginManagement {
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id.startsWith("com.android.")) useModule("com.android.tools.build:gradle:${requested.version}")
        }
    }
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        google(); mavenCentral(); gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        google(); mavenCentral()
    }
}
rootProject.name = "AsterIDE"
include(":app", ":core:app", ":ide:app", ":agent:app", ":terminal-emulator", ":terminal-view", ":termux-runtime")
project(":app").projectDir = file("core/launcher")
project(":termux-runtime").projectDir = file("core/termux-runtime")
project(":terminal-emulator").projectDir = file("vendor/termux-app/terminal-emulator")
project(":terminal-view").projectDir = file("vendor/termux-app/terminal-view")

include(":client:shizuku", ":client:browser", ":client:automation")
