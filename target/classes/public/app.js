// Tessera Live Map - Full Feature Implementation

const WS_URL = `ws://${location.host}/ws/positions`;
const WS_URL_EVENTS = `ws://${location.host}/ws/events`;
const API_GEOFENCES = `/api/geofences`;
const API_ROUTE = `/api/route`;
const API_VEHICLES = `/api/vehicles`;
const API_ZONES = `/api/zones`;
const API_SEARCH = `/api/vehicles/search`;

const AREA_BOUNDS = [[47.646, -122.334], [47.650, -122.330]];
const map = L.map('map', { zoomControl: true }).setView([47.648, -122.332], 16);

L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
    attribution: '&copy; OpenStreetMap contributors',
    maxZoom: 19
}).addTo(map);
map.fitBounds(AREA_BOUNDS);

const markers = new Map();
const trails = new Map();
let selectedVehicleId = null;
let routeLayer = L.layerGroup().addTo(map);
let geofenceLayer = L.layerGroup().addTo(map);
let searchLayer = L.layerGroup().addTo(map);
let alertLog = [];
const MAX_ALERTS = 50;
const TRAIL_LENGTH = 50;

const ZONE_COLORS = {
    center: '#2196f3',
    airport: '#ff9800'
};

let currentDrawControl = null;
let pendingZonePolygon = null;
let searchDrawControl = null;

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

function flashMarker(vehicleId) {
    const m = markers.get(vehicleId);
    if (!m) return;
    const originalColor = m.options.fillColor;
    m.setStyle({ color: '#ffeb3b', weight: 4, fillColor: '#ffeb3b' });
    setTimeout(() => {
        m.setStyle({ color: '#fff', weight: 2, fillColor: originalColor });
    }, 600);
}

function flashZone(zoneId) {
    geofenceLayer.eachLayer(layer => {
        if (layer.getPopup() && layer.getPopup().getContent().includes(zoneId.toUpperCase())) {
            const originalOpacity = layer.options.fillOpacity;
            layer.setStyle({ fillOpacity: 0.55, weight: 4 });
            setTimeout(() => {
                layer.setStyle({ fillOpacity: originalOpacity, weight: 2 });
            }, 600);
        }
    });
}

function updateTrails(positions) {
    for (const pos of positions) {
        if (!trails.has(pos.vehicleId)) continue;
        const trail = trails.get(pos.vehicleId);
        trail.positions.push([pos.y, pos.x]);
        if (trail.positions.length > TRAIL_LENGTH) trail.positions.shift();
        trail.polyline.setLatLngs(trail.positions);
    }
}

function renderAllVehicles() {
    const container = document.getElementById('allVehicleList');
    const filter = document.getElementById('vehicleFilter').value.trim();
    container.innerHTML = '';

    const sorted = Array.from(markers.keys()).sort((a, b) => a - b);
    document.getElementById('vehicleTotal').textContent = `(${sorted.length})`;

    for (const vid of sorted) {
        if (filter && !String(vid).includes(filter)) continue;
        const marker = markers.get(vid);
        const isTracked = trails.has(vid);
        const isSelected = selectedVehicleId === vid;

        const div = document.createElement('div');
        div.className = 'vehicle-item' + (isSelected ? ' selected' : '');
        div.innerHTML = `
            <span style="color:${isTracked ? '#ff9800' : '#eee'}">#${vid}</span>
            <div>
                <button class="btn" onclick="selectVehicle(${vid});map.setView(markers.get(${vid}).getLatLng(),17);">Show</button>
                <button class="btn" onclick="trackVehicle(${vid})">${isTracked ? 'Tracking' : 'Track'}</button>
                <button class="btn" onclick="prepareRoute(${vid})">Route</button>
            </div>
        `;
        container.appendChild(div);
    }
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
            m._zones = newZones;
            const isInZone = newZones.length > 0;
            const newColor = isInZone ? (ZONE_COLORS[newZones[0]] || '#ff5722') : '#3399ff';
            if (m.options.fillColor !== newColor) {
                m.setStyle({ fillColor: newColor });
            }
            if (selectedVehicleId === id) {
                updateDetailPanel(id, pos, newZones);
            }
        } else {
            const isInZone = pointZones.length > 0;
            const color = isInZone ? (ZONE_COLORS[pointZones[0].id] || '#ff5722') : '#3399ff';
            const marker = L.circleMarker([pos.y, pos.x], {
                radius: 5, color: '#fff', fillColor: color, fillOpacity: 0.9, weight: 2
            }).addTo(map);
            marker.bindPopup(`<b>Vehicle ${id}</b><br/>lat: ${pos.y.toFixed(5)}, lng: ${pos.x.toFixed(5)}`);
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
    renderAllVehicles();
}

