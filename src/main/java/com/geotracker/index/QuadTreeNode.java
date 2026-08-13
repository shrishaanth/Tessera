package com.geotracker.index;

import com.geotracker.model.BoundingBox;

public sealed interface QuadTreeNode {
    BoundingBox bounds();

    record Leaf(BoundingBox bounds, long[] vehicleIds, double[] xs, double[] ys, int size) implements QuadTreeNode {
        public static Leaf empty(BoundingBox bounds) {
            return new Leaf(bounds, new long[32], new double[32], new double[32], 0);
        }

        public Leaf add(long vehicleId, double x, double y) {
            if (size < vehicleIds.length) {
                long[] newIds = java.util.Arrays.copyOf(vehicleIds, vehicleIds.length);
                double[] newXs = java.util.Arrays.copyOf(xs, xs.length);
                double[] newYs = java.util.Arrays.copyOf(ys, ys.length);
                newIds[size] = vehicleId;
                newXs[size] = x;
                newYs[size] = y;
                return new Leaf(bounds, newIds, newXs, newYs, size + 1);
            }
            throw new IllegalStateException("Leaf capacity exceeded");
        }

        public Leaf remove(long vehicleId, double x, double y) {
            int idx = -1;
            for (int i = 0; i < size; i++) {
                if (vehicleIds[i] == vehicleId) {
                    idx = i;
                    break;
                }
            }
            if (idx == -1) {
                return this;
            }
            if (size == 1) {
                return empty(bounds);
            }
            long[] newIds = new long[vehicleIds.length];
            double[] newXs = new double[xs.length];
            double[] newYs = new double[ys.length];
            System.arraycopy(vehicleIds, 0, newIds, 0, idx);
            System.arraycopy(xs, 0, newXs, 0, idx);
            System.arraycopy(ys, 0, newYs, 0, idx);
            System.arraycopy(vehicleIds, idx + 1, newIds, idx, size - idx - 1);
            System.arraycopy(xs, idx + 1, newXs, idx, size - idx - 1);
            System.arraycopy(ys, idx + 1, newYs, idx, size - idx - 1);
            return new Leaf(bounds, newIds, newXs, newYs, size - 1);
        }
    }

    record Branch(BoundingBox bounds, QuadTreeNode nw, QuadTreeNode ne, QuadTreeNode sw, QuadTreeNode se) implements QuadTreeNode {
        public QuadTreeNode getChild(int index) {
            return switch (index) {
                case 0 -> nw;
                case 1 -> ne;
                case 2 -> sw;
                case 3 -> se;
                default -> throw new IllegalArgumentException("Invalid quadrant: " + index);
            };
        }
    }
}
