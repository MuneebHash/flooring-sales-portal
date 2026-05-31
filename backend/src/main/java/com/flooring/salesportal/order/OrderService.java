package com.flooring.salesportal.order;

import com.flooring.salesportal.common.api.ErrorDetail;
import com.flooring.salesportal.common.error.ErrorCode;
import com.flooring.salesportal.common.error.NotFoundException;
import com.flooring.salesportal.common.error.ValidationException;
import com.flooring.salesportal.common.session.RequestContext;
import com.flooring.salesportal.common.session.RequestContextGuard;
import com.flooring.salesportal.order.dto.AddressDto;
import com.flooring.salesportal.order.dto.CreateOrderRequest;
import com.flooring.salesportal.order.dto.CustomerDto;
import com.flooring.salesportal.order.dto.OrderHeaderResponse;
import com.flooring.salesportal.order.dto.OrderWorkspaceResponse;
import com.flooring.salesportal.order.dto.PersistedFinancialsDto;
import com.flooring.salesportal.store.Store;
import com.flooring.salesportal.store.StoreRepository;
import com.flooring.salesportal.user.AppUser;
import com.flooring.salesportal.user.AppUserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.IsoFields;
import java.util.List;

@Service
public class OrderService {

    private static final String FLOORING_SOFT = "SOFT";
    private static final String FLOORING_HARD = "HARD";
    private static final String STATUS_LEAD = "LEAD";
    private static final String STATUS_LAID = "LAID";
    private static final String ADDRESS_INSTALLATION = "INSTALLATION";
    private static final String ADDRESS_BILLING = "BILLING";

    private final RequestContextGuard requestContextGuard;
    private final StoreRepository storeRepository;
    private final AppUserRepository appUserRepository;
    private final OrderCreateRepository orderCreateRepository;
    private final SalesOrderRepository salesOrderRepository;
    private final OrderCustomerRepository orderCustomerRepository;
    private final OrderAddressRepository orderAddressRepository;

    public OrderService(RequestContextGuard requestContextGuard,
                        StoreRepository storeRepository,
                        AppUserRepository appUserRepository,
                        OrderCreateRepository orderCreateRepository,
                        SalesOrderRepository salesOrderRepository,
                        OrderCustomerRepository orderCustomerRepository,
                        OrderAddressRepository orderAddressRepository) {
        this.requestContextGuard = requestContextGuard;
        this.storeRepository = storeRepository;
        this.appUserRepository = appUserRepository;
        this.orderCreateRepository = orderCreateRepository;
        this.salesOrderRepository = salesOrderRepository;
        this.orderCustomerRepository = orderCustomerRepository;
        this.orderAddressRepository = orderAddressRepository;
    }

    /**
     * POST /orders. The advisory lock, MAX(order_sequence_number)+1, and INSERT all run inside this
     * single {@code @Transactional} unit so the lock is held (transaction-scoped) through the insert.
     */
    @Transactional
    public OrderHeaderResponse createOrder(String slug, CreateOrderRequest body, HttpServletRequest httpRequest) {
        // 1. Standard-protected guard: 401 / 403 / generic 404 for slug/session.
        RequestContext ctx = requestContextGuard.requireStandardProtected(slug, httpRequest);

        // 2. Validate body.
        String flooringType = validateFlooringType(body);

        // 3. Look up the display codes the order_number needs. RequestContextGuard does not carry
        //    store_code or salesperson_code, so resolve them from the session store_id / user_id.
        String storeCode = storeRepository.findById(ctx.storeId())
                .map(Store::getStoreCode)
                .orElseThrow(() -> new IllegalStateException("Active store not found for session store_id."));
        String salespersonCode = appUserRepository.findById(ctx.userId())
                .map(AppUser::getSalespersonCode)
                .orElseThrow(() -> new IllegalStateException("Session user not found for session user_id."));

        // 4. One clock for created_at, updated_at, and the ISO week — they cannot disagree at a
        //    week boundary because all three derive from this single instant.
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = now.toLocalDate();
        int weekYear = today.get(IsoFields.WEEK_BASED_YEAR);
        int weekNumber = today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);

        // 5. lock -> MAX+1 -> INSERT, all in this transaction.
        orderCreateRepository.lockBusinessForSequence(ctx.businessId());
        int sequenceNumber = orderCreateRepository.nextSequenceNumber(ctx.businessId());
        String orderNumber = String.format("%s.%s.%05d", storeCode, salespersonCode, sequenceNumber);
        long orderId = orderCreateRepository.insertOrderShell(
                ctx.businessId(), ctx.storeId(), ctx.userId(),
                sequenceNumber, orderNumber, flooringType,
                weekNumber, weekYear, now);