async function loadGeofences() {
    try {
        const resp = await fetch(API_GEOFENCES);
        const zones = await resp.json();
        geofenceLayer.clearLayers();
        const zoneList = document.getElementById('zoneList');
        if (zoneList) zoneList.innerHTML = '';
        for (const zone of zones) {
            const latlngs = zone.polygon.map(p => [p.y, p.x]);
            const color = ZONE_COLORS[zone.id] || '#ffff00';
            L.polygon(latlngs, {
                color, fillColor: color, fillOpacity: 0.15, weight: 2, dashArray: '6 4'
            }).addTo(geofenceLayer).bindPopup(`<b>${zone.id.toUpperCase()}</b>`);
            if (zoneList) {
                const div = document.createElement('div');
                div.className = 'zone-item';
                const monitored = zone.monitoredVehicleIds
                    ? (zone.monitoredVehicleIds.length > 0 ? zone.monitoredVehicleIds.join(', ') : 'All vehicles')
                    : 'All vehicles';
                div.innerHTML = `
                    <h4>${zone.id}</h4>
                    <div>${zone.polygon.length} points</div>
                    <div style="color:#aaa;font-size:11px;">Monitors: ${monitored}</div>
                    ${zone.zoneId ? `<div class="zone-actions"><button class="btn btn-danger" onclick="deleteZone('${zone.zoneId}')">Delete</button></div>` : ''}
                `;
                zoneList.appendChild(div);
            }
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
    document.getElementById('routeSelected').textContent = `Selected: Vehicle #${id}`;
    showDetailPanel(id);
    renderAllVehicles();
}

async function showDetailPanel(vehicleId) {
    try {
        const resp = await fetch(`${API_VEHICLES}/${vehicleId}`);
        if (!resp.ok) return;
        const detail = await resp.json();
        document.getElementById('detailTitle').textContent = `Vehicle #${detail.vehicleId}`;
        document.getElementById('detailPos').textContent = `${detail.position.y.toFixed(5)}, ${detail.position.x.toFixed(5)}`;
        document.getElementById('detailSpeed').textContent = `${detail.speedKmh.toFixed(1)} km/h`;
        document.getElementById('detailHeading').textContent = `${detail.heading.toFixed(1)}°`;
        document.getElementById('detailZones').textContent = detail.zones.length > 0 ? detail.zones.join(', ') : 'none';
        document.getElementById('detailStatus').textContent = detail.status;
        document.getElementById('detailPanel').classList.add('active');
    } catch (e) {
        console.error('Failed to load vehicle detail:', e);
    }
}

function updateDetailPanel(vehicleId, pos, zones) {
    if (selectedVehicleId !== vehicleId) return;
    document.getElementById('detailPos').textContent = `${pos.y.toFixed(5)}, ${pos.x.toFixed(5)}`;
    document.getElementById('detailZones').textContent = zones.length > 0 ? zones.join(', ') : 'none';
}

async function requestRoute(destX, destY) {
    if (!selectedVehicleId) {
        alert('Select a vehicle first.');
        return;
    }
    routeLayer.clearLayers();
    const url = `${API_ROUTE}?vehicleId=${selectedVehicleId}&destX=${destX}&destY=${destY}`;
    try {
        const resp = await fetch(url);
        if (!resp.ok) {
            const err = await resp.text();
            throw new Error(err || 'Route request failed');
        }
        const route = await resp.json();
        if (route.nodes && route.nodes.length > 1) {
            const latlngs = route.nodes.map(p => [p.y, p.x]);
            L.polyline(latlngs, {
                color: '#e91e63', weight: 4, opacity: 0.9
            }).addTo(routeLayer);
            L.marker(latlngs[latlngs.length - 1], {
                icon: L.divIcon({ className: 'dest-marker', html: '&#9733;', iconSize: [16, 16] })
            }).addTo(routeLayer).bindPopup(`Destination`);
            const dist = route.distanceMeters ? route.distanceMeters.toFixed(0) : '?';
            const eta = route.estimatedSeconds ? route.estimatedSeconds.toFixed(0) : '?';
            document.getElementById('routeInfo').textContent = `Route: ${dist}m, ~${eta}s`;
        } else {
            alert('No route found to destination.');
        }
    } catch (e) {
        console.error('Route fetch failed:', e);
        alert('Failed to compute route: ' + e.message);
    }
}

function startDrawing(mode) {
    cancelDrawing();
    const drawnItems = new L.FeatureGroup();
    map.addLayer(drawnItems);
    currentDrawControl = new L.Control.Draw({
        draw: mode === 'rectangle' ? {
            rectangle: { shapeOptions: { color: '#4caf50', weight: 2 } }
        } : {
            polygon: { shapeOptions: { color: '#4caf50', weight: 2 } }
        },
        edit: false
    });
    map.addControl(currentDrawControl);

    map.once('draw:created', (e) => {
        const layer = e.layer;
        drawnItems.addLayer(layer);
        const latlngs = layer.getLatLngs()[0];
        pendingZonePolygon = latlngs.map(ll => ({ x: ll.lng, y: ll.lat }));
        document.getElementById('zoneModal').classList.add('active');
        cancelDrawing();
    });
}

function cancelDrawing() {
    if (currentDrawControl) {
        map.removeControl(currentDrawControl);
        currentDrawControl = null;
    }
}

async function saveZone() {
    const name = document.getElementById('zoneName').value || 'Unnamed Zone';
    const vehicleIdsStr = document.getElementById('zoneVehicles').value;
    const vehicleIds = vehicleIdsStr ? new Set(vehicleIdsStr.split(',').map(s => parseInt(s.trim())).filter(n => !isNaN(n))) : new Set();
    const onEnter = document.getElementById('zoneEnter').checked;
    const onExit = document.getElementById('zoneExit').checked;
    if (!pendingZonePolygon) return;
    const polygon = pendingZonePolygon.map(p => ({ x: p.x, y: p.y }));
    const resp = await fetch(API_ZONES, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name, polygon, vehicleIds: Array.from(vehicleIds), alertOnEnter: onEnter, alertOnExit: onExit })
    });
    if (resp.ok) {
        const result = await resp.json();
        addAlert(`Created zone ${result.zoneId}`, 'enter');
        loadGeofences();
    }
    document.getElementById('zoneModal').classList.remove('active');
    pendingZonePolygon = null;
}

