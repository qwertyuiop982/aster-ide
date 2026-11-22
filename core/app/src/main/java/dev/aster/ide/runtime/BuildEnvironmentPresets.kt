package dev.aster.ide.runtime

object BuildEnvironmentPresets {
    data class Preset(val id: String, val title: String, val packages: String, val executables: List<String>)

    val entries = listOf(
        Preset("env-python", "Python", "python python-pip", listOf("python", "pip")),
        Preset("env-node", "Node.js / TypeScript", "nodejs-lts、npm 上的 typescript", listOf("node", "npm", "tsc")),
        Preset("env-cpp", "C / C++", "clang make cmake ninja pkg-config", listOf("clang", "clang++", "make", "cmake", "ninja", "pkg-config")),
        Preset("env-rust", "Rust / Cargo", "rust", listOf("rustc", "cargo")),
        Preset("env-go", "Go", "golang", listOf("go")),
        Preset("env-jvm", "Java / Kotlin", "openjdk（所选版本） kotlin", listOf("java", "javac", "kotlinc")),
        Preset("env-cli", "常用命令行工具", "git ripgrep fd jq tree zip unzip", listOf("git", "rg", "fd", "jq", "tree", "zip", "unzip"))
    )

    fun get(id: String): Preset? = entries.firstOrNull { it.id == id }
}
