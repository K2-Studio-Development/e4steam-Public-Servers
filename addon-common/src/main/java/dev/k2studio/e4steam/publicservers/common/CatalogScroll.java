package dev.k2studio.e4steam.publicservers.common;

/** Pure UI geometry; independent of discovery, selection and connection state. */
public final class CatalogScroll {
    private CatalogScroll() { }
    public static int maximum(int count, int visible) { return Math.max(0, count - Math.max(1, visible)); }
    public static int thumbHeight(int count, int visible, int height) {
        if (height <= 0) return 0;
        return Math.min(height, Math.max(12, (int) ((long) height * Math.max(1, visible) / Math.max(1, count))));
    }
    public static int thumbTop(int index, int count, int visible, int height) {
        int maximum = maximum(count, visible);
        if (maximum == 0) return 0;
        return (int) Math.round((double) Math.max(0, Math.min(index, maximum))
                * (height - thumbHeight(count, visible, height)) / maximum);
    }
    public static int indexAt(double thumbTop, int count, int visible, int height) {
        int travel = height - thumbHeight(count, visible, height);
        if (travel <= 0) return 0;
        double fraction = Math.max(0, Math.min(1, thumbTop / travel));
        return (int) Math.round(fraction * maximum(count, visible));
    }
}
