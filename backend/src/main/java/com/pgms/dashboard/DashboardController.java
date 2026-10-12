package com.pgms.dashboard;

import com.pgms.security.AccessGuard;
import com.pgms.security.CurrentUser;
import com.pgms.user.RoleName;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
@Tag(name = "Dashboard")
public class DashboardController {

    private final DashboardService dashboardService;
    private final AccessGuard accessGuard;

    public DashboardController(DashboardService dashboardService, AccessGuard accessGuard) {
        this.dashboardService = dashboardService;
        this.accessGuard = accessGuard;
    }

    /**
     * Role-specific dashboard: operations KPIs for admins/managers (optionally for one property),
     * a work queue for staff, and personal dues/stay details for tenants.
     */
    @GetMapping
    @Operation(summary = "Dashboard for the current user's role")
    public Object dashboard(@RequestParam(required = false) Long propertyId) {
        CurrentUser user = accessGuard.currentUser();
        if (user.is(RoleName.TENANT)) {
            return dashboardService.tenant();
        }
        if (user.is(RoleName.STAFF)) {
            return dashboardService.staff();
        }
        return dashboardService.operations(dashboardService.scopeKey(propertyId), propertyId);
    }
}
