package com.flooring.salesportal.catalog;

import com.flooring.salesportal.catalog.dto.AvailableChargeResponse;
import com.flooring.salesportal.catalog.dto.AvailableProductResponse;
import com.flooring.salesportal.common.api.ApiResponse;
import com.flooring.salesportal.common.api.ErrorDetail;
import com.flooring.salesportal.common.api.PaginationMeta;
import com.flooring.salesportal.common.error.ErrorCode;
import com.flooring.salesportal.common.error.NotFoundException;
import com.flooring.salesportal.common.error.ValidationException;
import com.flooring.salesportal.common.session.RequestContext;
import com.flooring.salesportal.common.session.RequestContextGuard;
import com.flooring.salesportal.order.SalesOrder;
import com.flooring.salesportal.order.SalesOrderRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Chunk 3 catalog search (D.1 / D.2): the products and charges a salesperson may add to an order.
 *
 * <p>Both endpoints share one gate order: standard-protected guard → validate {@code orderId}
 * (positive int, else VALIDATION_FAILED on {@code order_id}) → validate {@code page}/{@code
 * page_size} → scoped order lookup (missing / cross-store / cross-business → 404
 * {@code ORDER_NOT_FOUND}, no existence leak) → read the order's flooring type → catalog query
 * scoped to {@code session.store_id} + flooring type + active only, ordered by {@code code} asc.
 * Both are GET reads allowed regardless of order status (no LAID gate). Cost is never returned
 * (conventions §10). Read-only transaction so the order lookup, count, and page see one snapshot.
 */
@Service
public class OrderCatalogService {

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MIN_PAGE = 1;
    private static final int MIN_PAGE_SIZE = 1;
    private static final int MAX_PAGE_SIZE = 100;

    private final RequestContextGuard requestContextGuard;
    private final SalesOrderRepository salesOrderRepository;
    private final AvailableProductRepository availableProductRepository;
    private final AvailableChargeRepository availableChargeRepository;

    public OrderCatalogService(RequestContextGuard requestContextGuard,
                               SalesOrderRepository salesOrderRepository,
                               AvailableProductRepository availableProductRepository,
                               AvailableChargeRepository availableChargeRepository) {
        this.requestContextGuard = requestContextGuard;
        this.salesOrderRepository = salesOrderRepository;
        this.availableProductRepository = availableProductRepository;
        this.availableChargeRepository = availableChargeRepository;
    }

    @Transactional(readOnly = true)
    public ApiResponse<List<AvailableProductResponse>> listProducts(String slug,
                                                                    String orderIdRaw,
                                                                    Integer page,
                                                                    Integer pageSize,
                                                                    String search,
                                                                    HttpServletRequest httpRequest) {
        RequestContext ctx = requestContextGuard.requireStandardProtected(slug, httpRequest);
        long orderId = parseOrderId(orderIdRaw);
        Paging paging = validatePaging(page, pageSize);
        SalesOrder order = loadOrderInScope(orderId, ctx);

        String flooringType = order.getFlooringType();
        String escapedSearch = escapedSearchOrNull(search);

        long totalItems = availableProductRepository.countRows(ctx.storeId(), flooringType, escapedSearch);
        int totalPages = totalPages(totalItems, paging.pageSize());

        List<AvailableProductResponse> rows = totalItems == 0
                ? List.of()
                : availableProductRepository.findRows(
                        ctx.storeId(), flooringType, escapedSearch, paging.pageSize(), offset(paging));

        return ApiResponse.page(rows, new PaginationMeta(paging.page(), paging.pageSize(), totalItems, totalPages));
    }

    @Transactional(readOnly = true)
    public ApiResponse<List<AvailableChargeResponse>> listCharges(String slug,
                                                                  String orderIdRaw,
                                                                  Integer page,
                                                                  Integer pageSize,
                                                                  String search,
                                                                  HttpServletRequest httpRequest) {
        RequestContext ctx = requestContextGuard.requireStandardProtected(slug, httpRequest);
        long orderId = parseOrderId(orderIdRaw);
        Paging paging = validatePaging(page, pageSize);
        SalesOrder order = loadOrderInScope(orderId, ctx);

        String flooringType = order.getFlooringType();
        String escapedSearch = escapedSearchOrNull(search);

        long totalItems = availableChargeRepository.countRows(ctx.storeId(), flooringType, escapedSearch);
        int totalPages = totalPages(totalItems, paging.pageSize());

        List<AvailableChargeResponse> rows = totalItems == 0
                ? List.of()
                : availableChargeRepository.findRows(
                        ctx.storeId(), flooringType, escapedSearch, paging.pageSize(), offset(paging));

        return ApiResponse.page(rows, new PaginationMeta(paging.page(), paging.pageSize(), totalItems, totalPages));
    }

    /**
     * Resource-level tenant + store scoping (read, no lock — GET). An order outside the session's
     * {@code (business_id, store_id)} returns empty, mapped to 404 {@code ORDER_NOT_FOUND} with no
     * cross-tenant existence leak (conventions §4 / §9).
     */
    private SalesOrder loadOrderInScope(long orderId, RequestContext ctx) {
        return salesOrderRepository
                .findByOrderIdAndBusinessIdAndStoreId(orderId, ctx.businessId(), ctx.storeId())
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND, "Order not found."));
    }

    // Mirrors OrderService.parseOrderId: non-numeric / non-positive both produce a single
    // VALIDATION_FAILED on field order_id rather than a 404 or a type-mismatch.
    private static long parseOrderId(String raw) {
        if (raw != null && !raw.isBlank()) {
            try {
                long parsed = Long.parseLong(raw);
                if (parsed > 0) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                // fall through to a single VALIDATION_FAILED below
            }
        }
        throw new ValidationException(
                ErrorCode.VALIDATION_FAILED.defaultMessage(),
                List.of(new ErrorDetail(null, "order_id", "Must be a positive integer.")));
    }

    private static Paging validatePaging(Integer pageRaw, Integer pageSizeRaw) {
        int page = pageRaw == null ? DEFAULT_PAGE : pageRaw;
        int pageSize = pageSizeRaw == null ? DEFAULT_PAGE_SIZE : pageSizeRaw;

        List<ErrorDetail> errors = new ArrayList<>();
        if (page < MIN_PAGE) {
            errors.add(new ErrorDetail(null, "page", "Must be greater than or equal to 1."));
        }
        if (pageSize < MIN_PAGE_SIZE || pageSize > MAX_PAGE_SIZE) {
            errors.add(new ErrorDetail(null, "page_size", "Must be between 1 and 100."));
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(ErrorCode.VALIDATION_FAILED.defaultMessage(), errors);
        }
        return new Paging(page, pageSize);
    }

    private static int totalPages(long totalItems, int pageSize) {
        return totalItems == 0
                ? 0
                : (int) ((totalItems + pageSize - 1) / pageSize);
    }

    // long to avoid int overflow when (page - 1) * pageSize exceeds Integer.MAX_VALUE.
    private static long offset(Paging paging) {
        return ((long) (paging.page() - 1)) * (long) paging.pageSize();
    }

    private static String escapedSearchOrNull(String search) {
        if (search == null || search.isBlank()) {
            return null;
        }
        return escapeForLike(search);
    }

    // Escape `\` first so subsequent escape prefixes are not themselves re-escaped.
    // Pairs with `ESCAPE '\'` on every ILIKE clause in the catalog repositories — matches the
    // dashboard search behaviour so `%` and `_` are treated as literal characters.
    private static String escapeForLike(String input) {
        return input
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private record Paging(int page, int pageSize) {
    }
}
