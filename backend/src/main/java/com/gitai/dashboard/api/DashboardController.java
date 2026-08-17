package com.gitai.dashboard.api;

import com.gitai.dashboard.service.DashboardService;
import com.gitai.dashboard.auth.AccessPolicy;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;

@RestController
@RequestMapping("/api")
public class DashboardController {
    private final DashboardService dashboardService;
    private final AccessPolicy access;

    public DashboardController(DashboardService dashboardService, AccessPolicy access) {
        this.dashboardService = dashboardService;
        this.access = access;
    }

    @GetMapping("/health")
    public java.util.Map<String, String> health() {
        return java.util.Map.of("status", "ok");
    }

    @GetMapping("/filters")
    public DashboardDtos.FilterOptions filters() {
        return dashboardService.filters(access.allowedDepartmentId());
    }

    @GetMapping("/hierarchy")
    public java.util.List<DashboardDtos.GroupNode> hierarchy() {
        return dashboardService.hierarchy(access.allowedDepartmentId());
    }

    @GetMapping("/dashboard")
    public DashboardDtos.DashboardResponse dashboard(
            @RequestParam(required = false) Long departmentId,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long repositoryId,
            @RequestParam(required = false) Long groupId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid date range: from must not be after to");
        }
        Long scopedDepartmentId = access.dashboardDepartment(departmentId, projectId, groupId, repositoryId);
        return dashboardService.dashboard(scopedDepartmentId, projectId, repositoryId, groupId, from, to);
    }
}
