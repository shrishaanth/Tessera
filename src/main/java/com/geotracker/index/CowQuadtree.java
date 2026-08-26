package com.geotracker.index;

import com.geotracker.model.BoundingBox;
import com.geotracker.model.NearestResult;

import java.util.ArrayList;
import java.util.List;

public class CowQuadtree implements SpatialIndex {
    private static final int MAX_CAPACITY = 32;
    private static final int MAX_DEPTH = 20;

    private volatile QuadTreeNode publishedRoot;
    private QuadTreeNode workingRoot;

    public CowQuadtree(BoundingBox bounds) {
        this.workingRoot = QuadTreeNode.Leaf.empty(bounds);
        this.publishedRoot = this.workingRoot;
    }

    CowQuadtree(QuadTreeNode publishedRoot) {
        this.workingRoot = publishedRoot;
        this.publishedRoot = publishedRoot;
    }

    @Override
    public synchronized void insert(long vehicleId, double x, double y) {
        if (!workingRoot.bounds().contains(x, y)) {
            return;
        }
        workingRoot = insert(workingRoot, vehicleId, x, y, 0);
    }

    @Override
    public synchronized void remove(long vehicleId, double x, double y) {
        if (!workingRoot.bounds().contains(x, y)) {
            return;
        }
        workingRoot = remove(workingRoot, vehicleId, x, y, 0);
    }

    @Override
    public synchronized void update(long vehicleId, double oldX, double oldY, double newX, double newY) {
        if (workingRoot.bounds().contains(oldX, oldY)) {
            workingRoot = remove(workingRoot, vehicleId, oldX, oldY, 0);
        }
        if (workingRoot.bounds().contains(newX, newY)) {
            workingRoot = insert(workingRoot, vehicleId, newX, newY, 0);
        }
    }

    @Override
    public List<Long> rangeQuery(BoundingBox bbox) {
        QuadTreeNode root = publishedRoot;
        if (root == null) {
            return List.of();
        }
        List<Long> result = new ArrayList<>();
        rangeQuery(root, bbox, result);
        return result;
    }

    @Override
    public NearestResult nearest(double x, double y) {
        QuadTreeNode root = publishedRoot;
        if (root == null) {
            return null;
        }
        return nearest(root, x, y);
    }

    public synchronized void publish() {
        publishedRoot = deepCopy(workingRoot);
    }

    public CowQuadtree snapshot() {
        return new CowQuadtree(publishedRoot);
    }

    private QuadTreeNode deepCopy(QuadTreeNode node) {
        if (node instanceof QuadTreeNode.Leaf leaf) {
            long[] ids = java.util.Arrays.copyOf(leaf.vehicleIds(), leaf.size());
            double[] xs = java.util.Arrays.copyOf(leaf.xs(), leaf.size());
            double[] ys = java.util.Arrays.copyOf(leaf.ys(), leaf.size());
            return new QuadTreeNode.Leaf(leaf.bounds(), ids, xs, ys, leaf.size());
        }
        QuadTreeNode.Branch branch = (QuadTreeNode.Branch) node;
        return new QuadTreeNode.Branch(
            branch.bounds(),
            deepCopy(branch.nw()),
            deepCopy(branch.ne()),
            deepCopy(branch.sw()),
            deepCopy(branch.se())
        );
    }

