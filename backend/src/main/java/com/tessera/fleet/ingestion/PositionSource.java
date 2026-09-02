package com.tessera.fleet.ingestion;

import java.util.List;

import com.tessera.fleet.model.PositionReport;

/**
 * A source of live vehicle position reports.
 *
 * <p>Two implementations ship in Phase 1: a deterministic {@link SimulatedPositionSource}
 * (used by all automated tests, no network) and a {@link GtfsRealtimePositionSource}
 * that reads a real public GTFS-Realtime feed. Per SRS §2.6 and FR-7, whichever
 * source is active must disclose its real-world provenance in the product; the
 * {@link #isSubstitute()} / {@link #disclosure()} pair carries that text.
 */
public interface PositionSource {

    /** Stable machine id, e.g. {@code "simulator"} or {@code "gtfs-realtime"}. */
    String id();

    /** Human-readable name for the data-sources panel (FR-7.1). */
    String displayName();

    /**
     * {@code true} when this feed is <em>not</em> production fleet telematics and
     * must be plainly disclosed as a stand-in (FR-7.2): the simulator, or a public
     * transit feed used in place of a real fleet's own feed.
     */
    boolean isSubstitute();

    /** Plain-language provenance statement shown verbatim in the product (FR-7.2). */
    String disclosure();

    /**
     * The reports available since the last call. May be empty (e.g. a feed polled
     * faster than it updates). Never {@code null}.
     */
    List<PositionReport> poll();
}
