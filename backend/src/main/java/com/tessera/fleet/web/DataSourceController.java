package com.tessera.fleet.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tessera.fleet.transparency.DataSourceInfo;
import com.tessera.fleet.transparency.DataSourceService;

/**
 * FR-7: lists every major external data source with its real-world provenance.
 * Substitute sources carry a non-empty {@code disclosure}; the UI must render it
 * plainly and must not visually minimise it (FR-7.2).
 */
@RestController
@RequestMapping("/api/data-sources")
public class DataSourceController {

    private final DataSourceService dataSourceService;

    public DataSourceController(DataSourceService dataSourceService) {
        this.dataSourceService = dataSourceService;
    }

    @GetMapping
    public List<DataSourceInfo> list() {
        return dataSourceService.list();
    }
}
