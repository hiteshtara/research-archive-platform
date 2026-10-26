package edu.bu.archive.adapter.in.web;

import edu.bu.archive.adapter.in.web.dto.DashboardDto;
import edu.bu.archive.application.service.DashboardService;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
@Transactional(readOnly = true)
public class DashboardController {

    private final DashboardService service;

    public DashboardController(DashboardService service) {
        this.service = service;
    }

    @GetMapping
    public DashboardDto dashboard() {
        return service.counts();
    }
}
