package com.geotracker.geofence;

import com.geotracker.model.Position;

import java.util.ArrayList;
import java.util.List;

public class RayCaster {
    public static boolean contains(Position point, List<Position> polygon) {
        if (polygon.size() < 3) return false;
        boolean inside = false;
        int n = polygon.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            Position pi = polygon.get(i);
            Position pj = polygon.get(j);
            if (((pi.y() > point.y()) != (pj.y() > point.y())) &&
                    (point.x() < (pj.x() - pi.x()) * (point.y() - pi.y()) / (pj.y() - pi.y()) + pi.x())) {
                inside = !inside;
            }
        }
        return inside;
    }
}
