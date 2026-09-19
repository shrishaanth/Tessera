package com.geotracker.index;

import com.geotracker.model.BoundingBox;
import com.geotracker.model.NearestResult;

import java.util.ArrayList;
import java.util.List;

public interface SpatialIndex {
    void insert(long vehicleId, double x, double y);
    void remove(long vehicleId, double x, double y);
    default void update(long vehicleId, double oldX, double oldY, double newX, double newY) {
        remove(vehicleId, oldX, oldY);
        insert(vehicleId, newX, newY);
    }
    List<Long> rangeQuery(BoundingBox bbox);
    NearestResult nearest(double x, double y);
}
