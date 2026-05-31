package com.flooring.salesportal.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flooring.salesportal.common.api.ErrorDetail;
import com.flooring.salesportal.common.error.BusinessRuleException;
import com.flooring.salesportal.common.error.ErrorCode;
import com.flooring.salesportal.common.error.MalformedJsonException;
import com.flooring.salesportal.common.error.NotFoundException;
import com.flooring.salesportal.common.error.ValidationException;
import com.flooring.salesportal.common.session.RequestContext;
import com.flooring.salesportal.common.session.RequestContextGuard;
import com.flooring.salesportal.order.dto.AddressDto;
import com.flooring.salesportal.order.dto.AddressUpsertRequest;
import com.flooring.salesportal.order.dto.BillingAddressResponse;
import com.flooring.salesportal.order.dto.CreateOrderRequest;
import com.flooring.salesportal.order.dto.CustomerDto;
import com.flooring.salesportal.order.dto.CustomerSaveRequest;
import com.flooring.salesportal.order.dto.CustomerSaveResponse;
import com.flooring.salesportal.order.dto.InstallationAddressResponse;
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
import java.util.ArrayList;
import java.util.List;

@Service
public class OrderService {

    private static final String FLOORING_SOFT = "SOFT";
    private static final String FLOORING_HARD = "HARD";
    private static final String STATUS_LEAD = "LEAD";
    private static final String STATUS_LAID = "LAID";
    private static final String ADDRESS_INSTALLATION = "INSTALLATION";
    private static final String ADDRESS_BILLING = "BILLING";

    // order_customer VARCHAR limits, taken directly from V2__create_tables.sql.
    private static final int MAX_NAME = 100;    // first_name / middle_name / last_name VARCHAR(100)
    private static final int MAX_EMAIL = 255;   // email VARCHAR(255)
    private static final int MAX_PHONE = 20;    // mobile / home_phone / work_phone VARCHAR(20)
    private static final int MAX_COMPANY = 150; // company_name VARCHAR(150)

    // order_address VARCHAR limits, taken directly from V2__create_tables.sql.
    private static final int MAX_UNIT_NUMBER = 20;    // unit_number VARCHAR(20)
    private static final int MAX_STREET_NUMBER = 20;  // street_number VARCHAR(20)
    private static final int MAX_STREET = 255;        // street VARCHAR(255)
    private static final int MAX_SUBURB = 100;        // suburb VARCHAR(100)
    private static final int MAX_STATE_CODE = 20;     // state_code VARCHAR(20)
    private static final int MAX_POSTCODE = 10;       // postcode VARCHAR(10)

    private final RequestContextGuard requestContextGuard;
    private final StoreRepository storeRepository;
    private final AppUserRepository appUserRepository;
    private final OrderCreateRepository orderCreateRepository;
    private final SalesOrderRepository salesOrderRepository;
    private final OrderCustomerRepository orderCustomerRepository;
    private final OrderCustomerWriteRepository orderCustomerWriteRepository;
    private final OrderAddressRepository orderAddressRepository;
    private final OrderAddressWriteRepository orderAddressWriteRepository;
    private final ObjectMapper objectMapper;

