package com.flooring.salesportal.dashboard;

import com.flooring.salesportal.common.api.ApiResponse;
import com.flooring.salesportal.common.api.ErrorDetail;
import com.flooring.salesportal.common.api.PaginationMeta;
import com.flooring.salesportal.common.error.ErrorCode;
import com.flooring.salesportal.common.error.ValidationException;
import com.flooring.salesportal.common.session.RequestContext;
import com.flooring.salesportal.common.session.RequestContextGuard;
import com.flooring.salesportal.dashboard.dto.DashboardOrderQuery;
import com.flooring.salesportal.dashboard.dto.DashboardOrderRowResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class DashboardOrderService {

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MIN_PAGE_SIZE = 1;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MIN_WEEK_YEAR = 2000;
    private static final int MAX_WEEK_YEAR = 2100;
    private static final int MIN_WEEK_NUMBER = 1;
    private static final int MAX_WEEK_NUMBER = 53;

    private static final Set<String> ALLOWED_STATUSES = Set.of(
            "LEAD", "NEW_ACHIEVED_SALE", "FOLLOW_UP", "ACCEPTED", "LAID", "CANCELLED");

    private final RequestContextGuard requestContextGuard;
    private final DashboardOrderRepository dashboardOrderRepository;

    public DashboardOrderService(RequestContextGuard requestContextGuard,
                                 DashboardOrderRepository dashboardOrderRepository) {
        this.requestContextGuard = requestContextGuard;
        this.dashboardOrderRepository = dashboardOrderRepository;
    }

    public ApiResponse<List<DashboardOrderRowResponse>> list(String slug,
                                                             DashboardOrderQuery query,
                                                             HttpServletRequest httpRequest) {
        RequestContext ctx = requestContextGuard.requireStandardProtected(slug, httpRequest);

        int page = query.page() == null ? DEFAULT_PAGE : query.page();
        int pageSize = query.pageSize() == null ? DEFAULT_PAGE_SIZE : query.pageSize();
        String[] statusRaw = query.status();
        Integer weekYear = query.weekYear();
        Integer weekNumber = query.weekNumber();
        String search = (query.search() == null || query.search().isBlank()) ? null : query.search();

        List<ErrorDetail> errors = new ArrayList<>();
        if (page < 1) {
            errors.add(detail("page", "Must be greater than or equal to 1."));
        }
        if (pageSize < MIN_PAGE_SIZE || pageSize > MAX_PAGE_SIZE) {
            errors.add(detail("page_size", "Must be between 1 and 100."));
        }

        // Resolve status: null (not provided) → no filter; >1 value (repeated or comma-split)
        // → single VALIDATION_FAILED on status; exactly 1 value → enum membership check.
        String status = null;
        if (statusRaw != null && statusRaw.length > 0) {
            if (statusRaw.length > 1) {
                errors.add(detail("status",
                        "Must be a single value: one of LEAD, NEW_ACHIEVED_SALE, FOLLOW_UP, ACCEPTED, LAID, CANCELLED."));
            } else {
                String single = statusRaw[0];
                if (!ALLOWED_STATUSES.contains(single)) {
                    errors.add(detail("status",
                            "Must be one of LEAD, NEW_ACHIEVED_SALE, FOLLOW_UP, ACCEPTED, LAID, CANCELLED."));
                } else {
                    status = single;
                }
            }
        }

        if (weekYear != null && (weekYear < MIN_WEEK_YEAR || weekYear > MAX_WEEK_YEAR)) {
            errors.add(detail("week_year", "Must be between 2000 and 2100."));
        }
        if (weekNumber != null && (weekNumber < MIN_WEEK_NUMBER || weekNumber > MAX_WEEK_NUMBER)) {
            errors.add(detail("week_number", "Must be between 1 and 53."));
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(ErrorCode.VALIDATION_FAILED.defaultMessage(), errors);
        }

        String escapedSearch = search == null ? null : escapeForLike(search);

        long totalItems = dashboardOrderRepository.countRows(
                ctx.businessId(), ctx.storeId(), status, weekYear, weekNumber, escapedSearch);

        int totalPages = totalItems == 0
                ? 0
                : (int) ((totalItems + pageSize - 1) / pageSize);

        List<DashboardOrderRowResponse> rows;
        if (totalItems == 0) {
            rows = List.of();
        } else {
            // long to avoid int overflow when (page - 1) * pageSize exceeds Integer.MAX_VALUE.
            long offset = ((long) (page - 1)) * (long) pageSize;
            rows = dashboardOrderRepository.findRows(
                    ctx.businessId(), ctx.storeId(),
                    status, weekYear, weekNumber, escapedSearch,
                    pageSize, offset);
        }

        return ApiResponse.page(rows, new PaginationMeta(page, pageSize, totalItems, totalPages));
    }

    // Escape `\` first so subsequent escape prefixes are not themselves re-escaped.
    // Pairs with `ESCAPE '\'` on every ILIKE clause in DashboardOrderRepository.
    private static String escapeForLike(String input) {
        return input
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private static ErrorDetail detail(String field, String message) {
        return new ErrorDetail(null, field, message);
    }
}
