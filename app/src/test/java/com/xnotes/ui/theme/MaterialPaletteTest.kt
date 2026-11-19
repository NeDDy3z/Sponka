package com.xnotes.ui.theme

import com.xnotes.core.model.Rgba
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaterialPaletteTest {

    private val seed = Rgba(0, 230, 118) // the default green accent

    private fun luminance(c: Rgba): Double =
        (0.299 * c.r + 0.587 * c.g + 0.114 * c.b) / 255.0

    @Test fun materialAppearanceMapping() {
        val dark = MaterialColors.seeded(seed, dark = true)
        val light = MaterialColors.seeded(seed, dark = false)
        assertTrue(Palette.material("dark", dark).isDark)
        assertTrue(Palette.material("oled", dark).isDark)
        assertFalse(Palette.material("light", light).isDark)
    }

    @Test fun seededLightSurfacesStepDownFromWhitePaper() {
        val m = MaterialColors.seeded(seed, dark = false)
        assertEquals(Rgba(255, 255, 255, 255), m.surfaceContainerLowest)
        val p = Palette.materialLight(m)
        assertEquals(m.surfaceContainerLowest, p.paper)
        assertTrue(luminance(p.paper) > luminance(p.menuBg))
        assertTrue(luminance(p.menuBg) > luminance(p.panel))
        assertTrue(luminance(p.panel) > luminance(p.bg))
        assertTrue(luminance(p.bg) > luminance(p.surface))
        assertTrue(luminance(p.surface) > luminance(p.surfaceHi))
        assertTrue(luminance(p.text) < 0.3)
        // The backstage background must sit visibly below white paper, not wash out.
        assertTrue(luminance(p.menuBg) < 0.95)
    }

    @Test fun seededDarkSurfacesStayDarkWithPastelPrimary() {
        val m = MaterialColors.seeded(seed, dark = true)
        val p = Palette.materialDark(m)
        assertEquals(m.surfaceContainerLow, p.paper)
        assertTrue(luminance(p.bg) < 0.1)
        assertTrue(luminance(p.paper) < 0.2)
        assertTrue(luminance(p.accent) > 0.5)
        assertTrue(luminance(p.text) > 0.7)
    }

    @Test fun materialOledBigSurfacesArePitchBlack() {
        val p = Palette.materialOled(MaterialColors.seeded(seed, dark = true))
        val black = Rgba(0, 0, 0, 255)
        assertEquals(black, p.bg)
        assertEquals(black, p.panel)
        assertEquals(black, p.paper)
        assertEquals(black, p.menuBg)
        assertTrue(p.isDark)
        assertTrue(luminance(p.surface) > 0.0)
    }

    @Test fun materialBordersSitCloserToTheChromeThanOutlineVariant() {
        val light = MaterialColors.seeded(seed, dark = false)
        val lp = Palette.materialLight(light)
        assertTrue(
            kotlin.math.abs(luminance(lp.border) - luminance(lp.menuBg)) <
                kotlin.math.abs(luminance(light.outlineVariant) - luminance(lp.menuBg)),
        )
        val dark = MaterialColors.seeded(seed, dark = true)
        val dp = Palette.materialDark(dark)
        assertTrue(
            kotlin.math.abs(luminance(dp.border) - luminance(dp.bg)) <
                kotlin.math.abs(luminance(dark.outlineVariant) - luminance(dp.bg)),
        )
    }

    @Test fun seededIsDeterministic() {
        assertEquals(MaterialColors.seeded(seed, true), MaterialColors.seeded(seed, true))
        assertEquals(MaterialColors.seeded(seed, false), MaterialColors.seeded(seed, false))
    }

    @Test fun backgroundBrightnessShiftsChromeButKeepsPaper() {
        val m = MaterialColors.seeded(seed, dark = true)
        val base = Palette.material("dark", m)
        val brighter = Palette.material("dark", m, 1.0)
        val darker = Palette.material("dark", m, -1.0)
        assertTrue(luminance(brighter.bg) > luminance(base.bg))
        assertTrue(luminance(darker.panel) < luminance(base.panel))
        assertEquals(base.paper, brighter.paper)
        assertEquals(base, Palette.material("dark", m, 0.0))
        // The lighter sidebar moves further than the darker background behind the pages.
        fun value(c: Rgba) = ColorMath.rgbToHsv(c)[2]
        assertTrue(value(brighter.panel) - value(base.panel) > value(brighter.bg) - value(base.bg))
        // Darkening a light theme keeps the background apart from the buttons sat on it.
        val light = MaterialColors.seeded(seed, dark = false)
        val lightBase = Palette.material("light", light)
        val lightDarker = Palette.material("light", light, -0.6)
        assertTrue(luminance(lightDarker.bg) - luminance(lightDarker.surface) >= luminance(lightBase.bg) - luminance(lightBase.surface))
        // Lifting tints the chrome with the theme's hue instead of washing it toward grey.
        assertTrue(ColorMath.rgbToHsv(Palette.material("dark", m, 0.8).bg)[1] > ColorMath.rgbToHsv(base.bg)[1])
        for (level in listOf(-0.6, 0.4, 0.8)) {
            assertEquals(Palette.material("oled", m), Palette.material("oled", m, level))
        }
    }

    @Test fun backgroundPresetsKeepTextReadable() {
        val seeds = listOf(seed, Rgba(245, 186, 149), Rgba(255, 107, 154), Rgba(33, 150, 243), Rgba(255, 235, 59), Rgba(120, 120, 120))
        for (sd in seeds) for (dark in listOf(true, false)) for (level in listOf(-0.6, 0.4, 0.8)) for (style in com.xnotes.settings.MaterialStyle.entries) {
            val m = MaterialColors.seeded(sd, dark, style)
            val base = Palette.material(if (dark) "dark" else "light", m)
            val p = Palette.material(if (dark) "dark" else "light", m, level)
            val pairs = listOf(base.bg to p.bg, base.panel to p.panel, base.menuBg to p.menuBg, base.surface to p.surface, base.surfaceHi to p.surfaceHi)
            for ((before, after) in pairs) {
                val floor = minOf(4.5, ColorMath.contrastRatio(base.textDim, before))
                val ratio = ColorMath.contrastRatio(p.textDim, after)
                assertTrue("textDim on ${Rgba.toHex(after)} is ${"%.2f".format(ratio)} ($sd dark=$dark $level $style)", ratio >= floor)
            }
        }
    }
}
