package com.tessera.fleet.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.tessera.fleet.reporting.ReportFilter;
import com.tessera.fleet.reporting.ReportModels.DwellReport;
import com.tessera.fleet.reporting.ReportModels.FilterOptions;
import com.tessera.fleet.reporting.ReportModels.OnTimeReport;
import com.tessera.fleet.reporting.ReportModels.Readiness;
import com.tessera.fleet.reporting.ReportingService;

/**
 * Historical performance reporting (FR-4). Request/response, not real-time
 * (SRS §5.3). {@code from}/{@code to} are epoch millis; omit for a trailing
 * 30-day window.
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportingService reporting;

    public ReportController(ReportingService reporting) {
        this.reporting = reporting;
    }

    @GetMapping("/on-time")
    public OnTimeReport onTime(
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            @RequestParam(required = false) String route,
            @RequestParam(required = false) String driver,
            @RequestParam(required = false) String siteId) {
        return reporting.onTime(new ReportFilter(from, to, blankToNull(route),
                blankToNull(driver), blankToNull(siteId)));
    }

    @GetMapping("/dwell")
    public DwellReport dwell(
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            @RequestParam(required = false) String siteId) {
        return reporting.dwell(new ReportFilter(from, to, null, null, blankToNull(siteId)));
    }

    @GetMapping("/readiness")
    public Readiness readiness() {
        return reporting.readiness();
    }

    @GetMapping("/filters")
    public FilterOptions filters() {
        return reporting.filterOptions();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
