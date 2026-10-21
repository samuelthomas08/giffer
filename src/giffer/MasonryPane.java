package giffer;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.layout.Pane;

/** Pinterest-style columns: each child goes into the currently shortest column, its height follows the GIF's aspect. */
final class MasonryPane extends Pane {

    /** Children implementing this decide their own height for a given column width. */
    interface HeightForWidth {
        double heightForWidth(double columnWidth);
    }

    private static final double GAP = 12;
    private static final double MIN_COLUMN_WIDTH = 220;
    private static final double FALLBACK_WIDTH = 900;

    private static int columnCountFor(double availableWidth) {
        return Math.max(1, (int) ((availableWidth + GAP) / (MIN_COLUMN_WIDTH + GAP)));
    }

    private static int indexOfShortestColumn(double[] columnHeights) {
        int shortest = 0;
        for (int column = 1; column < columnHeights.length; column++) {
            if (columnHeights[column] < columnHeights[shortest] - 0.5) shortest = column;
        }
        return shortest;
    }

    private static double childHeight(Node child, double columnWidth) {
        return child instanceof HeightForWidth sizedChild ? sizedChild.heightForWidth(columnWidth) : child.prefHeight(columnWidth);
    }

    @Override
    protected void layoutChildren() {
        Insets insets = getInsets();
        double availableWidth = getWidth() - insets.getLeft() - insets.getRight();
        if (availableWidth <= 0) return;
        int columnCount = columnCountFor(availableWidth);
        double columnWidth = (availableWidth - GAP * (columnCount - 1)) / columnCount;
        double[] columnHeights = new double[columnCount];
        for (Node child : getManagedChildren()) {
            int column = indexOfShortestColumn(columnHeights);
            double height = childHeight(child, columnWidth);
            double left = insets.getLeft() + column * (columnWidth + GAP);
            double top = insets.getTop() + columnHeights[column];
            child.resizeRelocate(left, top, columnWidth, height);
            columnHeights[column] += height + GAP;
        }
    }

    @Override
    protected double computePrefHeight(double width) {
        Insets insets = getInsets();
        double contentWidth = width > 0 ? width : getWidth() > 0 ? getWidth() : FALLBACK_WIDTH;
        double availableWidth = contentWidth - insets.getLeft() - insets.getRight();
        int columnCount = columnCountFor(availableWidth);
        double columnWidth = (availableWidth - GAP * (columnCount - 1)) / columnCount;
        double[] columnHeights = new double[columnCount];
        for (Node child : getManagedChildren()) {
            columnHeights[indexOfShortestColumn(columnHeights)] += childHeight(child, columnWidth) + GAP;
        }
        double tallestColumn = 0;
        for (double columnHeight : columnHeights) tallestColumn = Math.max(tallestColumn, columnHeight);
        return insets.getTop() + insets.getBottom() + Math.max(0, tallestColumn - GAP);
    }

    @Override
    protected double computeMinHeight(double width) {
        return 0;
    }
}
