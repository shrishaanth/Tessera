package com.tessera.fleet.search;

import java.util.List;

import org.springframework.stereotype.Service;

import com.tessera.fleet.durable.DurableStore;
import com.tessera.fleet.geofence.Site;

/**
 * Fuzzy search of known customer site names (FR-6.3). Delegates to the durable
 * store: PostgreSQL full-text + {@code pg_trgm} in production (SRS §3.2), an
 * in-memory trigram match otherwise.
 */
@Service
public class SiteSearchService {

    private final DurableStore durableStore;

    public SiteSearchService(DurableStore durableStore) {
        this.durableStore = durableStore;
    }

    public List<Site> search(String query, int limit) {
        int n = Math.min(Math.max(1, limit), 20);
        return durableStore.searchSites(query, n).stream().map(Site::fromRecord).toList();
    }
}
