package com.flooring.salesportal.dashboard;

import com.flooring.salesportal.common.api.ApiResponse;
import com.flooring.salesportal.dashboard.dto.DashboardOrderQuery;
import com.flooring.salesportal.dashboard.dto.DashboardOrderRowResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/{slug}/orders")
public class DashboardOrderController {

    private final DashboardOrderService dashboardOrderService;

    public DashboardOrderController(DashboardOrderService dashboardOrderService) {
        this.dashboardOrderService = dashboardOrderService;
    }

    @GetMapping
    public ApiResponse<List<DashboardOrderRowResponse>> list(
            @PathVariable String slug,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "page_size", required = false) Integer pageSize,
            @RequestParam(name = "status", required = false) String[] status,
            @RequestParam(name = "week_year", required = false) Integer weekYear,
            @RequestParam(name = "week_number", required = false) Integer weekNumber,
            @RequestParam(name = "search", required = false) String search,
            HttpServletRequest httpRequest) {

        DashboardOrderQuery query = new DashboardOrderQuery(
                page, pageSize, status, weekYear, weekNumber, search);

        return dashboardOrderService.list(slug, query, httpRequest);
    }
}
