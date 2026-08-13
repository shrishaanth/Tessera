package com.geotracker.index;

import com.geotracker.model.BoundingBox;
import com.geotracker.model.NearestResult;

import java.util.*;

public class NaiveIndex implements SpatialIndex {
    private final List<Long> ids = new ArrayList<>();
    private final List<Double> xs = new ArrayList<>();
    private final List<Double> ys = new ArrayList<>();
    private final Map<Long, Integer> index = new HashMap<>();

    @Override
    public synchronized void insert(long vehicleId, double x, double y) {
        if (index.containsKey(vehicleId)) {
            update(vehicleId, xs.get(index.get(vehicleId)), ys.get(index.get(vehicleId)), x, y);
            return;
        }
        ids.add(vehicleId);
        xs.add(x);
        ys.add(y);
        index.put(vehicleId, ids.size() - 1);
    }

    @Override
    public synchronized void remove(long vehicleId, double x, double y) {
        Integer idx = index.remove(vehicleId);
        if (idx == null) return;
        int last = ids.size() - 1;
        if (idx != last) {
            ids.set(idx, ids.get(last));
            xs.set(idx, xs.get(last));
            ys.set(idx, ys.get(last));
            index.put(ids.get(idx), idx);
        }
        ids.remove(last);
        xs.remove(last);
        ys.remove(last);
    }

    @Override
    public synchronized void update(long vehicleId, double oldX, double oldY, double newX, double newY) {
        Integer idx = index.get(vehicleId);
        if (idx == null) {
            insert(vehicleId, newX, newY);
            return;
        }
        xs.set(idx, newX);
        ys.set(idx, newY);
    }

    @Override
    public synchronized java.util.List<Long> rangeQuery(BoundingBox bbox) {
        List<Long> result = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            double x = xs.get(i);
            double y = ys.get(i);
            if (bbox.contains(x, y)) {
                result.add(ids.get(i));
            }
        }
        return result;
    }

    @Override
    public synchronized NearestResult nearest(double x, double y) {
        if (ids.isEmpty()) return null;
        long bestId = -1;
        double bestX = 0;
        double bestY = 0;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < ids.size(); i++) {
            double dx = xs.get(i) - x;
            double dy = ys.get(i) - y;
            double dist = dx * dx + dy * dy;
            if (dist < bestDist) {
                bestDist = dist;
                bestId = ids.get(i);
                bestX = xs.get(i);
                bestY = ys.get(i);
            }
        }
        return new NearestResult(bestId, bestX, bestY, Math.sqrt(bestDist));
    }
}
