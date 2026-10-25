package dev.aster.ide.runtime

import dev.aster.ide.data.Workspace
import java.io.File

object BuildProfiles {
    val names = listOf("自动", "Android APK", "Python", "C / C++", "Node.js", "TypeScript", "Rust", "Cargo", "Go", "Java", "Kotlin", "Shell", "npm build", "CMake", "自定义")
    fun quote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"
    fun command(profile: String, workspace: Workspace, path: String?, custom: String): String {
        val p = path ?: ""
        val file = quote(p)
        val stem = quote(File(p).nameWithoutExtension)
        val extension = p.substringAfterLast('.').lowercase()
        val selected = if (profile == "自动") when {
            File(workspace.directory, "gradlew").exists() || File(workspace.directory, "build.gradle.kts").exists() -> "Android APK"
            File(workspace.directory, "Cargo.toml").exists() -> "Cargo"
            extension == "py" -> "Python"; extension in listOf("c", "cpp", "cc", "cxx") -> "C / C++"
            extension in listOf("js", "mjs", "cjs") -> "Node.js"; extension == "ts" -> "TypeScript"
            extension == "rs" -> "Rust"; extension == "go" -> "Go"; extension == "java" -> "Java"
            extension in listOf("kt", "kts") -> "Kotlin"; extension == "sh" -> "Shell"
            File(workspace.directory, "package.json").exists() -> "npm build"
            File(workspace.directory, "CMakeLists.txt").exists() -> "CMake"
            else -> "自定义"
        } else profile
        return when (selected) {
            "Android APK" -> "if [ -z \"\$ASTER_AAPT2\" ] || [ ! -x \"\$ASTER_AAPT2\" ]; then echo '请先在设置中安装 Android 原生 SDK / NDK'; exit 1; fi; if [ -f ./gradlew ]; then chmod +x ./gradlew; ./gradlew --console=plain --no-daemon -Pandroid.builder.sdkDownload=false -Pandroid.aapt2FromMavenOverride=\"\$ASTER_AAPT2\" assembleDebug; else gradle --console=plain --no-daemon -Pandroid.builder.sdkDownload=false -Pandroid.aapt2FromMavenOverride=\"\$ASTER_AAPT2\" assembleDebug; fi"
            "Python" -> "python $file"
            "C / C++" -> "mkdir -p .aster-build && ${if (extension == "c") "clang -std=c17" else "clang++ -std=c++20"} -Wall -O2 $file -o .aster-build/app && ./.aster-build/app"
            "Node.js" -> "node $file"
            "TypeScript" -> "tsc --target es2020 --module commonjs --outDir .aster-build $file && node .aster-build/${quote(File(p).nameWithoutExtension + ".js")}" 
            "Rust" -> "mkdir -p .aster-build && rustc $file -o .aster-build/app && ./.aster-build/app"
            "Cargo" -> "cargo run --color always"
            "Go" -> "go run $file"
            "Java" -> "mkdir -p .aster-build && javac -encoding UTF-8 -d .aster-build $file && java -cp .aster-build $stem"
            "Kotlin" -> "mkdir -p .aster-build && kotlinc $file -include-runtime -d .aster-build/app.jar && java -jar .aster-build/app.jar"
            "Shell" -> "bash $file"
            "npm build" -> "npm run build"
            "CMake" -> "cmake -S . -B .aster-build -G Ninja && cmake --build .aster-build --parallel 2"
            else -> custom
        }
    }
}