let ws = null;
let wsEvents = null;

function connectPositions() {
    ws = new WebSocket(WS_URL);
    ws.onopen = () => {
        console.log('Positions WebSocket connected');
        document.getElementById('statusDot').className = 'status-dot connected';
        document.getElementById('statusText').textContent = 'Live';
    };
    ws.onmessage = (event) => {
        try {
            const positions = JSON.parse(event.data);
            updateVehicles(positions, currentZones);
            updateTrails(positions);
        } catch (e) {
            console.error('Failed to parse positions:', e);
        }
    };
    ws.onclose = () => {
        console.log('Positions WebSocket closed, reconnecting in 2s...');
        document.getElementById('statusDot').className = 'status-dot disconnected';
        document.getElementById('statusText').textContent = 'Reconnecting...';
        setTimeout(connectPositions, 2000);
    };
    ws.onerror = (err) => {
        console.error('Positions WebSocket error:', err);
    };
}

function connectEvents() {
    wsEvents = new WebSocket(WS_URL_EVENTS);
    wsEvents.onopen = () => {
        console.log('Zone events WebSocket connected');
    };
    wsEvents.onmessage = (event) => {
        try {
            const msg = JSON.parse(event.data);
            if (msg.type === 'zoneEvent') {
                const { vehicleId, zoneId, eventType } = msg;
                addAlert(`Vehicle ${vehicleId} ${eventType.toUpperCase()} zone ${zoneId}`, eventType);
                flashMarker(vehicleId);
                flashZone(zoneId);
            }
        } catch (e) {
            console.error('Failed to parse zone event:', e);
        }
    };
    wsEvents.onclose = () => {
        console.log('Zone events WebSocket closed, reconnecting in 2s...');
        setTimeout(connectEvents, 2000);
    };
    wsEvents.onerror = (err) => {
        console.error('Zone events WebSocket error:', err);
    };
}

