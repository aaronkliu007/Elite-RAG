package com.yunjihai.rag.retrieval.domain;

public record NormalizedBoundingBox(double left, double top, double right, double bottom) {

    public NormalizedBoundingBox {
        if (!inUnitInterval(left)
                || !inUnitInterval(top)
                || !inUnitInterval(right)
                || !inUnitInterval(bottom)
                || right <= left
                || bottom <= top) {
            throw new IllegalArgumentException("normalized bounding box is invalid");
        }
    }

    private static boolean inUnitInterval(double value) {
        return Double.isFinite(value) && value >= 0 && value <= 1;
    }
}