        // 6. Build the create-order response. A fresh order is never LAID, so locked is false.
        return new OrderHeaderResponse(
                orderId,
                orderNumber,
                sequenceNumber,
                flooringType,
                STATUS_LEAD,
                false,
                null,   // plan_numbers
                null,   // proposed_lay_date
                null,   // lay_date_status
                null,   // details_of_sale
                null,   // last_emailed_at
                weekYear,
                weekNumber,
                now,
                now,
                false);
    }

    /** GET /orders/{orderId}. Read-only workspace state for Chunk 2 sections. */
    @Transactional(readOnly = true)
    public OrderWorkspaceResponse getOrder(String slug, String orderIdRaw, HttpServletRequest httpRequest) {
        // 1. Standard-protected guard.
        RequestContext ctx = requestContextGuard.requireStandardProtected(slug, httpRequest);

        // 2. Validate the path variable as a positive integer (manual, mirroring the status endpoint).
        long orderId = parseOrderId(orderIdRaw);

        // 3. Scope to the session's (business_id, store_id). Cross-store / cross-business / missing
        //    all return empty -> 404 ORDER_NOT_FOUND with no existence leak.
        SalesOrder order = salesOrderRepository
                .findByOrderIdAndBusinessIdAndStoreId(orderId, ctx.businessId(), ctx.storeId())
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND, "Order not found."));

        // 4. Nested reads — each null when its row is absent. GET never writes these tables.
        CustomerDto customer = orderCustomerRepository.findByOrderId(orderId)
                .map(OrderService::toCustomerDto)
                .orElse(null);

        List<OrderAddress> addresses = orderAddressRepository.findByOrderId(orderId);
        AddressDto installAddress = toAddressDto(findAddress(addresses, ADDRESS_INSTALLATION));
        AddressDto billingAddress = toAddressDto(findAddress(addresses, ADDRESS_BILLING));

        // 5. persisted_financials is always present; each scalar is null until Chunk 3 writes it.
        PersistedFinancialsDto persistedFinancials = new PersistedFinancialsDto(
                order.getSalePriceExGst(),
                order.getTotalCost(),
                order.getGp(),
                order.getGpPercent());

        boolean locked = STATUS_LAID.equals(order.getOrderStatus());

        return new OrderWorkspaceResponse(
                order.getOrderId(),
                order.getOrderNumber(),
                order.getOrderSequenceNumber(),
                order.getFlooringType(),
                order.getOrderStatus(),
                order.isSupplyOnly(),
                order.getPlanNumbers(),
                order.getProposedLayDate(),
                order.getLayDateStatus(),
                order.getDetailsOfSale(),
                order.getLastEmailedAt(),
                order.getWeekYear(),
                order.getWeekNumber(),
                order.getCreatedAt(),
                order.getUpdatedAt(),
                locked,
                customer,
                installAddress,
                billingAddress,
                persistedFinancials);
    }

    private static String validateFlooringType(CreateOrderRequest body) {
        String value = body == null ? null : body.flooringType();
        if (value == null) {
            throw new ValidationException(
                    ErrorCode.VALIDATION_FAILED.defaultMessage(),
                    List.of(new ErrorDetail(null, "flooring_type", "Required.")));
        }
        if (!FLOORING_SOFT.equals(value) && !FLOORING_HARD.equals(value)) {
            throw new ValidationException(
                    ErrorCode.VALIDATION_FAILED.defaultMessage(),
                    List.of(new ErrorDetail(null, "flooring_type", "Must be one of SOFT, HARD.")));
        }
        return value;
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

    private static OrderAddress findAddress(List<OrderAddress> addresses, String addressType) {
        return addresses.stream()
                .filter(a -> addressType.equals(a.getAddressType()))
                .findFirst()
                .orElse(null);
    }

    private static CustomerDto toCustomerDto(OrderCustomer c) {
        return new CustomerDto(
                c.getFirstName(),
                c.getMiddleName(),
                c.getLastName(),
                c.getEmail(),
                c.getMobile(),
                c.getHomePhone(),
                c.getWorkPhone(),
                c.getCompanyName());
    }

    private static AddressDto toAddressDto(OrderAddress a) {
        if (a == null) {
            return null;
        }
        return new AddressDto(
                a.getUnitNumber(),
                a.getStreetNumber(),
                a.getStreet(),
                a.getSuburb(),
                a.getStateCode(),
                a.getPostcode());
    }
}
