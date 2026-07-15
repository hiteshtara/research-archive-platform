package edu.bu.archive.adapter.in.web;

import edu.bu.archive.application.port.in.IrbQueryUseCase;
import edu.bu.archive.dto.DashboardDto;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final IrbQueryUseCase irbQueryUseCase;

    public DashboardController(IrbQueryUseCase irbQueryUseCase) {
        this.irbQueryUseCase = irbQueryUseCase;
    }

    @GetMapping
    public DashboardDto dashboard() {
        return new DashboardDto(
                irbQueryUseCase.count(),
                0,
                0,
                0,
                0,
                0
        );
    }
}
