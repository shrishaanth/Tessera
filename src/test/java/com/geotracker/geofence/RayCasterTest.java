package com.geotracker.geofence;

import com.geotracker.geofence.GeofenceEngine.Zone;
import com.geotracker.model.Position;

import java.util.List;

public class RayCasterTest {
    public static void main(String[] args) {
        boolean allPassed = true;
        allPassed &= testInsideSquare();
        allPassed &= testOutsideSquare();
        allPassed &= testOnEdge();
        allPassed &= testComplexPolygon();
        if (allPassed) {
            System.out.println("All RayCaster tests passed");
        } else {
            System.out.println("Some RayCaster tests FAILED");
            System.exit(1);
        }
    }

    private static boolean testInsideSquare() {
        try {
            var polygon = List.of(
                    new Position(0, 0, 0),
                    new Position(10, 0, 0),
                    new Position(10, 10, 0),
                    new Position(0, 10, 0)
            );
            assert RayCaster.contains(new Position(5, 5, 0), polygon) : "Expected inside";
            System.out.println("PASS: insideSquare");
            return true;
        } catch (Throwable t) {
            System.out.println("FAIL: insideSquare - " + t.getMessage());
            return false;
        }
    }

    private static boolean testOutsideSquare() {
        try {
            var polygon = List.of(
                    new Position(0, 0, 0),
                    new Position(10, 0, 0),
                    new Position(10, 10, 0),
                    new Position(0, 10, 0)
            );
            assert !RayCaster.contains(new Position(15, 5, 0), polygon) : "Expected outside";
            System.out.println("PASS: outsideSquare");
            return true;
        } catch (Throwable t) {
            System.out.println("FAIL: outsideSquare - " + t.getMessage());
            return false;
        }
    }

    private static boolean testOnEdge() {
        try {
            var polygon = List.of(
                    new Position(0, 0, 0),
                    new Position(10, 0, 0),
                    new Position(10, 10, 0),
                    new Position(0, 10, 0)
            );
            assert RayCaster.contains(new Position(5, 0, 0), polygon) : "Expected on edge";
            System.out.println("PASS: onEdge");
            return true;
        } catch (Throwable t) {
            System.out.println("FAIL: onEdge - " + t.getMessage());
            return false;
        }
    }

    private static boolean testComplexPolygon() {
        try {
            var polygon = List.of(
                    new Position(0, 0, 0),
                    new Position(10, 5, 0),
                    new Position(0, 10, 0)
            );
            assert RayCaster.contains(new Position(2, 5, 0), polygon) : "Expected inside triangle";
            assert !RayCaster.contains(new Position(8, 5, 0), polygon) : "Expected outside triangle";
            System.out.println("PASS: complexPolygon");
            return true;
        } catch (Throwable t) {
            System.out.println("FAIL: complexPolygon - " + t.getMessage());
            return false;
        }
    }
}
