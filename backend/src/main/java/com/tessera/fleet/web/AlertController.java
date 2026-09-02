package com.tessera.fleet.web;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.tessera.fleet.alert.Alert;
import com.tessera.fleet.alert.AlertService;

/** Dispatcher alert / exception feed (FR-3.5 in Phase 2). */
@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private final AlertService alertService;

    public AlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    @GetMapping
    public List<Alert> list(@RequestParam(name = "includeAcknowledged", defaultValue = "false")
                            boolean includeAcknowledged) {
        return alertService.list(includeAcknowledged);
    }

    @PostMapping("/{alertId}/ack")
    public ResponseEntity<Alert> acknowledge(@PathVariable String alertId) {
        return alertService.acknowledge(alertId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
