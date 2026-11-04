package dev.aster.ide.runtime

/** Keep actual PRoot failures visible; only routine diagnostics go to raw logs. */
object RuntimeDiagnostics {
    private val diagnostic = Regex("^(?:proot|\\S*libaster_proot\\.so) (?:warning|info):", RegexOption.IGNORE_CASE)
    private val expiredTemporary = Regex("^(?:proot|\\S*libaster_proot\\.so) error: can(?:'t|t) remove '[^']*/proot-[^']+': No such file or directory$", RegexOption.IGNORE_CASE)
    fun isRoutine(line: String): Boolean = diagnostic.containsMatchIn(line.trim()) || expiredTemporary.matches(line.trim())
    private val linkerConfiguration = Regex("""^WARNING: linker: Warning: failed to find generated linker configuration from ["']/linkerconfig/ld\.config\.txt["']$""")
    fun isIgnored(line: String): Boolean = isRoutine(line) || linkerConfiguration.matches(line.trim())
}