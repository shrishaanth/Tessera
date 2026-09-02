package com.tessera.fleet.transparency;

/**
 * One entry in the data-source disclosure panel (FR-7).
 *
 * @param key        stable id
 * @param name       display name
 * @param provider   who provides the data
 * @param purpose    what the system uses it for
 * @param role       {@link Role#PRODUCTION} for a real production source,
 *                   {@link Role#SUBSTITUTE} for a stand-in that must not be
 *                   visually minimised (FR-7.2)
 * @param disclosure plain-language provenance statement; non-empty for substitutes
 * @param active     whether this source is currently in use
 */
public record DataSourceInfo(
        String key,
        String name,
        String provider,
        String purpose,
        Role role,
        String disclosure,
        boolean active) {

    public enum Role { PRODUCTION, SUBSTITUTE }
}
