package com.tessera.fleet.live;

import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.tessera.fleet.model.Vehicle;
import com.tessera.fleet.web.ws.LiveWebSocketHandler;

/**
 * Drives the live map: on every tick it lets the live layer record any
 * time-driven status transitions (chiefly vehicles going OFFLINE) and then pushes
 * a full fleet snapshot to connected dispatcher clients. Tick period is kept well
 * under the 2-second UI freshness bound (FR-1.2 / NFR-2).
 */
@Service
public class LiveBroadcastService {

    private final LiveFleetService liveFleet;
    private final LiveWebSocketHandler webSocket;

    public LiveBroadcastService(LiveFleetService liveFleet, LiveWebSocketHandler webSocket) {
        this.liveFleet = liveFleet;
        this.webSocket = webSocket;
    }

    @Scheduled(fixedDelayString = "${tessera.broadcast-millis}")
    public void tick() {
        liveFleet.sweepStatusTransitions();
        List<Vehicle> snapshot = liveFleet.allVehicles();
        webSocket.broadcastFleet(snapshot);
    }
}
