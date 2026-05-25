package com.flooring.salesportal.order;

import com.flooring.salesportal.common.api.ApiResponse;
import com.flooring.salesportal.common.api.ErrorDetail;
import com.flooring.salesportal.common.error.ErrorCode;
import com.flooring.salesportal.common.error.NotFoundException;
import com.flooring.salesportal.common.error.ValidationException;
import com.flooring.salesportal.common.session.RequestContext;
import com.flooring.salesportal.common.session.RequestContextGuard;
import com.flooring.salesportal.order.dto.OrderStatusUpdateRequest;
import com.flooring.salesportal.order.dto.OrderStatusUpdateResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Service
public class OrderStatusService {

    private static final String LAID = "LAID";

    private static final Set<String> ALLOWED_STATUSES = Set.of(
            "LEAD", "NEW_ACHIEVED_SALE", "FOLLOW_UP", "ACCEPTED", LAID, "CANCELLED");

    private static final String ORDER_STATUS_ENUM_MESSAGE =
            "Must be one of LEAD, NEW_ACHIEVED_SALE, FOLLOW_UP, ACCEPTED, LAID, CANCELLED.";

    private final RequestContextGuard requestContextGuard;
    private final OrderStatusRepository orderStatusRepository;

    public OrderStatusService(RequestContextGuard requestContextGuard,
                              OrderStatusRepository orderStatusRepository) {
        this.requestContextGuard = requestContextGuard;
        this.orderStatusRepository = orderStatusRepository;
    }

    @Transactional
    public ApiResponse<OrderStatusUpdateResponse> updateStatus(String slug,
                                                               String orderIdRaw,
                                                               OrderStatusUpdateRequest body,
                                                               HttpServletRequest httpRequest) {
        // 1. Standard-protected guard first: 401 / 403 / generic 404 for slug/session.
        RequestContext ctx = requestContextGuard.requireStandardProtected(slug, httpRequest);

        // 2. Validate the path variable as a positive integer (manual so non-numeric and
        //    non-positive both route through VALIDATION_FAILED with field "order_id").
        long orderId = parseOrderId(orderIdRaw);

        // 3. Validate the request body.
        String newStatus = parseAndValidateStatus(body);

        // 4. Row-lock the order scoped to the session business + store.
        OrderStatusRepository.CurrentOrderSnapshot current = orderStatusRepository
                .findForUpdate(orderId, ctx.businessId(), ctx.storeId())
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND, "Order not found."));

        // 5. Same-status no-op: no write, response carries the existing updated_at.
        if (current.orderStatus().equals(newStatus)) {
            OrderStatusUpdateResponse data = new OrderStatusUpdateResponse(
                    current.orderId(),
                    current.orderNumber(),
                    current.orderStatus(),
                    current.orderStatus(),
                    LAID.equals(current.orderStatus()),
                    current.updatedAt());
            return ApiResponse.ok(data, "Order status updated.");
        }

        // 6. Real update: write the new status and refresh updated_at explicitly.
        LocalDateTime newUpdatedAt = orderStatusRepository.updateStatus(
                orderId, ctx.businessId(), ctx.storeId(), newStatus);

        OrderStatusUpdateResponse data = new OrderStatusUpdateResponse(
                current.orderId(),
                current.orderNumber(),
                newStatus,
                current.orderStatus(),
                LAID.equals(newStatus),
                newUpdatedAt);
        return ApiResponse.ok(data, "Order status updated.");
    }

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

    private static String parseAndValidateStatus(OrderStatusUpdateRequest body) {
        String value = body == null ? null : body.orderStatus();
        if (value == null) {
            throw new ValidationException(
                    ErrorCode.VALIDATION_FAILED.defaultMessage(),
                    List.of(new ErrorDetail(null, "order_status", "Required.")));
        }
        if (!ALLOWED_STATUSES.contains(value)) {
            throw new ValidationException(
                    ErrorCode.VALIDATION_FAILED.defaultMessage(),
                    List.of(new ErrorDetail(null, "order_status", ORDER_STATUS_ENUM_MESSAGE)));
        }
        return value;
    }
}