    private QuadTreeNode insert(QuadTreeNode node, long vehicleId, double x, double y, int depth) {
        if (node instanceof QuadTreeNode.Leaf leaf) {
            if (leaf.size() < MAX_CAPACITY || depth >= MAX_DEPTH) {
                return leaf.add(vehicleId, x, y);
            }
            boolean allSameCoords = true;
            for (int i = 0; i < leaf.size(); i++) {
                if (leaf.xs()[i] != x || leaf.ys()[i] != y) {
                    allSameCoords = false;
                    break;
                }
            }
            if (allSameCoords) {
                return leaf.add(vehicleId, x, y);
            }
            QuadTreeNode branch = split(leaf);
            return insert(branch, vehicleId, x, y, depth);
        }
        QuadTreeNode.Branch branch = (QuadTreeNode.Branch) node;
        int quadrant = getQuadrant(branch.bounds(), x, y);
        return switch (quadrant) {
            case 0 -> new QuadTreeNode.Branch(branch.bounds(), insert(branch.nw(), vehicleId, x, y, depth + 1), branch.ne(), branch.sw(), branch.se());
            case 1 -> new QuadTreeNode.Branch(branch.bounds(), branch.nw(), insert(branch.ne(), vehicleId, x, y, depth + 1), branch.sw(), branch.se());
            case 2 -> new QuadTreeNode.Branch(branch.bounds(), branch.nw(), branch.ne(), insert(branch.sw(), vehicleId, x, y, depth + 1), branch.se());
            case 3 -> new QuadTreeNode.Branch(branch.bounds(), branch.nw(), branch.ne(), branch.sw(), insert(branch.se(), vehicleId, x, y, depth + 1));
            default -> throw new IllegalStateException("Invalid quadrant: " + quadrant);
        };
    }

    private QuadTreeNode remove(QuadTreeNode node, long vehicleId, double x, double y, int depth) {
        if (node instanceof QuadTreeNode.Leaf leaf) {
            return leaf.remove(vehicleId, x, y);
        }
        QuadTreeNode.Branch branch = (QuadTreeNode.Branch) node;
        int quadrant = getQuadrant(branch.bounds(), x, y);
        return switch (quadrant) {
            case 0 -> new QuadTreeNode.Branch(branch.bounds(), remove(branch.nw(), vehicleId, x, y, depth + 1), branch.ne(), branch.sw(), branch.se());
            case 1 -> new QuadTreeNode.Branch(branch.bounds(), branch.nw(), remove(branch.ne(), vehicleId, x, y, depth + 1), branch.sw(), branch.se());
            case 2 -> new QuadTreeNode.Branch(branch.bounds(), branch.nw(), branch.ne(), remove(branch.sw(), vehicleId, x, y, depth + 1), branch.se());
            case 3 -> new QuadTreeNode.Branch(branch.bounds(), branch.nw(), branch.ne(), branch.sw(), remove(branch.se(), vehicleId, x, y, depth + 1));
            default -> throw new IllegalStateException("Invalid quadrant: " + quadrant);
        };
    }

    private QuadTreeNode split(QuadTreeNode.Leaf leaf) {
        BoundingBox bounds = leaf.bounds();
        double midX = (bounds.minX() + bounds.maxX()) / 2.0;
        double midY = (bounds.minY() + bounds.maxY()) / 2.0;

        QuadTreeNode nw = QuadTreeNode.Leaf.empty(new BoundingBox(bounds.minX(), midY, midX, bounds.maxY()));
        QuadTreeNode ne = QuadTreeNode.Leaf.empty(new BoundingBox(midX, midY, bounds.maxX(), bounds.maxY()));
        QuadTreeNode sw = QuadTreeNode.Leaf.empty(new BoundingBox(bounds.minX(), bounds.minY(), midX, midY));
        QuadTreeNode se = QuadTreeNode.Leaf.empty(new BoundingBox(midX, bounds.minY(), bounds.maxX(), midY));

        QuadTreeNode.Branch branch = (QuadTreeNode.Branch) new QuadTreeNode.Branch(bounds, nw, ne, sw, se);
        for (int i = 0; i < leaf.size(); i++) {
            int quadrant = getQuadrant(bounds, leaf.xs()[i], leaf.ys()[i]);
            branch = switch (quadrant) {
                case 0 -> new QuadTreeNode.Branch(bounds, insert(branch.nw(), leaf.vehicleIds()[i], leaf.xs()[i], leaf.ys()[i], 1), branch.ne(), branch.sw(), branch.se());
                case 1 -> new QuadTreeNode.Branch(bounds, branch.nw(), insert(branch.ne(), leaf.vehicleIds()[i], leaf.xs()[i], leaf.ys()[i], 1), branch.sw(), branch.se());
                case 2 -> new QuadTreeNode.Branch(bounds, branch.nw(), branch.ne(), insert(branch.sw(), leaf.vehicleIds()[i], leaf.xs()[i], leaf.ys()[i], 1), branch.se());
                case 3 -> new QuadTreeNode.Branch(bounds, branch.nw(), branch.ne(), branch.sw(), insert(branch.se(), leaf.vehicleIds()[i], leaf.xs()[i], leaf.ys()[i], 1));
                default -> throw new IllegalStateException("Invalid quadrant: " + quadrant);
            };
        }
        return branch;
    }

