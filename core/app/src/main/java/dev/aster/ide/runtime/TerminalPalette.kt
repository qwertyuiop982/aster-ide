package dev.aster.ide.runtime

/** UI palette for the existing embedded terminal. ANSI colours retain their meaning. */
data class TerminalPalette(val foreground: Int, val background: Int, val cursor: Int, val ansi: List<Int>) {
    companion object {
        private val classic = listOf(0xFF000000,0xFFCD0000,0xFF00CD00,0xFFCDCD00,0xFF6495ED,0xFFCD00CD,0xFF00CDCD,0xFFE5E5E5,
            0xFF7F7F7F,0xFFFF0000,0xFF00FF00,0xFFFFFF00,0xFF5C5CFF,0xFFFF00FF,0xFF00FFFF,0xFFFFFFFF).map { it.toInt() }
        private val day = listOf(0xFF171717,0xFFA52A2A,0xFF126C31,0xFF805400,0xFF1C57A0,0xFF713D8A,0xFF006B74,0xFF747474,
            0xFF555555,0xFFC42D2D,0xFF15793C,0xFF936000,0xFF245FC0,0xFF87479D,0xFF007C86,0xFF242424).map { it.toInt() }
        fun forStyle(acs: Boolean, dark: Boolean): TerminalPalette = when {
            !acs -> TerminalPalette(0xFFDDE3F0.toInt(),0xFF11141D.toInt(),0xFFFFFFFF.toInt(),classic)
            dark -> TerminalPalette(0xFFF5F5F5.toInt(),0xFF000000.toInt(),0xFFFFFFFF.toInt(),classic)
            else -> TerminalPalette(0xFF171717.toInt(),0xFFFFFFFF.toInt(),0xFF000000.toInt(),day)
        }
    }
}
