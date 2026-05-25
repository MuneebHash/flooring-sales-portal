package com.flooring.salesportal.dashboard.dto;

public record DashboardOrderQuery(
        Integer page,
        Integer pageSize,
        String[] status,
        Integer weekYear,
        Integer weekNumber,
        String search
) {
}
