package com.tessera.risk.reporting.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tessera.risk.reporting.model.DataSourceInfo;

/**
 * Data-source transparency (FR-6.1).
 *
 * <p>Served by the API rather than written into the dashboard's markup so the
 * disclosure travels with the system producing the data. A statement hard-coded in
 * a template can drift out of step with reality; one served next to the data cannot
 * be forgotten when the data changes.
 */
@RestController
@RequestMapping("/api/meta")
public class MetaController {

    private final String areaName;

    public MetaController(@Value("${tessera.area-name:}") String areaName) {
        this.areaName = areaName;
    }

    @GetMapping("/data-source")
    public DataSourceInfo dataSource() {
        return DataSourceInfo.simulated(areaName);
    }
}