    public OrderService(RequestContextGuard requestContextGuard,
                        StoreRepository storeRepository,
                        AppUserRepository appUserRepository,
                        OrderCreateRepository orderCreateRepository,
                        SalesOrderRepository salesOrderRepository,
                        OrderCustomerRepository orderCustomerRepository,
                        OrderCustomerWriteRepository orderCustomerWriteRepository,
                        OrderAddressRepository orderAddressRepository,
                        OrderAddressWriteRepository orderAddressWriteRepository,
                        ObjectMapper objectMapper) {
        this.requestContextGuard = requestContextGuard;
        this.storeRepository = storeRepository;
        this.appUserRepository = appUserRepository;
        this.orderCreateRepository = orderCreateRepository;
        this.salesOrderRepository = salesOrderRepository;
        this.orderCustomerRepository = orderCustomerRepository;
        this.orderCustomerWriteRepository = orderCustomerWriteRepository;
        this.orderAddressRepository = orderAddressRepository;
        this.orderAddressWriteRepository = orderAddressWriteRepository;
        this.objectMapper = objectMapper;
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

    /**
     * PUT /orders/{orderId}/customer. Insert-or-full-replace the one {@code order_customer} row.
     * Gate-first ordering (approved): guard → parse orderId → scoped lookup → 404 → LAID → 422 →
     * only then parse the raw JSON body, validate it, and upsert. So a LAID in-scope order with a
     * malformed/invalid body still returns 422 ORDER_LOCKED, and a cross-store/cross-business order
     * still returns 404 ORDER_NOT_FOUND — body parsing/validation never runs ahead of the gates.
     * The body arrives as a raw String (parsed here, not by Spring) precisely so malformed JSON
     * cannot 400 before these gates.
     */
    @Transactional
    public CustomerSaveResponse saveCustomer(String slug,
                                             String orderIdRaw,
                                             String body,
                                             HttpServletRequest httpRequest) {
        // 1. Standard-protected guard.
        RequestContext ctx = requestContextGuard.requireStandardProtected(slug, httpRequest);

        // 2. Validate the path variable as a positive integer.
        long orderId = parseOrderId(orderIdRaw);

        // 3. Scope to the session's (business_id, store_id) with a pessimistic write lock
        //    (SELECT ... FOR UPDATE). Locking inside this @Transactional method closes the
        //    read-then-write race: a concurrent PATCH-status update to LAID cannot interleave
        //    between this read and the upsert commit. Missing / cross-store / cross-business
        //    all return empty -> 404 ORDER_NOT_FOUND with no existence leak.
        SalesOrder order = salesOrderRepository
                .findByOrderIdAndBusinessIdAndStoreIdForUpdate(orderId, ctx.businessId(), ctx.storeId())
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND, "Order not found."));

        // 4. Only after the order is confirmed in-scope: LAID is locked for customer edits.
        if (STATUS_LAID.equals(order.getOrderStatus())) {
            throw new BusinessRuleException(ErrorCode.ORDER_LOCKED, ErrorCode.ORDER_LOCKED.defaultMessage());
        }

        // 5. Parse the raw JSON body (malformed -> 400 MALFORMED_JSON), then validate + trim.
        //    Null/blank body parses to null and flows to validation as all-fields-missing.
        //    Full-replace: optional omitted/null fields become null.
        CustomerSaveRequest request = parseBodyAfterGates(body, CustomerSaveRequest.class);
        CustomerDto customer = validateAndTrimCustomer(request);

        // 6. One clock for created_at (insert only) / updated_at. Native upsert respects
        //    uq_order_customer_order, preserves created_at on replace, refreshes updated_at.
        LocalDateTime now = LocalDateTime.now();
        orderCustomerWriteRepository.upsert(
                orderId,
                customer.firstName(),
                customer.middleName(),
                customer.lastName(),
                customer.email(),
                customer.mobile(),
                customer.homePhone(),
                customer.workPhone(),
                customer.companyName(),
                now);

