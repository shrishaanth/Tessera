// Tessera Live Map - Phase 3 (real coordinates, OSM road graph, EPSG3857)

const AREA_BOUNDS = [[47.646, -122.334], [47.650, -122.330]];
const WS_URL = `ws://${location.host}/ws/positions`;
const API_GEOFENCES = `/api/geofences`;
const API_ROUTE = `/api/route`;

const map = L.map('map', {
    zoomControl: true
}).setView([47.648, -122.332], 16);

L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
    attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
    maxZoom: 19
}).addTo(map);

map.fitBounds(AREA_BOUNDS);

const markers = new Map();
let selectedVehicleId = null;
let routeLayer = L.layerGroup().addTo(map);
let geofenceLayer = L.layerGroup().addTo(map);
let alertLog = [];
const MAX_ALERTS = 50;

const ZONE_COLORS = {
    center: '#2196f3',
    airport: '#ff9800'
};

function pointInPolygon(x, y, polygon) {
    let inside = false;
    for (let i = 0, j = polygon.length - 1; i < polygon.length; j = i++) {
        const xi = polygon[i].x, yi = polygon[i].y;
        const xj = polygon[j].x, yj = polygon[j].y;
        const intersect = ((yi > y) !== (yj > y)) && (x < (xj - xi) * (y - yi) / (yj - yi) + xi);
        if (intersect) inside = !inside;
    }
    return inside;
}

function getZonesForPoint(x, y, zones) {
    return zones.filter(z => pointInPolygon(x, y, z.polygon));
}

function addAlert(message, type) {
    const time = new Date().toLocaleTimeString();
    alertLog.unshift({ time, message, type });
    if (alertLog.length > MAX_ALERTS) alertLog.pop();
    renderAlerts();
    document.getElementById('alertCount').textContent = alertLog.length;
}

function renderAlerts() {
    const container = document.getElementById('alertList');
    container.innerHTML = alertLog.map(a =>
        `<div class="alert-entry ${a.type === 'enter' ? 'alert-enter' : 'alert-exit'}">[${a.time}] ${a.message}</div>`
    ).join('');
}

function updateVehicles(positions, zones) {
    const seen = new Set();

    for (const pos of positions) {
        const id = pos.vehicleId;
        seen.add(id);
        const pointZones = getZonesForPoint(pos.x, pos.y, zones);

        if (markers.has(id)) {
            const m = markers.get(id);
            m.setLatLng([pos.y, pos.x]);
            const prevZones = m._zones || [];
            const newZones = pointZones.map(z => z.id);

            for (const z of newZones) {
                if (!prevZones.includes(z)) {
                    addAlert(`Vehicle ${id} ENTER zone ${z}`, 'enter');
                }
            }
            for (const z of prevZones) {
                if (!newZones.includes(z)) {
                    addAlert(`Vehicle ${id} EXIT zone ${z}`, 'exit');
                }
            }
            m._zones = newZones;
        } else {
            const isInZone = pointZones.length > 0;
            const color = isInZone ? (ZONE_COLORS[pointZones[0].id] || '#ff5722') : '#3399ff';
            const marker = L.circleMarker([pos.y, pos.x], {
                radius: 5,
                color: '#fff',
                fillColor: color,
                fillOpacity: 0.9,
                weight: 2
            }).addTo(map);
            marker.bindPopup(`<b>Vehicle ${id}</b><br/>lat: ${pos.y.toFixed(5)}, lng: ${pos.x.toFixed(5)}<br/>Zones: ${pointZones.map(z => z.id).join(', ') || 'none'}`);
            marker.on('click', () => selectVehicle(id));
            markers.set(id, marker);
            marker._zones = pointZones.map(z => z.id);
        }
    }

    for (const [id, marker] of markers) {
        if (!seen.has(id)) {
            map.removeLayer(marker);
            markers.delete(id);
        }
    }

    document.getElementById('vehicleCount').textContent = markers.size;
}

async function loadGeofences() {
    try {
        const resp = await fetch(API_GEOFENCES);
        const zones = await resp.json();
        geofenceLayer.clearLayers();
        for (const zone of zones) {
            const latlngs = zone.polygon.map(p => [p.y, p.x]);
            const color = ZONE_COLORS[zone.id] || '#ffff00';
            L.polygon(latlngs, {
                color: color,
                fillColor: color,
                fillOpacity: 0.18,
                weight: 2,
                dashArray: '6 4'
            }).addTo(geofenceLayer).bindPopup(`<b>${zone.id.toUpperCase()}</b>`);
        }
        document.getElementById('zoneCount').textContent = zones.length;
        return zones;
    } catch (e) {
        console.error('Failed to load geofences:', e);
        return [];
    }
}

function selectVehicle(id) {
    selectedVehicleId = id;
    for (const [vid, marker] of markers) {
        if (vid === id) {
            marker.setStyle({ color: '#4caf50', weight: 3, fillColor: '#4caf50' });
            marker.openPopup();
        } else {
            marker.setStyle({ color: '#fff', weight: 2, fillColor: marker.options.fillColor });
        }
    }
}

async function requestRoute(destX, destY) {
    if (!selectedVehicleId) {
        alert('Select a vehicle first by clicking its marker.');
        return;
    }
    const marker = markers.get(selectedVehicleId);
    if (!marker) return;

    routeLayer.clearLayers();
    const url = `${API_ROUTE}?vehicleId=${selectedVehicleId}&destX=${destX}&destY=${destY}`;
    try {
        const resp = await fetch(url);
        if (!resp.ok) throw new Error('Route request failed');
        const route = await resp.json();
        if (route.nodes && route.nodes.length > 1) {
            const latlngs = route.nodes.map(p => [p.y, p.x]);
            L.polyline(latlngs, {
                color: '#e91e63',
                weight: 3,
                opacity: 0.9,
                dashArray: '8 5'
            }).addTo(routeLayer);
            L.marker(latlngs[latlngs.length - 1], {
                icon: L.divIcon({ className: 'dest-marker', html: '&#9733;', iconSize: [16, 16] })
            }).addTo(routeLayer).bindPopup(`Destination (${destX.toFixed(5)}, ${destY.toFixed(5)})`);
        } else {
            alert('No route found to destination.');
        }
    } catch (e) {
        console.error('Route fetch failed:', e);
        alert('Failed to compute route.');
    }
}

let zones = [];
let ws = null;

function connect() {
    ws = new WebSocket(WS_URL);
    ws.onopen = () => {
        console.log('WebSocket connected');
        document.getElementById('statusDot').className = 'status-dot connected';
        document.getElementById('statusText').textContent = 'Live';
    };
    ws.onmessage = (event) => {
        try {
            const positions = JSON.parse(event.data);
            updateVehicles(positions, zones);
        } catch (e) {
            console.error('Failed to parse positions:', e);
        }
    };
    ws.onclose = () => {
        console.log('WebSocket closed, reconnecting in 2s...');
        document.getElementById('statusDot').className = 'status-dot disconnected';
        document.getElementById('statusText').textContent = 'Reconnecting...';
        setTimeout(connect, 2000);
    };
    ws.onerror = (err) => {
        console.error('WebSocket error:', err);
    };
}

map.on('click', (e) => {
    const lat = e.latlng.lat;
    const lng = e.latlng.lng;
    requestRoute(lng, lat);
});

(async function init() {
    zones = await loadGeofences();
    connect();
})();
