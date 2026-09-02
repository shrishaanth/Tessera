package com.tessera.fleet.model;

/**
 * One entry in a vehicle's recent status history (FR-1.4).
 *
 * @param status     the status the vehicle moved into
 * @param epochMillis when the transition was observed
 */
public record StatusChange(VehicleStatus status, long epochMillis) { }
