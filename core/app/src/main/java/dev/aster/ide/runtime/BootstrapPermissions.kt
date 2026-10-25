package dev.aster.ide.runtime

/** ZIP streams omit Unix mode bits; executable payloads must also be recognized by content. */
object BootstrapPermissions {
    fun executable(name: String, header: ByteArray): Boolean =
        name.startsWith("bin/") || name.startsWith("libexec/") ||
            name.endsWith(".so") || ".so." in name ||
            (header.size >= 4 && header[0] == 0x7f.toByte() && header[1] == 'E'.code.toByte() &&
                header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte()) ||
            (header.size >= 2 && header[0] == '#'.code.toByte() && header[1] == '!'.code.toByte())
}
