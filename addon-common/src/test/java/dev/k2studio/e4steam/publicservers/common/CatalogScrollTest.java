package dev.k2studio.e4steam.publicservers.common;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CatalogScrollTest {
    @Test void emptyAndShortListsNeverScroll() {
        for (int count = 0; count <= 8; count++) {
            assertEquals(0, CatalogScroll.maximum(count, 8));
            assertEquals(0, CatalogScroll.indexAt(999, count, 8, 176));
        }
    }
    @Test void draggingBeyondTheTrackClampsToBothEnds() {
        assertEquals(0, CatalogScroll.indexAt(-100, 100, 8, 176));
        assertEquals(92, CatalogScroll.indexAt(1000, 100, 8, 176));
    }
    @Test void thumbAlwaysFitsEvenOnTinyScreens() {
        for (int height = 0; height < 200; height++) {
            int thumb = CatalogScroll.thumbHeight(100, 8, height);
            assertTrue(thumb >= 0 && thumb <= height);
            assertTrue(CatalogScroll.thumbTop(92, 100, 8, height) + thumb <= height);
        }
    }
    @Test void longListsHaveMonotonicBoundedIndices() {
        int last = 0;
        for (int y = -100; y < 500; y++) {
            int index = CatalogScroll.indexAt(y, Integer.MAX_VALUE, 8, 176);
            assertTrue(index >= last && index <= Integer.MAX_VALUE - 8);
            last = index;
        }
    }
    @Test void filteringAndResizingClampExistingPosition() {
        assertEquals(2, Math.min(92, CatalogScroll.maximum(10, 8)));
        assertEquals(0, Math.min(92, CatalogScroll.maximum(10, 12)));
    }
}