        return new CustomerSaveResponse(customer);
    }

    /**
     * PUT /orders/{orderId}/addresses/installation. Insert-or-full-replace the one
     * {@code order_address} row of type INSTALLATION. Same gate-first ordering as
     * {@link #saveCustomer}: guard → parse orderId → scoped FOR UPDATE lookup → 404 → LAID → 422 →
     * only then validate body and upsert. The path determines {@code address_type}; it is never
     * read from the body.
     */
    @Transactional
    public InstallationAddressResponse saveInstallationAddress(String slug,
                                                               String orderIdRaw,
                                                               String body,
                                                               HttpServletRequest httpRequest) {
        AddressDto address = saveAddress(slug, orderIdRaw, body, httpRequest, ADDRESS_INSTALLATION);
        return new InstallationAddressResponse(address);
    }

    /**
     * PUT /orders/{orderId}/addresses/billing. Insert-or-full-replace the one
     * {@code order_address} row of type BILLING. Same gate-first ordering as
     * {@link #saveInstallationAddress}; the path determines {@code address_type}.
     */
    @Transactional
    public BillingAddressResponse saveBillingAddress(String slug,
                                                     String orderIdRaw,
                                                     String body,
                                                     HttpServletRequest httpRequest) {
        AddressDto address = saveAddress(slug, orderIdRaw, body, httpRequest, ADDRESS_BILLING);
        return new BillingAddressResponse(address);
    }

    /**
     * Shared body of the two address PUTs. Gate order: guard → parse orderId → scoped FOR UPDATE
     * lookup (missing/cross-store/cross-business → 404 ORDER_NOT_FOUND, no existence leak) → LAID
     * (422 ORDER_LOCKED) → parse raw JSON body (malformed → 400 MALFORMED_JSON) → validate + trim
     * (400 VALIDATION_FAILED; a null/blank body parses to null and is treated as all-fields-missing,
     * not an NPE) → native upsert. The body arrives as a raw String (parsed here, not by Spring) so
     * malformed JSON cannot 400 before the gates. The pessimistic write lock plus this
     * {@code @Transactional} method serialize all address writes for the order against a concurrent
     * PATCH-status update to LAID.
     */
    private AddressDto saveAddress(String slug,
                                   String orderIdRaw,
                                   String body,
                                   HttpServletRequest httpRequest,
                                   String addressType) {
        // 1. Standard-protected guard.
        RequestContext ctx = requestContextGuard.requireStandardProtected(slug, httpRequest);

        // 2. Validate the path variable as a positive integer.
        long orderId = parseOrderId(orderIdRaw);

        // 3. Scope to the session's (business_id, store_id) with a pessimistic write lock.
        SalesOrder order = salesOrderRepository
                .findByOrderIdAndBusinessIdAndStoreIdForUpdate(orderId, ctx.businessId(), ctx.storeId())
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND, "Order not found."));

        // 4. Only after the order is confirmed in-scope: LAID is locked for address edits.
        if (STATUS_LAID.equals(order.getOrderStatus())) {
            throw new BusinessRuleException(ErrorCode.ORDER_LOCKED, ErrorCode.ORDER_LOCKED.defaultMessage());
        }

        // 5. Parse the raw JSON body (malformed -> 400 MALFORMED_JSON), then validate + trim.
        //    Null/blank body parses to null -> all-fields-missing. Full-replace: omitted/null
        //    unit_number becomes null.
        AddressUpsertRequest request = parseBodyAfterGates(body, AddressUpsertRequest.class);
        AddressDto address = validateAndTrimAddress(request);

        // 6. One clock for created_at (insert only) / updated_at. Native upsert respects
        //    uq_order_address_order_type, preserves created_at on replace, refreshes updated_at.
        LocalDateTime now = LocalDateTime.now();
        upsertAddress(orderId, addressType, address, now);

        return address;
    }

    /**
     * POST /orders/{orderId}/addresses/billing/copy-from-installation. Copies the existing
     * INSTALLATION address into the BILLING row (create or replace). Gate order: guard → parse
     * orderId → scoped FOR UPDATE lookup → 404 → LAID → 422 → only then read INSTALLATION → if
     * absent 422 INSTALLATION_ADDRESS_REQUIRED. A LAID order with no installation row returns
     * ORDER_LOCKED (the LAID gate runs first), and a cross-store/cross-business order returns 404
     * before any installation-exists check (no existence leak). Reading the INSTALLATION row
     * without its own row lock is safe: the sales_order FOR UPDATE lock serializes every address
     * mutation for this order, since all address endpoints take that lock first. Atomic in this
     * single {@code @Transactional} method.
     */
    @Transactional
    public BillingAddressResponse copyBillingFromInstallation(String slug,
                                                              String orderIdRaw,
                                                              HttpServletRequest httpRequest) {
        // 1. Standard-protected guard.
        RequestContext ctx = requestContextGuard.requireStandardProtected(slug, httpRequest);

        // 2. Validate the path variable as a positive integer.
        long orderId = parseOrderId(orderIdRaw);

        // 3. Scope to the session's (business_id, store_id) with a pessimistic write lock.
        SalesOrder order = salesOrderRepository
                .findByOrderIdAndBusinessIdAndStoreIdForUpdate(orderId, ctx.businessId(), ctx.storeId())
                .orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND, "Order not found."));

        // 4. LAID is locked for address edits — checked before the installation-exists precondition.
        if (STATUS_LAID.equals(order.getOrderStatus())) {
            throw new BusinessRuleException(ErrorCode.ORDER_LOCKED, ErrorCode.ORDER_LOCKED.defaultMessage());
        }

        // 5. Read the existing INSTALLATION row. Absent → 422, no write occurs.
        OrderAddress installation = findAddress(orderAddressRepository.findByOrderId(orderId), ADDRESS_INSTALLATION);
        if (installation == null) {
            throw new BusinessRuleException(
                    ErrorCode.INSTALLATION_ADDRESS_REQUIRED,
                    ErrorCode.INSTALLATION_ADDRESS_REQUIRED.defaultMessage());
        }

        // 6. Copy all six columns into BILLING (create or replace), one clock for timestamps.
        AddressDto billing = new AddressDto(
                installation.getUnitNumber(),
                installation.getStreetNumber(),
                installation.getStreet(),
                installation.getSuburb(),
                installation.getStateCode(),
                installation.getPostcode());
        LocalDateTime now = LocalDateTime.now();
        upsertAddress(orderId, ADDRESS_BILLING, billing, now);

        return new BillingAddressResponse(billing);
    }

    private void upsertAddress(long orderId, String addressType, AddressDto address, LocalDateTime now) {
        orderAddressWriteRepository.upsert(
                orderId,
                addressType,
                address.unitNumber(),
                address.streetNumber(),
                address.street(),
                address.suburb(),
                address.stateCode(),
                address.postcode(),
                now);
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

    /**
     * Parse a raw request body into {@code type} <em>after</em> the mutation gates (guard →
     * orderId → scoped lookup → 404 → LAID → 422) have passed — the body is taken as a raw String
     * by the controller precisely so this parse cannot run ahead of those gates. A {@code null} or
     * blank body returns {@code null}, which downstream {@code validateAndTrim*} treats as
     * all-fields-missing (→ 400 VALIDATION_FAILED). Well-formed-but-incomplete JSON (e.g.
     * {@code "{}"}) parses successfully and likewise flows to validation. Only genuinely
     * unparseable input throws {@link MalformedJsonException} (→ 400 MALFORMED_JSON, same wrapper
     * as Spring's HttpMessageNotReadableException via {@code handleApiException}). Uses the shared
     * Spring {@link ObjectMapper}, so global Jackson behaviour (snake_case, unknown fields ignored)
     * is preserved.
     */
    private <T> T parseBodyAfterGates(String rawBody, Class<T> type) {
        if (rawBody == null || rawBody.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(rawBody, type);
        } catch (JsonProcessingException ex) {
            throw new MalformedJsonException();
        }
    }

    /**
     * Validate the customer body and return trimmed values as a {@link CustomerDto}. Collects all
     * field errors (validated in field order) into one VALIDATION_FAILED so {@code details[0]} is
     * the first offending field. Required: first_name, last_name, email (must contain {@code @}),
     * mobile. Optionals, when provided non-null, must be non-blank after trim; otherwise null.
     * Every value is trimmed first, then checked against its {@code order_customer} VARCHAR limit
     * so overlong input is a normal 400 VALIDATION_FAILED rather than a native-upsert 500.
     */
    private static CustomerDto validateAndTrimCustomer(CustomerSaveRequest body) {
        List<ErrorDetail> errors = new ArrayList<>();
        String firstName = requireNonBlank(body == null ? null : body.firstName(), "first_name", MAX_NAME, errors);
        String lastName  = requireNonBlank(body == null ? null : body.lastName(), "last_name", MAX_NAME, errors);
        String email     = requireEmail(body == null ? null : body.email(), MAX_EMAIL, errors);
        String mobile    = requireNonBlank(body == null ? null : body.mobile(), "mobile", MAX_PHONE, errors);
        String middleName  = optionalNonBlank(body == null ? null : body.middleName(), "middle_name", MAX_NAME, errors);
        String homePhone   = optionalNonBlank(body == null ? null : body.homePhone(), "home_phone", MAX_PHONE, errors);
        String workPhone   = optionalNonBlank(body == null ? null : body.workPhone(), "work_phone", MAX_PHONE, errors);
        String companyName = optionalNonBlank(body == null ? null : body.companyName(), "company_name", MAX_COMPANY, errors);

        if (!errors.isEmpty()) {
            throw new ValidationException(ErrorCode.VALIDATION_FAILED.defaultMessage(), errors);
        }

        return new CustomerDto(firstName, middleName, lastName, email, mobile, homePhone, workPhone, companyName);
    }

    /**
     * Validate the address body and return trimmed values as an {@link AddressDto}. Collects all
     * field errors (validated in field order) into one VALIDATION_FAILED so {@code details[0]} is
     * the first offending field. Required: street_number, street, suburb, state_code, postcode —
     * each non-blank after trim. {@code unit_number} is optional; when provided non-null it must be
     * non-blank after trim, otherwise null. Validates exactly what V2/V3 enforce — non-blank after
     * trim plus the {@code order_address} VARCHAR limit — and nothing more (no state-code list, no
     * postcode format). A {@code null} body (from {@code @RequestBody(required=false)}) is treated
     * as all-required-fields-missing → 400, never an NPE.
     */
    private static AddressDto validateAndTrimAddress(AddressUpsertRequest body) {
        List<ErrorDetail> errors = new ArrayList<>();
        String streetNumber = requireNonBlank(body == null ? null : body.streetNumber(), "street_number", MAX_STREET_NUMBER, errors);
        String street       = requireNonBlank(body == null ? null : body.street(), "street", MAX_STREET, errors);
        String suburb       = requireNonBlank(body == null ? null : body.suburb(), "suburb", MAX_SUBURB, errors);
        String stateCode    = requireNonBlank(body == null ? null : body.stateCode(), "state_code", MAX_STATE_CODE, errors);
        String postcode     = requireNonBlank(body == null ? null : body.postcode(), "postcode", MAX_POSTCODE, errors);
        String unitNumber   = optionalNonBlank(body == null ? null : body.unitNumber(), "unit_number", MAX_UNIT_NUMBER, errors);

        if (!errors.isEmpty()) {
            throw new ValidationException(ErrorCode.VALIDATION_FAILED.defaultMessage(), errors);
        }

        return new AddressDto(unitNumber, streetNumber, street, suburb, stateCode, postcode);
    }

    private static String requireNonBlank(String raw, String field, int maxLength, List<ErrorDetail> errors) {
        if (raw == null || raw.isBlank()) {
            errors.add(new ErrorDetail(null, field, "Required."));
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.length() > maxLength) {
            errors.add(new ErrorDetail(null, field, "Must be at most " + maxLength + " characters."));
            return null;
        }
        return trimmed;
    }

    private static String requireEmail(String raw, int maxLength, List<ErrorDetail> errors) {
        if (raw == null || raw.isBlank()) {
            errors.add(new ErrorDetail(null, "email", "Required."));
            return null;
        }
        String trimmed = raw.trim();
        if (!trimmed.contains("@")) {
            errors.add(new ErrorDetail(null, "email", "Must be a valid email address."));
            return null;
        }
        if (trimmed.length() > maxLength) {
            errors.add(new ErrorDetail(null, "email", "Must be at most " + maxLength + " characters."));
            return null;
        }
        return trimmed;
    }

    private static String optionalNonBlank(String raw, String field, int maxLength, List<ErrorDetail> errors) {
        if (raw == null) {
            return null;
        }
        if (raw.isBlank()) {
            errors.add(new ErrorDetail(null, field, "Must not be blank."));
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.length() > maxLength) {
            errors.add(new ErrorDetail(null, field, "Must be at most " + maxLength + " characters."));
            return null;
        }
        return trimmed;
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
