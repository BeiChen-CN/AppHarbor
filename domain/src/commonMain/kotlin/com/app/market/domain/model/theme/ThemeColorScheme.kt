package com.app.market.domain.model.theme

enum class ThemeColorMode(val key: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromKey(key: String?): ThemeColorMode = when (key) {
            LIGHT.key -> LIGHT
            DARK.key -> DARK
            else -> SYSTEM
        }
    }
}

enum class ThemeColorSource(val key: String) {
    DEFAULT("default"),
    MONET("monet"),
    CUSTOM("custom");

    companion object {
        fun fromKey(key: String?): ThemeColorSource = when (key) {
            MONET.key -> MONET
            CUSTOM.key -> CUSTOM
            else -> DEFAULT
        }
    }
}

enum class ThemePaletteStyle(val key: String) {
    TONAL_SPOT("tonal_spot"),
    NEUTRAL("spritz"),
    VIBRANT("vibrant"),
    EXPRESSIVE("expressive"),
    RAINBOW("rainbow"),
    FRUIT_SALAD("fruit_salad"),
    MONOCHROME("monochrome"),
    FIDELITY("fidelity"),
    CONTENT("content");

    /** Material 色彩算法只为这四种风格定义了 2025 规格，其余风格选 2021 / 2025 结果完全一致。 */
    val supportsSpec2025: Boolean
        get() = this == TONAL_SPOT || this == NEUTRAL || this == VIBRANT || this == EXPRESSIVE

    companion object {
        fun fromKey(key: String?): ThemePaletteStyle = entries.firstOrNull { it.key == key } ?: TONAL_SPOT
    }
}

enum class ThemeColorSpec(val key: String) {
    SPEC_2021("spec_2021"),
    SPEC_2025("spec_2025");

    companion object {
        fun fromKey(key: String?): ThemeColorSpec = when (key) {
            SPEC_2025.key -> SPEC_2025
            else -> SPEC_2021
        }
    }
}
