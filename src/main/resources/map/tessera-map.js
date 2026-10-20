/*
 * Tessera map integration. Exposes window.TesseraMap, called from Java (MapPane) via executeScript.
 * Events go back to Java through window.tesseraBridge.post("type|arg|arg...").
 *
 * Coordinate convention: everything crossing this boundary is [lat, lng] (Leaflet order).
 */
(function () {
    'use strict';

    // ---------------------------------------------------------------- bridge to Java
    var pending = [];

    function post(message) {
        var bridge = window.tesseraBridge;
        if (bridge) {
            try {
                bridge.post(String(message));
                return;
            } catch (e) { /* fall through and queue */ }
        }
        if (pending.length < 200) pending.push(String(message));
    }

    function flushPending() {
        var queued = pending;
        pending = [];
        for (var i = 0; i < queued.length; i++) post(queued[i]);
    }

    ['log', 'warn', 'error'].forEach(function (level) {
        var original = console[level];
        console[level] = function () {
            var text = Array.prototype.slice.call(arguments).map(String).join(' ');
            post('log|' + level + '|' + text);
            if (original) { try { original.apply(console, arguments); } catch (e) { /* ignore */ } }
        };
    });
    window.addEventListener('error', function (e) {
        post('log|error|' + e.message + ' (' + (e.filename || '') + ':' + (e.lineno || 0) + ')');
    });

    // Links (e.g. attribution) must open in the system browser, not navigate the map view away.
    document.addEventListener('click', function (ev) {
        var node = ev.target;
        while (node && node !== document && node.tagName !== 'A') node = node.parentNode;
        if (node && node.tagName === 'A' && /^https?:/.test(node.href || '')) {
            ev.preventDefault();
            ev.stopPropagation();
            post('open|' + node.href);
        }
    }, true);

    // ---------------------------------------------------------------- constants
    var COLORS = {
        vehicleGraph: '#2a78d6',
        vehicleCsv: '#eb6834',
        vehicleStale: '#9aa0a6',
        selectedHalo: 'rgba(26, 115, 232, 0.22)',
        selectedRing: '#1a73e8',
        searchRing: '#f29900',
        route: '#6250d6',
        routeCasing: '#ffffff',
        roadGraph: '#3c4043',
        searchStroke: '#5f6368',
        draft: '#1a73e8'
    };
    var STATUS = { ACTIVE: 0, STALE: 1, PAUSED: 2, IDLE: 3 };
    var OSM_URL = 'https://tile.openstreetmap.org/{z}/{x}/{y}.png';
    var OSM_ATTRIBUTION = '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors';
    var RETRY_DELAYS_MS = [1000, 3000, 8000];

    // ---------------------------------------------------------------- Leaflet adjustments for WebView
    // With 3D transforms disabled Leaflet has no animated zoom: every zoom goes through _resetView, whose
    // 'viewprereset' event makes tile layers discard all tiles, and tile levels are never scaled. The map
    // then shows bare background until the new zoom level's tiles arrive. Scale levels with a 2D
    // transform (which this WebKit renders correctly) and keep tiles across zoom changes, so Leaflet's
    // usual behaviour applies: the previous level stays visible, scaled, until the new tiles replace it.
    L.GridLayer.include({
        _setZoomTransform: function (level, center, zoom) {
            var scale = this._map.getZoomScale(zoom, level.zoom);
            var translate = level.origin.multiplyBy(scale).subtract(this._map._getNewPixelOrigin(center, zoom)).round();
            level.el.style[L.DomUtil.TRANSFORM] = 'translate(' + translate.x + 'px,' + translate.y + 'px) scale(' + scale + ')';
        }
    });
    var RetainingTileLayer = L.TileLayer.extend({
        getEvents: function () {
            var events = L.TileLayer.prototype.getEvents.call(this);
            delete events.viewprereset;
            return events;
        }
    });

    // ---------------------------------------------------------------- map
    var map = L.map('map', {
        center: [47.6482, -122.3334],
        zoom: 16,
        minZoom: 3,
        maxZoom: 19,
        zoomControl: false,
        attributionControl: true,
        wheelDebounceTime: 60,
        wheelPxPerZoomLevel: 90,
        // Leaflet's default is unbounded; WebView can deliver bursts of coalesced mouse moves, which would
        // otherwise fling the map far away after a short drag.
        inertiaMaxSpeed: 1500
    });
    map.attributionControl.setPrefix('<a href="https://leafletjs.com">Leaflet</a>');

    function makePane(name, zIndex, pointerEvents) {
        var pane = map.createPane(name);
        pane.style.zIndex = String(zIndex);
        if (pointerEvents === false) pane.style.pointerEvents = 'none';
        return pane;
    }
    makePane('roadBasePane', 250, false);
    makePane('roadGraphPane', 405, false);
    makePane('zonesPane', 410);
    makePane('routePane', 420, false);
    makePane('searchPane', 430, false);
    makePane('zoneLabelPane', 440, false);
    makePane('vehiclePane', 450, false);
    makePane('draftPane', 460, false);

    L.control.scale({ position: 'bottomleft', imperial: false, maxWidth: 120 }).addTo(map);
    L.control.zoom({ position: 'bottomright', zoomInTitle: 'Zoom in', zoomOutTitle: 'Zoom out' }).addTo(map);

    var ICON_FIT = '<svg viewBox="0 0 24 24"><path d="M5 15H3v4a2 2 0 0 0 2 2h4v-2H5v-4zm0-10h4V3H5a2 2 0 0 0-2 2v4h2V5zm14-2h-4v2h4v4h2V5a2 2 0 0 0-2-2zm0 16h-4v2h4a2 2 0 0 0 2-2v-4h-2v4zM12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8z"/></svg>';
    var ICON_HOME = '<svg viewBox="0 0 24 24"><path d="M12 3 2 12h3v8h6v-6h2v6h6v-8h3L12 3z"/></svg>';
    var ViewControl = L.Control.extend({
        options: { position: 'bottomright' },
        onAdd: function () {
            var container = L.DomUtil.create('div', 'leaflet-bar tessera-view-control');
            addButton(container, 'Fit all vehicles', ICON_FIT, function () { api.fitVehicles(); });
            addButton(container, 'Fit road network (simulation area)', ICON_HOME, function () { api.fitDataset(); });
            L.DomEvent.disableClickPropagation(container);
            L.DomEvent.disableScrollPropagation(container);
            return container;
        }
    });
    function addButton(container, title, svg, onClick) {
        var button = L.DomUtil.create('button', '', container);
        button.type = 'button';
        button.title = title;
        button.setAttribute('aria-label', title);
        button.innerHTML = svg;
        L.DomEvent.on(button, 'click', function (e) { L.DomEvent.stop(e); onClick(); });
    }
    new ViewControl().addTo(map);

    var state = {
        datasetBounds: null,
        initialViewApplied: false,
        basemap: 'osm',
        fallbackActive: false,
        visible: { vehicles: true, roadGraph: false, zones: true, route: true, search: true, draft: true },
        selectedId: -1,
        followId: -1,
        pickMode: null,
        searchHighlight: {},
        lastDrawMs: 0
    };

    // ---------------------------------------------------------------- basemap tiles
    var tileStats = { requested: 0, loaded: 0, errors: 0, retries: 0, failed: 0, lastLoadAt: 0, lastErrorAt: 0, diagnostic: '',
                      loadMsTotal: 0, loadMsMax: 0 };
    var errorsSinceLastLoad = 0;
    var diagnosticsRequested = 0;

    var tileLayer = new RetainingTileLayer(OSM_URL, {
        maxZoom: 19,
        maxNativeZoom: 19,
        attribution: OSM_ATTRIBUTION,
        keepBuffer: 3
    });
    tileLayer.on('tileloadstart', function (e) {
        tileStats.requested++;
        e.tile._tesseraStart = Date.now();
    });
    tileLayer.on('tileload', function (e) {
        tileStats.loaded++;
        if (e.tile._tesseraStart) {
            var ms = Date.now() - e.tile._tesseraStart;
            tileStats.loadMsTotal += ms;
            tileStats.loadMsMax = Math.max(tileStats.loadMsMax, ms);
        }
        tileStats.lastLoadAt = Date.now();
        errorsSinceLastLoad = 0;
        if (state.fallbackActive && state.basemap === 'osm') setFallback(false);
    });
    tileLayer.on('tileerror', function (e) {
        tileStats.errors++;
        tileStats.lastErrorAt = Date.now();
        errorsSinceLastLoad++;
        var tile = e.tile;
        var attempt = tile._tesseraRetries || 0;
        var url = tileLayer.getTileUrl(e.coords);
        if (diagnosticsRequested < 3) {
            diagnosticsRequested++;
            post('tileerror|' + url);
        }
        if (attempt < RETRY_DELAYS_MS.length) {
            tile._tesseraRetries = attempt + 1;
            tileStats.retries++;
            setTimeout(function () {
                // The fragment changes the src string (forcing a reload) without changing the HTTP request.
                if (tile.parentNode && map.hasLayer(tileLayer)) tile.src = url + '#retry' + (attempt + 1);
            }, RETRY_DELAYS_MS[attempt]);
        } else {
            tile._tesseraFailed = true;
            tileStats.failed++;
        }
        if (state.basemap === 'osm' && !state.fallbackActive && errorsSinceLastLoad >= 6
                && Date.now() - tileStats.lastLoadAt > 5000) {
            setFallback(true);
        }
    });
    tileLayer.addTo(map);

    var bannerEl = document.getElementById('tile-banner');
    var bannerDetail = document.getElementById('tile-banner-detail');
    document.getElementById('tile-banner-retry').addEventListener('click', function () { api.retryTiles(); });

    function updateBanner() {
        var show = state.basemap === 'osm' && state.fallbackActive;
        bannerEl.hidden = !show;
        if (show) {
            var detail = tileStats.errors + ' tile request' + (tileStats.errors === 1 ? '' : 's') + ' failed';
            if (tileStats.diagnostic) detail += ' (' + tileStats.diagnostic + ')';
            bannerDetail.textContent = detail + '. Showing the bundled road network instead.';
        }
    }

    function setFallback(active) {
        state.fallbackActive = active;
        refreshRoadBase();
        updateBanner();
        post('tiles|' + (active ? 'fallback' : 'online'));
    }

    // ---------------------------------------------------------------- road network (offline basemap + overlay)
    // Polylines ([[lat,lng],...] each). City-scale networks have tens of thousands of points, so these
    // layers use canvas renderers and are only built when first shown.
    var roadSegments = [];
    var roadBaseRenderer = L.svg({ pane: 'roadBasePane', padding: 0.3 });
    var roadGraphRenderer = L.svg({ pane: 'roadGraphPane', padding: 0.3 });
    var roadBaseLayer = null;
    var roadGraphLayer = null;

    function refreshRoadBase() {
        var showBase = state.basemap === 'offline' || state.fallbackActive;
        map.getContainer().classList.toggle('offline-basemap', showBase);
        if (showBase && !roadBaseLayer && roadSegments.length) {
            roadBaseLayer = L.layerGroup([
                L.polyline(roadSegments, { pane: 'roadBasePane', renderer: roadBaseRenderer, color: '#c3c7cd', weight: 8, lineCap: 'round', lineJoin: 'round', interactive: false }),
                L.polyline(roadSegments, { pane: 'roadBasePane', renderer: roadBaseRenderer, color: '#ffffff', weight: 5.5, lineCap: 'round', lineJoin: 'round', interactive: false })
            ], { attribution: 'Road network ' + OSM_ATTRIBUTION });
        }
        if (roadBaseLayer) {
            if (showBase && !map.hasLayer(roadBaseLayer)) roadBaseLayer.addTo(map);
            if (!showBase && map.hasLayer(roadBaseLayer)) map.removeLayer(roadBaseLayer);
        }
    }

    function refreshRoadGraphOverlay() {
        if (!roadGraphLayer && roadSegments.length && state.visible.roadGraph) {
            roadGraphLayer = L.polyline(roadSegments, {
                pane: 'roadGraphPane', renderer: roadGraphRenderer, color: COLORS.roadGraph, weight: 1.5, opacity: 0.55, interactive: false
            });
        }
        if (!roadGraphLayer) return;
        var show = state.visible.roadGraph;
        if (show && !map.hasLayer(roadGraphLayer)) roadGraphLayer.addTo(map);
        if (!show && map.hasLayer(roadGraphLayer)) map.removeLayer(roadGraphLayer);
    }

    // ---------------------------------------------------------------- vehicles (single canvas layer)
    var fleet = {
        n: 0, cap: 0,
        id: null, lat: null, lng: null, heading: null, status: null, source: null,
        px: null, py: null, projZoom: -1
    };
    var indexById = {};

    function ensureCapacity(n) {
        if (fleet.cap >= n) return;
        var cap = Math.max(64, n);
        fleet.id = new Int32Array(cap);
        fleet.lat = new Float64Array(cap);
        fleet.lng = new Float64Array(cap);
        fleet.heading = new Float32Array(cap);
        fleet.status = new Uint8Array(cap);
        fleet.source = new Uint8Array(cap);
        fleet.px = new Float64Array(cap);
        fleet.py = new Float64Array(cap);
        fleet.cap = cap;
    }

    function projectFleet() {
        var z = map.getZoom();
        if (fleet.projZoom === z) return;
        for (var i = 0; i < fleet.n; i++) {
            var p = map.project([fleet.lat[i], fleet.lng[i]], z);
            fleet.px[i] = p.x;
            fleet.py[i] = p.y;
        }
        fleet.projZoom = z;
    }

    var VehicleLayer = L.Layer.extend({
        onAdd: function (m) {
            this._canvas = L.DomUtil.create('canvas', 'tessera-vehicle-canvas');
            m.getPane('vehiclePane').appendChild(this._canvas);
            this._ctx = this._canvas.getContext('2d');
            this._frame = null;
            m.on('move zoomend viewreset resize', this._schedule, this);
            this._draw();
        },
        onRemove: function (m) {
            m.off('move zoomend viewreset resize', this._schedule, this);
            L.DomUtil.remove(this._canvas);
        },
        _schedule: function () {
            if (this._frame) return;
            var self = this;
            this._frame = L.Util.requestAnimFrame(function () { self._frame = null; self._draw(); });
        },
        redraw: function () { if (this._map) this._schedule(); },
        // Offset that converts projected pixel coordinates into canvas (= container) coordinates.
        offset: function () {
            var origin = this._map.getPixelOrigin();
            var topLeft = this._map.containerPointToLayerPoint([0, 0]);
            return { x: -origin.x - topLeft.x, y: -origin.y - topLeft.y, topLeft: topLeft };
        },
        /** Detailed markers (white ring + heading arrow) only when they will not overlap into a blob. */
        detailed: function () {
            return fleet.n <= 2500 && map.getZoom() >= 16;
        },
        markerRadius: function () {
            var z = map.getZoom();
            if (this.detailed()) return z >= 17 ? 7 : 5.5;
            // Large fleets at city zoom: small dots so streets and density stay readable.
            if (fleet.n > 5000) return z >= 17 ? 4 : z >= 15 ? 2.6 : z >= 13 ? 1.9 : 1.4;
            return z >= 15 ? 4.5 : z >= 13 ? 3.5 : 2.6;
        },
        /** Translucent fill when thousands of small dots overlap, so dense areas read as density, not a blob. */
        fillAlpha: function () {
            return fleet.n > 5000 && map.getZoom() < 15 ? 0.65 : 1;
        },
        _draw: function () {
            var started = Date.now();
            var m = this._map;
            if (!m) return;
            var size = m.getSize();
            var dpr = window.devicePixelRatio || 1;
            var canvas = this._canvas;
            var w = Math.round(size.x * dpr), h = Math.round(size.y * dpr);
            if (canvas.width !== w || canvas.height !== h) {
                canvas.width = w;
                canvas.height = h;
                canvas.style.width = size.x + 'px';
                canvas.style.height = size.y + 'px';
            }
            var off = this.offset();
            L.DomUtil.setPosition(canvas, off.topLeft);
            var ctx = this._ctx;
            ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
            ctx.clearRect(0, 0, size.x, size.y);
            if (!state.visible.vehicles || fleet.n === 0) {
                state.lastDrawMs = Date.now() - started;
                return;
            }
            projectFleet();

            var r = this.markerRadius();
            var detailed = this.detailed();
            var minX = -20, minY = -20, maxX = size.x + 20, maxY = size.y + 20;
            var i, x, y;

            // Search result rings underneath the markers.
            var hasSearch = state.visible.search && Object.keys(state.searchHighlight).length > 0;
            if (hasSearch) {
                ctx.beginPath();
                for (i = 0; i < fleet.n; i++) {
                    if (!state.searchHighlight[fleet.id[i]]) continue;
                    x = fleet.px[i] + off.x; y = fleet.py[i] + off.y;
                    if (x < minX || y < minY || x > maxX || y > maxY) continue;
                    ctx.moveTo(x + r + 5, y);
                    ctx.arc(x, y, r + 5, 0, Math.PI * 2);
                }
                ctx.lineWidth = 2.5;
                ctx.strokeStyle = COLORS.searchRing;
                ctx.stroke();
            }

            var groups = [
                { color: COLORS.vehicleGraph, test: function (k) { return fleet.status[k] !== STATUS.STALE && fleet.source[k] === 0; } },
                { color: COLORS.vehicleCsv, test: function (k) { return fleet.status[k] !== STATUS.STALE && fleet.source[k] === 1; } },
                { color: COLORS.vehicleStale, test: function (k) { return fleet.status[k] === STATUS.STALE; } }
            ];
            var g;
            if (detailed) {
                // White outline ring for every visible marker, then coloured disc + heading arrow.
                ctx.beginPath();
                for (i = 0; i < fleet.n; i++) {
                    x = fleet.px[i] + off.x; y = fleet.py[i] + off.y;
                    if (x < minX || y < minY || x > maxX || y > maxY) continue;
                    ctx.moveTo(x + r + 2, y);
                    ctx.arc(x, y, r + 2, 0, Math.PI * 2);
                }
                ctx.fillStyle = '#ffffff';
                ctx.shadowColor = 'rgba(0,0,0,0.30)';
                ctx.shadowBlur = 3;
                ctx.fill();
                ctx.shadowBlur = 0;
                ctx.shadowColor = 'transparent';
            }
            for (g = 0; g < groups.length; g++) {
                ctx.beginPath();
                var any = false;
                for (i = 0; i < fleet.n; i++) {
                    if (!groups[g].test(i)) continue;
                    x = fleet.px[i] + off.x; y = fleet.py[i] + off.y;
                    if (x < minX || y < minY || x > maxX || y > maxY) continue;
                    ctx.moveTo(x + r, y);
                    ctx.arc(x, y, r, 0, Math.PI * 2);
                    any = true;
                }
                if (any) {
                    ctx.fillStyle = groups[g].color;
                    ctx.globalAlpha = this.fillAlpha();
                    ctx.fill();
                    ctx.globalAlpha = 1;
                    if (!detailed && fleet.n <= 5000) {
                        ctx.lineWidth = 1;
                        ctx.strokeStyle = 'rgba(255,255,255,0.9)';
                        ctx.stroke();
                    }
                }
            }
            if (detailed) {
                ctx.beginPath();
                for (i = 0; i < fleet.n; i++) {
                    var hd = fleet.heading[i];
                    if (hd < 0) continue;
                    x = fleet.px[i] + off.x; y = fleet.py[i] + off.y;
                    if (x < minX || y < minY || x > maxX || y > maxY) continue;
                    arrowPath(ctx, x, y, r * 0.62, hd);
                }
                ctx.fillStyle = '#ffffff';
                ctx.fill();
            }

            // Selected vehicle on top with a halo.
            var sel = indexById[state.selectedId];
            if (sel !== undefined) {
                x = fleet.px[sel] + off.x; y = fleet.py[sel] + off.y;
                var rs = Math.max(r, 7) + 2;
                ctx.beginPath();
                ctx.arc(x, y, rs + 11, 0, Math.PI * 2);
                ctx.fillStyle = COLORS.selectedHalo;
                ctx.fill();
                ctx.lineWidth = 2;
                ctx.strokeStyle = COLORS.selectedRing;
                ctx.stroke();
                ctx.beginPath();
                ctx.arc(x, y, rs + 2.5, 0, Math.PI * 2);
                ctx.fillStyle = '#ffffff';
                ctx.shadowColor = 'rgba(0,0,0,0.35)';
                ctx.shadowBlur = 4;
                ctx.fill();
                ctx.shadowBlur = 0;
                ctx.shadowColor = 'transparent';
                ctx.beginPath();
                ctx.arc(x, y, rs, 0, Math.PI * 2);
                ctx.fillStyle = fleet.status[sel] === STATUS.STALE ? COLORS.vehicleStale
                    : (fleet.source[sel] === 1 ? COLORS.vehicleCsv : COLORS.vehicleGraph);
                ctx.fill();
                if (fleet.heading[sel] >= 0) {
                    ctx.beginPath();
                    arrowPath(ctx, x, y, rs * 0.62, fleet.heading[sel]);
                    ctx.fillStyle = '#ffffff';
                    ctx.fill();
                }
            }
            state.lastDrawMs = Date.now() - started;
        }
    });

    function arrowPath(ctx, x, y, s, headingDeg) {
        var a = headingDeg * Math.PI / 180;
        var sin = Math.sin(a), cos = Math.cos(a);
        // Arrow pointing up (north) in local coordinates, rotated clockwise by the heading.
        var pts = [[0, -s], [s * 0.72, s * 0.72], [0, s * 0.3], [-s * 0.72, s * 0.72]];
        for (var k = 0; k < pts.length; k++) {
            var px = pts[k][0] * cos - pts[k][1] * sin + x;
            var py = pts[k][0] * sin + pts[k][1] * cos + y;
            if (k === 0) ctx.moveTo(px, py); else ctx.lineTo(px, py);
        }
        ctx.closePath();
    }

    var vehicleLayer = new VehicleLayer().addTo(map);

    function hitTest(containerPoint) {
        if (!state.visible.vehicles || fleet.n === 0) return -1;
        projectFleet();
        var off = vehicleLayer.offset();
        var r = vehicleLayer.markerRadius() + 5;
        var best = -1, bestD = r * r;
        for (var i = 0; i < fleet.n; i++) {
            var dx = fleet.px[i] + off.x - containerPoint.x;
            var dy = fleet.py[i] + off.y - containerPoint.y;
            var d = dx * dx + dy * dy;
            if (d <= bestD) { bestD = d; best = i; }
        }
        return best >= 0 ? fleet.id[best] : -1;
    }

    // ---------------------------------------------------------------- overlays
    var zonesLayer = L.layerGroup().addTo(map);
    var routeLayer = L.layerGroup().addTo(map);
    var searchLayer = L.layerGroup().addTo(map);
    var draftLayer = L.layerGroup().addTo(map);
    var zoneData = [];
    var highlightedZone = null;

    function renderZones() {
        zonesLayer.clearLayers();
        for (var i = 0; i < zoneData.length; i++) {
            var z = zoneData[i];
            if (!z.points || z.points.length < 3) continue;
            var highlighted = z.id === highlightedZone;
            var poly = L.polygon(z.points, {
                pane: 'zonesPane',
                color: z.color,
                weight: highlighted ? 3.5 : 2,
                opacity: 0.95,
                fillColor: z.color,
                fillOpacity: highlighted ? 0.24 : 0.10,
                dashArray: z.builtIn ? null : '7 5',
                interactive: false
            });
            zonesLayer.addLayer(poly);
            // Label inside the zone's north-west corner, in a pane below the vehicles so it never hides one.
            zonesLayer.addLayer(L.tooltip({
                permanent: true, direction: 'right', className: 'zone-label', interactive: false, opacity: 1,
                pane: 'zoneLabelPane', offset: [2, 12]
            }).setLatLng(poly.getBounds().getNorthWest())
              .setContent(z.name + (z.occupancy > 0 ? ' · ' + z.occupancy + ' inside' : '')));
        }
    }

    function applyVisibility() {
        toggleLayer(zonesLayer, state.visible.zones);
        toggleLayer(routeLayer, state.visible.route);
        toggleLayer(searchLayer, state.visible.search);
        toggleLayer(draftLayer, state.visible.draft);
        refreshRoadGraphOverlay();
        vehicleLayer.redraw();
    }

    function toggleLayer(layer, show) {
        if (show && !map.hasLayer(layer)) layer.addTo(map);
        if (!show && map.hasLayer(layer)) map.removeLayer(layer);
    }

    function pin(latlng, cls, label) {
        return L.marker(latlng, {
            icon: L.divIcon({ className: '', html: '<div class="route-pin ' + cls + '">' + label + '</div>', iconSize: [26, 26], iconAnchor: [13, 13] }),
            keyboard: false, interactive: false, zIndexOffset: 1000
        });
    }

    // ---------------------------------------------------------------- interaction
    map.on('click', function (e) {
        if (state.pickMode) {
            post('pick|' + state.pickMode + '|' + e.latlng.lat.toFixed(7) + '|' + e.latlng.lng.toFixed(7));
            return;
        }
        var id = hitTest(e.containerPoint);
        post('vehicle|' + id);
    });

    var lastHover = 0;
    map.on('mousemove', function (e) {
        if (state.pickMode) return;
        var now = Date.now();
        if (now - lastHover < 60) return;
        lastHover = now;
        map.getContainer().classList.toggle('vehicle-hover', hitTest(e.containerPoint) >= 0);
    });

    map.on('moveend', function () { post('view|' + map.getZoom()); });

    document.addEventListener('keydown', function (e) {
        if (e.key === 'Escape') post('key|Escape');
    });

    function parse(json) {
        return typeof json === 'string' ? JSON.parse(json) : json;
    }

    function boundsOf(b) {
        // b = [south, west, north, east]
        return L.latLngBounds([b[0], b[1]], [b[2], b[3]]);
    }

    // ---------------------------------------------------------------- public API
    var api = {
        ready: function () {
            flushPending();
            return 'ok';
        },

        init: function (configJson) {
            var cfg = parse(configJson);
            state.datasetBounds = cfg.datasetBounds ? boundsOf(cfg.datasetBounds) : null;
            if (cfg.tileUrl && cfg.tileUrl !== OSM_URL) {
                tileLayer.options.attribution = cfg.tileAttribution || OSM_ATTRIBUTION;
                tileLayer.setUrl(cfg.tileUrl);
                console.log('Using tile server ' + cfg.tileUrl);
            }
            api.invalidateSize();
            return 'ok';
        },

        invalidateSize: function () {
            map.invalidateSize({ animate: false });
            var size = map.getSize();
            if (!state.initialViewApplied && state.datasetBounds && size.x > 50 && size.y > 50) {
                state.initialViewApplied = true;
                api.fitDataset();
            }
            vehicleLayer.redraw();
            return size.x + 'x' + size.y;
        },

        fitDataset: function () {
            map.invalidateSize({ animate: false });
            if (!state.datasetBounds) return;
            // Without 3D transforms Leaflet only allows whole zoom levels, and fitBounds always rounds down,
            // which can leave the network at half size. Round up when it would overflow by only a few percent.
            var b = state.datasetBounds, z0 = map.getZoom(), pad = 24;
            var nw = map.project(b.getNorthWest(), z0), se = map.project(b.getSouthEast(), z0);
            var size = map.getSize(), w = Math.max(1, se.x - nw.x), h = Math.max(1, se.y - nw.y);
            var padded = map.getScaleZoom(Math.min((size.x - 2 * pad) / w, (size.y - 2 * pad) / h), z0);
            var unpadded = map.getScaleZoom(Math.min(size.x / w, size.y / h), z0);
            var zoom = Math.floor(padded);
            // One level closer if the network then overflows the map by at most ~5% (2^0.07).
            if (unpadded - zoom >= 0.93) zoom += 1;
            zoom = Math.max(map.getMinZoom(), Math.min(map.getMaxZoom(), zoom));
            map.setView(b.getCenter(), zoom, { animate: false });
        },

        fitVehicles: function () {
            map.invalidateSize({ animate: false });
            if (fleet.n === 0) { api.fitDataset(); return; }
            var s = 90, w = 180, n = -90, e = -180;
            for (var i = 0; i < fleet.n; i++) {
                s = Math.min(s, fleet.lat[i]); n = Math.max(n, fleet.lat[i]);
                w = Math.min(w, fleet.lng[i]); e = Math.max(e, fleet.lng[i]);
            }
            if (n - s < 1e-4 && e - w < 1e-4) map.setView([(s + n) / 2, (w + e) / 2], 17, { animate: false });
            else map.fitBounds([[s, w], [n, e]], { padding: [40, 40], maxZoom: 18, animate: false });
        },

        fitBounds: function (south, west, north, east) {
            map.invalidateSize({ animate: false });
            map.fitBounds([[south, west], [north, east]], { padding: [48, 48], maxZoom: 18, animate: false });
        },

        setView: function (lat, lng, zoom) {
            map.setView([lat, lng], zoom, { animate: false });
        },

        /** Vehicles as {"v":[id,lat,lng,heading,status,source, ...]} (flat, 6 numbers per vehicle). */
        setVehicles: function (json) {
            var data = parse(json);
            var v = data.v || [];
            var n = Math.floor(v.length / 6);
            ensureCapacity(n);
            indexById = {};
            for (var i = 0, k = 0; i < n; i++, k += 6) {
                fleet.id[i] = v[k];
                fleet.lat[i] = v[k + 1];
                fleet.lng[i] = v[k + 2];
                fleet.heading[i] = v[k + 3];
                fleet.status[i] = v[k + 4];
                fleet.source[i] = v[k + 5];
                indexById[v[k]] = i;
            }
            fleet.n = n;
            fleet.projZoom = -1;
            if (state.followId >= 0) {
                var f = indexById[state.followId];
                if (f !== undefined) map.panTo([fleet.lat[f], fleet.lng[f]], { animate: true, duration: 0.25 });
            }
            vehicleLayer.redraw();
            return n;
        },

        selectVehicle: function (id, centre) {
            state.selectedId = id;
            var i = indexById[id];
            if (centre && i !== undefined) {
                var ll = L.latLng(fleet.lat[i], fleet.lng[i]);
                if (map.getZoom() < 16) map.setView(ll, 17, { animate: false });
                else map.panTo(ll, { animate: true, duration: 0.3 });
            }
            vehicleLayer.redraw();
        },

        followVehicle: function (id) {
            state.followId = id;
            if (id >= 0) api.selectVehicle(id, true);
        },

        setLayerVisible: function (name, visible) {
            if (Object.prototype.hasOwnProperty.call(state.visible, name)) {
                state.visible[name] = !!visible;
                applyVisibility();
            }
        },

        /** Road segments as [[[lat,lng],[lat,lng]], ...]. */
        setRoadNetwork: function (json) {
            roadSegments = parse(json);
            if (roadBaseLayer) { map.removeLayer(roadBaseLayer); roadBaseLayer = null; }
            if (roadGraphLayer) { map.removeLayer(roadGraphLayer); roadGraphLayer = null; }
            refreshRoadBase();
            refreshRoadGraphOverlay();
            return roadSegments.length;
        },

        setBasemap: function (mode) {
            state.basemap = mode === 'offline' ? 'offline' : 'osm';
            if (state.basemap === 'osm') {
                if (!map.hasLayer(tileLayer)) tileLayer.addTo(map);
            } else if (map.hasLayer(tileLayer)) {
                map.removeLayer(tileLayer);
            }
            refreshRoadBase();
            updateBanner();
        },

        retryTiles: function () {
            errorsSinceLastLoad = 0;
            diagnosticsRequested = 0;
            tileStats.diagnostic = '';
            if (state.fallbackActive) setFallback(false);
            tileLayer.redraw();
        },

        setTileDiagnostic: function (text) {
            tileStats.diagnostic = text || '';
            updateBanner();
        },

        /** Zones as [{id,name,color,builtIn,occupancy,points:[[lat,lng],...]}]. */
        setZones: function (json) {
            zoneData = parse(json);
            renderZones();
        },

        highlightZone: function (id) {
            highlightedZone = id || null;
            renderZones();
        },

        fitZone: function (id) {
            map.invalidateSize({ animate: false });
            for (var i = 0; i < zoneData.length; i++) {
                if (zoneData[i].id === id) {
                    map.fitBounds(L.latLngBounds(zoneData[i].points), { padding: [80, 80], maxZoom: 18, animate: false });
                }
            }
        },

        /** Draft polygon vertices [[lat,lng],...] while a geofence is being drawn. */
        setDraft: function (json) {
            draftLayer.clearLayers();
            var pts = parse(json) || [];
            if (pts.length >= 3) {
                draftLayer.addLayer(L.polygon(pts, { pane: 'draftPane', color: COLORS.draft, weight: 2, fillOpacity: 0.12, dashArray: '5 4', interactive: false }));
            } else if (pts.length === 2) {
                draftLayer.addLayer(L.polyline(pts, { pane: 'draftPane', color: COLORS.draft, weight: 2, dashArray: '5 4', interactive: false }));
            }
            for (var i = 0; i < pts.length; i++) {
                draftLayer.addLayer(L.circleMarker(pts[i], { pane: 'draftPane', radius: 5, color: COLORS.draft, weight: 2, fillColor: '#ffffff', fillOpacity: 1, interactive: false }));
            }
        },

        /**
         * Route as {path:[[lat,lng]...], origin:[lat,lng], destination:[lat,lng],
         * originSnap:[lat,lng], destinationSnap:[lat,lng], fit:boolean}. Any field may be missing.
         */
        setRoute: function (json) {
            routeLayer.clearLayers();
            var r = parse(json) || {};
            if (r.path && r.path.length >= 2) {
                routeLayer.addLayer(L.polyline(r.path, { pane: 'routePane', color: COLORS.routeCasing, weight: 9, opacity: 1, lineCap: 'round', lineJoin: 'round', interactive: false }));
                routeLayer.addLayer(L.polyline(r.path, { pane: 'routePane', color: COLORS.route, weight: 5.5, opacity: 0.95, lineCap: 'round', lineJoin: 'round', interactive: false }));
            }
            var dashed = { pane: 'routePane', color: '#5f6368', weight: 2, dashArray: '2 5', interactive: false };
            if (r.origin && r.originSnap) routeLayer.addLayer(L.polyline([r.origin, r.originSnap], dashed));
            if (r.destination && r.destinationSnap) routeLayer.addLayer(L.polyline([r.destination, r.destinationSnap], dashed));
            if (r.origin) routeLayer.addLayer(pin(r.origin, 'origin', 'A'));
            if (r.destination) routeLayer.addLayer(pin(r.destination, 'destination', 'B'));
            if (r.fit && r.path && r.path.length >= 2) {
                map.invalidateSize({ animate: false });
                var b = L.latLngBounds(r.path);
                if (r.origin) b.extend(r.origin);
                if (r.destination) b.extend(r.destination);
                map.fitBounds(b, { padding: [70, 70], maxZoom: 18, animate: false });
            }
        },

        /** Search area: {type:'circle', center:[lat,lng], radius:m} | {type:'box', bounds:[s,w,n,e]} | {type:'point', center:[lat,lng]}. */
        setSearchArea: function (json) {
            searchLayer.clearLayers();
            var a = parse(json);
            if (!a) return;
            var style = { pane: 'searchPane', color: COLORS.searchStroke, weight: 2, dashArray: '6 5', fillColor: COLORS.searchStroke, fillOpacity: 0.07, interactive: false };
            if (a.type === 'circle') {
                searchLayer.addLayer(L.circle(a.center, L.extend({ radius: a.radius }, style)));
                searchLayer.addLayer(L.circleMarker(a.center, { pane: 'searchPane', radius: 4, color: COLORS.searchStroke, fillColor: '#ffffff', fillOpacity: 1, weight: 2, interactive: false }));
            } else if (a.type === 'box') {
                searchLayer.addLayer(L.rectangle(boundsOf(a.bounds), style));
            } else if (a.type === 'point') {
                searchLayer.addLayer(L.circleMarker(a.center, { pane: 'searchPane', radius: 5, color: COLORS.searchStroke, fillColor: '#ffffff', fillOpacity: 1, weight: 2, interactive: false }));
            }
            if (a.fit) {
                map.invalidateSize({ animate: false });
                var layers = searchLayer.getLayers();
                if (layers.length && layers[0].getBounds) map.fitBounds(layers[0].getBounds(), { padding: [60, 60], maxZoom: 18, animate: false });
            }
        },

        setSearchResults: function (json) {
            var ids = parse(json) || [];
            state.searchHighlight = {};
            for (var i = 0; i < ids.length; i++) state.searchHighlight[ids[i]] = true;
            vehicleLayer.redraw();
        },

        setPickMode: function (mode) {
            state.pickMode = mode || null;
            map.getContainer().classList.toggle('pick-mode', !!state.pickMode);
            if (state.pickMode) map.doubleClickZoom.disable(); else map.doubleClickZoom.enable();
        },

        /** Container pixel position of a vehicle as "x,y", or "" if it is not on the map. */
        vehiclePoint: function (id) {
            var i = indexById[id];
            if (i === undefined) return '';
            var p = map.latLngToContainerPoint([fleet.lat[i], fleet.lng[i]]);
            return p.x + ',' + p.y;
        },

        /** Current view as [south, west, north, east]. */
        getViewBounds: function () {
            var b = map.getBounds();
            return JSON.stringify([b.getSouth(), b.getWest(), b.getNorth(), b.getEast()]);
        },

        getStatus: function () {
            var c = map.getCenter();
            var size = map.getSize();
            var pendingTiles = 0;
            if (map.hasLayer(tileLayer)) {
                var tiles = tileLayer.getContainer() ? tileLayer.getContainer().querySelectorAll('img.leaflet-tile') : [];
                for (var i = 0; i < tiles.length; i++) {
                    if (tiles[i].className.indexOf('leaflet-tile-loaded') < 0 && !tiles[i]._tesseraFailed) pendingTiles++;
                }
            }
            return JSON.stringify({
                zoom: map.getZoom(), center: [c.lat, c.lng], size: [size.x, size.y],
                basemap: state.basemap, fallback: state.fallbackActive,
                tiles: { requested: tileStats.requested, loaded: tileStats.loaded, errors: tileStats.errors, retries: tileStats.retries, failed: tileStats.failed, pending: pendingTiles,
                         avgLoadMs: tileStats.loaded ? Math.round(tileStats.loadMsTotal / tileStats.loaded) : 0, maxLoadMs: tileStats.loadMsMax },
                vehicles: fleet.n, lastDrawMs: state.lastDrawMs,
                any3d: L.Browser.any3d, dpr: window.devicePixelRatio || 1
            });
        }
    };

    window.TesseraMap = api;
    post('ready|' + L.version);
})();
