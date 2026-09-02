package com.tessera.fleet.transparency;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.tessera.fleet.ingestion.PositionSource;
import com.tessera.fleet.routing.TravelTimeService;
import com.tessera.fleet.transparency.DataSourceInfo.Role;

/**
 * Builds the list backing the data-source transparency panel (FR-7). The live
 * position feed's entry is derived from the active {@link PositionSource} so the
 * disclosure always matches what is actually running (FR-7.2, NFR-6).
 */
@Service
public class DataSourceService {

    private final PositionSource positionSource;
    private final TravelTimeService travelTime;

    public DataSourceService(PositionSource positionSource, TravelTimeService travelTime) {
        this.positionSource = positionSource;
        this.travelTime = travelTime;
    }

    public List<DataSourceInfo> list() {
        List<DataSourceInfo> out = new ArrayList<>();

        out.add(new DataSourceInfo(
                positionSource.id(),
                positionSource.displayName(),
                positionSource.isSubstitute()
                        ? "Not production fleet telematics"
                        : "Customer fleet telematics",
                "Live vehicle positions for the dispatcher map and nearest-vehicle search",
                positionSource.isSubstitute() ? Role.SUBSTITUTE : Role.PRODUCTION,
                positionSource.isSubstitute() ? positionSource.disclosure() : "",
                true));

        out.add(new DataSourceInfo(
                "osm-road-network",
                "OpenStreetMap road network — " + travelTime.graph().areaName(),
                "OpenStreetMap contributors, via the Overpass API (ODbL)",
                "Real road-network travel-time ranking for nearest-available-vehicle assignment",
                Role.PRODUCTION,
                "Real, community-maintained road data. Free; no API key or billing "
                        + "account required.",
                true));

        out.add(new DataSourceInfo(
                "osm-tiles",
                "OpenStreetMap map tiles",
                "OpenStreetMap Foundation tile servers",
                "Base map imagery in the dispatcher and operations-manager views",
                Role.PRODUCTION,
                "Free tile service. Subject to the OSMF tile usage policy; a dedicated "
                        + "tile provider would be used at production scale.",
                true));

        return out;
    }
}
