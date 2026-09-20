package com.zhinibgdu.xianyu;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class FruitTemplateCatalogTest {

    @Test
    public void allInitialTemplatesDecodeToSevenBySevenRgb() {
        assertEquals(15, FruitTemplateCatalog.IDS.length);
        assertEquals(15, FruitTemplateCatalog.GRIDS.length);

        for (float[] grid : FruitTemplateCatalog.GRIDS) {
            assertEquals(7 * 7 * 3, grid.length);
            for (float v : grid) {
                assertTrue(v >= 0.0f && v <= 1.0f);
            }
        }
    }
}