function connect() {
    connectPositions();
    connectEvents();
}

let currentZones = [];
let routeMode = false;
let searchMode = false;

function setRouteMode(active) {
    routeMode = active;
    if (active) {
        searchMode = false;
        document.getElementById('btnDrawSearch').textContent = 'Draw Search Rectangle';
        document.getElementById('btnDrawSearch').className = 'btn';
        if (searchDrawControl) {
            map.removeControl(searchDrawControl);
            searchDrawControl = null;
        }
    }
    document.getElementById('btnSetDest').textContent = active ? 'Click Map...' : 'Set Destination';
    document.getElementById('btnSetDest').className = active ? 'btn btn-danger' : 'btn';
}

function setSearchMode(active) {
    searchMode = active;
    if (active) {
        routeMode = false;
        setRouteMode(false);
    }
    document.getElementById('btnDrawSearch').textContent = active ? 'Click map to set corners...' : 'Draw Search Rectangle';
    document.getElementById('btnDrawSearch').className = active ? 'btn btn-danger' : 'btn';
}

document.addEventListener('DOMContentLoaded', async () => {
    currentZones = await loadGeofences();
    connect();

    setInterval(() => {
        if (selectedVehicleId != null && document.getElementById('detailPanel').classList.contains('active')) {
            showDetailPanel(selectedVehicleId);
        }
    }, 2000);

    document.querySelectorAll('.tab').forEach(tab => {
        tab.addEventListener('click', () => {
            document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
            document.querySelectorAll('.panel').forEach(p => p.classList.remove('active'));
            tab.classList.add('active');
            document.getElementById('panel-' + tab.dataset.tab).classList.add('active');
        });
    });

    document.getElementById('sidebarToggle').addEventListener('click', () => {
        document.getElementById('sidebar').classList.toggle('collapsed');
    });

    document.getElementById('btnDrawRect').addEventListener('click', () => startDrawing('rectangle'));
    document.getElementById('btnDrawPoly').addEventListener('click', () => startDrawing('polygon'));
    document.getElementById('btnCancelDraw').addEventListener('click', cancelDrawing);
    document.getElementById('btnZoneSave').addEventListener('click', saveZone);
    document.getElementById('btnZoneCancel').addEventListener('click', () => {
        document.getElementById('zoneModal').classList.remove('active');
        pendingZonePolygon = null;
    });

    document.getElementById('btnTrack').addEventListener('click', () => {
        const vid = parseInt(document.getElementById('trackerSearch').value);
        if (isNaN(vid)) return;
        trackVehicle(vid);
    });

    document.getElementById('btnStopAll').addEventListener('click', () => {
        for (const [vid, trail] of trails) {
            map.removeLayer(trail.polyline);
        }
        trails.clear();
        document.getElementById('trackedList').innerHTML = '';
        renderAllVehicles();
    });

    document.getElementById('btnFindVehicle').addEventListener('click', () => {
        const vid = parseInt(document.getElementById('routeVehicleId').value);
        if (isNaN(vid)) return;
        const marker = markers.get(vid);
        if (marker) {
            map.setView(marker.getLatLng(), 17);
            selectVehicle(vid);
        } else {
            alert('Vehicle not found on map');
        }
    });

    document.getElementById('btnSetDest').addEventListener('click', () => {
        setRouteMode(!routeMode);
    });

    document.getElementById('btnClearRoute').addEventListener('click', () => {
        routeLayer.clearLayers();
        document.getElementById('routeInfo').textContent = '';
    });

    document.getElementById('btnDetailTrack').addEventListener('click', () => {
        const vid = selectedVehicleId;
        if (vid == null) return;
        trackVehicle(vid);
    });

    document.getElementById('btnDetailRoute').addEventListener('click', () => {
        if (selectedVehicleId == null) return;
        document.getElementById('routeVehicleId').value = selectedVehicleId;
        document.getElementById('routeSelected').textContent = `Selected: Vehicle #${selectedVehicleId}`;
        document.querySelector('[data-tab="routes"]').click();
        setRouteMode(true);
    });

    document.getElementById('btnDrawSearch').addEventListener('click', () => {
        if (searchMode) {
            setSearchMode(false);
            return;
        }
        setSearchMode(true);
        cancelDrawing();
        const drawnItems = new L.FeatureGroup();
        map.addLayer(drawnItems);
        searchDrawControl = new L.Control.Draw({
            draw: { rectangle: { shapeOptions: { color: '#4caf50', weight: 2 } } },
            edit: false
        });
        map.addControl(searchDrawControl);
        map.once('draw:created', (e) => {
            const layer = e.layer;
            drawnItems.addLayer(layer);
            const latlngs = layer.getLatLngs()[0];
            const bounds = [
                [latlngs[0].lat, latlngs[0].lng],
                [latlngs[2].lat, latlngs[2].lng]
            ];
            L.rectangle(bounds, { color: '#4caf50', weight: 2, fillOpacity: 0.1 }).addTo(searchLayer);
            searchVehicles(bounds);
            setSearchMode(false);
            if (searchDrawControl) {
                map.removeControl(searchDrawControl);
                searchDrawControl = null;
            }
        });
    });

    document.getElementById('btnClearSearch').addEventListener('click', () => {
        searchLayer.clearLayers();
        document.getElementById('searchResults').innerHTML = '';
    });

    document.getElementById('vehicleFilter').addEventListener('input', renderAllVehicles);

    map.on('click', (e) => {
        if (routeMode && selectedVehicleId != null) {
            requestRoute(e.latlng.lng, e.latlng.lat);
            setRouteMode(false);
        }
    });
});

