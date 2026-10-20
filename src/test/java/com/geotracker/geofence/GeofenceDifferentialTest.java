package com.geotracker.geofence;

import com.geotracker.model.Position;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GeofenceDifferentialTest {

    private List<Position> square() {
        return List.of(
                new Position(0, 0, 0),
                new Position(10, 0, 0),
                new Position(10, 10, 0),
                new Position(0, 10, 0)
        );
    }

    private List<Position> concaveL() {
        return List.of(
                new Position(0, 0, 0),
                new Position(10, 0, 0),
                new Position(10, 5, 0),
                new Position(5, 5, 0),
                new Position(5, 10, 0),
                new Position(0, 10, 0)
        );
    }

    @Test
    void convexSquare_insidePoints() {
        List<Position> sq = square();
        assertTrue(RayCaster.contains(new Position(5, 5, 0), sq));
        assertTrue(RayCaster.contains(new Position(1, 1, 0), sq));
        assertTrue(RayCaster.contains(new Position(9.9, 9.9, 0), sq));
    }

    @Test
    void convexSquare_outsidePoints() {
        List<Position> sq = square();
        assertFalse(RayCaster.contains(new Position(-1, 5, 0), sq));
        assertFalse(RayCaster.contains(new Position(5, -1, 0), sq));
        assertFalse(RayCaster.contains(new Position(11, 5, 0), sq));
        assertFalse(RayCaster.contains(new Position(5, 11, 0), sq));
    }

    @Test
    void concaveL_insidePoints() {
        List<Position> l = concaveL();
        assertTrue(RayCaster.contains(new Position(2, 2, 0), l));  // bottom part
        assertTrue(RayCaster.contains(new Position(2, 8, 0), l));  // left part
    }

    @Test
    void concaveL_outsidePoints() {
        List<Position> l = concaveL();
        assertFalse(RayCaster.contains(new Position(8, 8, 0), l));  // concave cutout
        assertFalse(RayCaster.contains(new Position(15, 5, 0), l)); // outside entirely
    }

    @Test
    void degeneratePolygon_fewerThan3Points() {
        List<Position> line = List.of(
                new Position(0, 0, 0),
                new Position(10, 10, 0)
        );
        assertFalse(RayCaster.contains(new Position(5, 5, 0), line));
    }

    @Test
    void triangle() {
        List<Position> tri = List.of(
                new Position(0, 0, 0),
                new Position(10, 0, 0),
                new Position(5, 10, 0)
        );
        assertTrue(RayCaster.contains(new Position(5, 3, 0), tri));
        assertFalse(RayCaster.contains(new Position(1, 9, 0), tri));
    }

    @Test
    void geoCoordinatePolygon() {
        // Seattle area polygon
        List<Position> poly = List.of(
                new Position(-122.334, 47.646, 0),
                new Position(-122.330, 47.646, 0),
                new Position(-122.330, 47.650, 0),
                new Position(-122.334, 47.650, 0)
        );
        assertTrue(RayCaster.contains(new Position(-122.332, 47.648, 0), poly));
        assertFalse(RayCaster.contains(new Position(-122.335, 47.648, 0), poly));
    }
}