    private int getQuadrant(BoundingBox bounds, double x, double y) {
        double midX = (bounds.minX() + bounds.maxX()) / 2.0;
        double midY = (bounds.minY() + bounds.maxY()) / 2.0;
        if (x < midX) {
            return y < midY ? 2 : 0;
        } else {
            return y < midY ? 3 : 1;
        }
    }

    private void rangeQuery(QuadTreeNode node, BoundingBox bbox, List<Long> result) {
        if (!node.bounds().intersects(bbox)) {
            return;
        }
        if (node instanceof QuadTreeNode.Leaf leaf) {
            for (int i = 0; i < leaf.size(); i++) {
                if (bbox.contains(leaf.xs()[i], leaf.ys()[i])) {
                    result.add(leaf.vehicleIds()[i]);
                }
            }
        } else if (node instanceof QuadTreeNode.Branch branch) {
            rangeQuery(branch.nw(), bbox, result);
            rangeQuery(branch.ne(), bbox, result);
            rangeQuery(branch.sw(), bbox, result);
            rangeQuery(branch.se(), bbox, result);
        }
    }

    private NearestResult nearest(QuadTreeNode node, double x, double y) {
        if (node instanceof QuadTreeNode.Leaf leaf) {
            long bestId = -1;
            double bestX = 0;
            double bestY = 0;
            double bestDist = Double.MAX_VALUE;
            for (int i = 0; i < leaf.size(); i++) {
                double dx = leaf.xs()[i] - x;
                double dy = leaf.ys()[i] - y;
                double dist = dx * dx + dy * dy;
                if (dist < bestDist) {
                    bestDist = dist;
                    bestId = leaf.vehicleIds()[i];
                    bestX = leaf.xs()[i];
                    bestY = leaf.ys()[i];
                }
            }
            return new NearestResult(bestId, bestX, bestY, Math.sqrt(bestDist));
        }
        QuadTreeNode.Branch branch = (QuadTreeNode.Branch) node;
        int quadrant = getQuadrant(branch.bounds(), x, y);
        NearestResult best = nearest(branch.getChild(quadrant), x, y);
        double bestDist = best == null ? Double.MAX_VALUE : best.distance();
        double midX = (branch.bounds().minX() + branch.bounds().maxX()) / 2.0;
        double midY = (branch.bounds().minY() + branch.bounds().maxY()) / 2.0;
        double dx = x < midX ? midX - x : x - midX;
        double dy = y < midY ? midY - y : y - midY;
        if (dx < bestDist) {
            if (quadrant == 0 || quadrant == 2) {
                NearestResult candidate = nearest(branch.ne(), x, y);
                if (candidate != null && candidate.distance() < bestDist) best = candidate;
                candidate = nearest(branch.se(), x, y);
                if (candidate != null && candidate.distance() < bestDist) best = candidate;
            } else {
                NearestResult candidate = nearest(branch.nw(), x, y);
                if (candidate != null && candidate.distance() < bestDist) best = candidate;
                candidate = nearest(branch.sw(), x, y);
                if (candidate != null && candidate.distance() < bestDist) best = candidate;
            }
        }
        if (dy < bestDist) {
            if (quadrant == 0 || quadrant == 1) {
                NearestResult candidate = nearest(branch.sw(), x, y);
                if (candidate != null && candidate.distance() < bestDist) best = candidate;
                candidate = nearest(branch.se(), x, y);
                if (candidate != null && candidate.distance() < bestDist) best = candidate;
            } else {
                NearestResult candidate = nearest(branch.nw(), x, y);
                if (candidate != null && candidate.distance() < bestDist) best = candidate;
                candidate = nearest(branch.ne(), x, y);
                if (candidate != null && candidate.distance() < bestDist) best = candidate;
            }
        }
        return best;
    }
}