window.stopTracking = function(vid) {
    if (trails.has(vid)) {
        map.removeLayer(trails.get(vid).polyline);
        trails.delete(vid);
    }
    const el = document.getElementById('track-' + vid);
    if (el) el.remove();
    renderAllVehicles();
};

window.trackVehicle = function(vid) {
    if (!trails.has(vid)) {
        const polyline = L.polyline([], { color: '#ff9800', weight: 2, opacity: 0.7 }).addTo(map);
        trails.set(vid, { polyline, positions: [] });
    }
    selectVehicle(vid);
    const trackedList = document.getElementById('trackedList');
    if (!document.getElementById('track-' + vid)) {
        const div = document.createElement('div');
        div.className = 'vehicle-item';
        div.id = 'track-' + vid;
        div.innerHTML = `<span>#${vid}</span><button class="btn btn-danger" onclick="stopTracking(${vid})">Stop</button>`;
        trackedList.appendChild(div);
    }
    renderAllVehicles();
};

window.prepareRoute = function(vid) {
    document.getElementById('routeVehicleId').value = vid;
    document.getElementById('routeSelected').textContent = `Selected: Vehicle #${vid}`;
    document.querySelector('[data-tab="routes"]').click();
    selectVehicle(vid);
    setRouteMode(true);
};

window.deleteZone = async function(zoneId) {
    if (!confirm('Delete this zone?')) return;
    await fetch(`${API_ZONES}/${zoneId}`, { method: 'DELETE' });
    loadGeofences();
};

async function searchVehicles(bounds) {
    const bbox = {
        minX: bounds[0][1],
        minY: bounds[0][0],
        maxX: bounds[1][1],
        maxY: bounds[1][0]
    };
    try {
        const resp = await fetch(API_SEARCH, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ bbox, vehicleIds: [] })
        });
        const results = await resp.json();
        const container = document.getElementById('searchResults');
        container.innerHTML = `<div style="margin-bottom:8px;color:#aaa;">Found ${results.length} vehicles</div>`;
        for (const v of results) {
            flashMarker(v.vehicleId);
            const div = document.createElement('div');
            div.className = 'vehicle-item';
            div.innerHTML = `<span>#${v.vehicleId} (${v.y.toFixed(4)}, ${v.x.toFixed(4)})</span>
                <button class="btn" onclick="selectVehicle(${v.vehicleId});map.setView([${v.y},${v.x}],17);">Track</button>`;
            container.appendChild(div);
        }
    } catch (e) {
        console.error('Search failed:', e);
    }
}
