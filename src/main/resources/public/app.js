// Tessera Live Map - Phase 1 (abstract CRS.Simple coordinates)

const MAP_BOUNDS = [[0, 0], [1000, 1000]];
const WS_URL = `ws://${location.host}/ws/positions`;
const API_GEOFENCES = `/api/geofences`;

const map = L.map('map', {
    crs: L.CRS.Simple,
    minZoom: -2,
    maxZoom: 2,
    zoomControl: true
}).fitBounds(MAP_BOUNDS);

// Light background for the abstract grid
L.rectangle(MAP_BOUNDS, {color: "#333", weight: 1, fillColor: "#222", fillOpacity: 1}).addTo(map);

const markers = new Map();
let geofenceLayer = L.layerGroup().addTo(map);

function updateVehicles(positions) {
    const seen = new Set();
    for (const pos of positions) {
        const id = pos.vehicleId;
        seen.add(id);
        if (markers.has(id)) {
            const marker = markers.get(id);
            marker.setLatLng([pos.y, pos.x]);
        } else {
            const marker = L.circleMarker([pos.y, pos.x], {
                radius: 4,
                color: '#3399ff',
                fillColor: '#3399ff',
                fillOpacity: 0.9,
                weight: 1
            }).addTo(map);
            markers.set(id, marker);
        }
    }
    // Remove vehicles no longer present
    for (const [id, marker] of markers) {
        if (!seen.has(id)) {
            map.removeLayer(marker);
            markers.delete(id);
        }
    }
}

async function loadGeofences() {
    try {
        const resp = await fetch(API_GEOFENCES);
        const zones = await resp.json();
        geofenceLayer.clearLayers();
        for (const zone of zones) {
            const latlngs = zone.polygon.map(p => [p.y, p.x]);
            L.polygon(latlngs, {
                color: '#ffff00',
                fillColor: '#ffff00',
                fillOpacity: 0.2,
                weight: 2
            }).addTo(geofenceLayer);
        }
    } catch (e) {
        console.error('Failed to load geofences:', e);
    }
}

const ws = new WebSocket(WS_URL);
ws.onopen = () => {
    console.log('WebSocket connected');
};
ws.onmessage = (event) => {
    try {
        const positions = JSON.parse(event.data);
        updateVehicles(positions);
    } catch (e) {
        console.error('Failed to parse positions:', e);
    }
};
ws.onclose = () => {
    console.log('WebSocket closed, reconnecting in 2s...');
    setTimeout(() => {
        location.reload();
    }, 2000);
};
ws.onerror = (err) => {
    console.error('WebSocket error:', err);
};

loadGeofences();
