package cn.ianzb.hyperrefine.hook.systemui.statusbar

import org.junit.Assert.*
import org.junit.Test

class DualSimArtworkPolicyTest {
    @Test fun sixNativeStrengthLevelsRemainMonotonicWithinFourCells() {
        assertEquals(listOf(0, 1, 2, 2, 3, 4), (0..5).map(DualSimArtworkPolicy::fourCellLevel))
        assertNull(DualSimArtworkPolicy.fourCellLevel(null))
        assertNull(DualSimArtworkPolicy.fourCellLevel(-1))
        assertNull(DualSimArtworkPolicy.fourCellLevel(6))
    }

    @Test fun dataSimSwitchDoesNotSwapTheDisplayedPhysicalSimRows() {
        val fromSim1 = DualSimArtworkPolicy.levels(0, 1, 5, 2)
        val fromSim2 = DualSimArtworkPolicy.levels(1, 0, 2, 5)
        assertEquals(DualSimArtworkPolicy.Levels(4, 2), fromSim1)
        assertEquals(fromSim1, fromSim2)
    }

    @Test fun duplicateOrUnknownSlotsAndUnknownStrengthKeepNativeLayout() {
        assertNull(DualSimArtworkPolicy.levels(0, 0, 3, 4))
        assertNull(DualSimArtworkPolicy.levels(-1, 1, 3, 4))
        assertNull(DualSimArtworkPolicy.levels(0, 2, 3, 4))
        assertNull(DualSimArtworkPolicy.levels(0, 1, null, 4))
    }

    @Test fun normalArtworkHasFourAlignedCellsPerRowInsideTheDrawable() {
        for ((width, height) in listOf(16f to 20f, 32f to 28f, 8f to 8f)) {
            val cells = DualSimArtworkPolicy.cells(width, height)
            assertEquals(8, cells.size)
            assertEquals(listOf(0, 1, 2, 3), cells.filter { it.row == 0 }.map { it.column })
            cells.forEach { assertTrue(it.left >= 0 && it.top >= -0.0001f && it.right <= width && it.bottom <= height) }
            cells.take(4).zip(cells.drop(4)).forEach { (upper, lower) ->
                assertEquals(upper.left, lower.left, 0f)
                assertEquals(upper.right, lower.right, 0f)
                assertTrue(upper.bottom < lower.top)
            }
        }
    }

    @Test fun upperRowRisesAndLowerRowHasEqualShortCells() {
        val cells = DualSimArtworkPolicy.cells(16f, 20f)
        val upper = cells.take(4)
        val lower = cells.drop(4)
        assertTrue(upper.zipWithNext().all { (a, b) -> a.top > b.top })
        assertEquals(1, upper.map { it.bottom }.distinct().size)
        assertEquals(1, lower.map { it.top }.distinct().size)
        assertEquals(1, lower.map { it.bottom }.distinct().size)
        assertTrue(lower.first().bottom - lower.first().top < upper.first().bottom - upper.first().top)
        // Both rows fit a compact silhouette rather than filling the host's tall drawable bounds.
        assertTrue(lower.last().bottom - upper.last().top < 16f)
        assertTrue(lower.first().top - upper.first().bottom > 1.5f)
        cells.forEach {
            val shortSide = minOf(it.right - it.left, it.bottom - it.top)
            assertTrue(it.radius > shortSide * 0.4f)
            assertTrue(it.radius <= shortSide / 2f)
        }
    }

    @Test fun invalidBoundsDoNotProduceInvalidCoordinates() {
        assertTrue(DualSimArtworkPolicy.cells(0f, 20f).isEmpty())
        assertTrue(DualSimArtworkPolicy.cells(16f, -1f).isEmpty())
        assertTrue(DualSimArtworkPolicy.cells(Float.NaN, 20f).isEmpty())
        assertTrue(DualSimArtworkPolicy.cells(16f, Float.POSITIVE_INFINITY).isEmpty())
    }

    @Test fun canonicalViewportIsCentredWithClearRowGap() {
        val cells = DualSimArtworkPolicy.cells(16f, 20f)
        assertEquals(0.72f, cells.first().left, 0.00001f)
        assertEquals(2.56f, cells.first().right - cells.first().left, 0.00001f)
        assertEquals(3.6f, cells[3].top, 0.00001f)
        assertEquals(13.8912f, cells[4].top, 0.00001f)
        assertEquals(16.4f, cells.last().bottom, 0.00001f)
        assertEquals(10f, (cells[3].top + cells.last().bottom) / 2f, 0.00001f)
        assertEquals(2.048f, cells[4].top - cells[0].bottom, 0.00001f)
    }

    @Test fun aspectChangesScaleUniformlyWithoutMorphingTheBarsOrCorners() {
        val template = DualSimArtworkPolicy.cells(16f, 20f)
        for ((width, height) in listOf(32f to 28f, 20f to 20f, 16f to 48f, 64f to 16f)) {
            val scale = minOf(width / 16f, height / 20f)
            val cells = DualSimArtworkPolicy.cells(width, height)
            template.zip(cells).forEach { (before, after) ->
                assertEquals((before.right - before.left) * scale, after.right - after.left, 0.00001f)
                assertEquals((before.bottom - before.top) * scale, after.bottom - after.top, 0.00001f)
                assertEquals(before.radius * scale, after.radius, 0.00001f)
            }
            assertEquals(height / 2f, (cells[3].top + cells.last().bottom) / 2f, 0.00001f)
            val offsetY = (height - 20f * scale) / 2f
            assertEquals(offsetY + 16.4f * scale, cells.last().bottom, 0.00001f)
        }
    }

    @Test fun whiteAndBlackHostPalettesRetainRgbAndDimOnlyInactiveCells() {
        for (color in listOf(0xffffffff.toInt(), 0xff000000.toInt(), 0xff75aaff.toInt())) {
            assertEquals(color, DualSimArtworkPolicy.cellColor(color, 255, true))
            val inactive = DualSimArtworkPolicy.cellColor(color, 255, false)
            assertEquals(color and 0x00ffffff, inactive and 0x00ffffff)
            assertEquals(71, inactive ushr 24)
        }
    }

    @Test fun hostTransparencyAndDrawableFadeMultiplyForBothSignalStates() {
        val color = 0x80ffffff.toInt()
        assertEquals(64, DualSimArtworkPolicy.cellColor(color, 128, true) ushr 24)
        assertEquals(18, DualSimArtworkPolicy.cellColor(color, 128, false) ushr 24)
        assertEquals(0, DualSimArtworkPolicy.cellColor(color, 0, true) ushr 24)
        assertEquals(128, DualSimArtworkPolicy.cellColor(color, 999, true) ushr 24)
    }
}
