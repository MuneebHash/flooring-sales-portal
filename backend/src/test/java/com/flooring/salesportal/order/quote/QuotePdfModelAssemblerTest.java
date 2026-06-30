package com.flooring.salesportal.order.quote;

import com.flooring.salesportal.common.storage.FileStorageService;
import com.flooring.salesportal.order.InvoiceTermsSanitizer;
import com.flooring.salesportal.order.OrderAddress;
import com.flooring.salesportal.order.OrderAddressRepository;
import com.flooring.salesportal.order.OrderCustomer;
import com.flooring.salesportal.order.OrderCustomerRepository;
import com.flooring.salesportal.order.OrderSalespersonResolver;
import com.flooring.salesportal.order.SalesOrder;
import com.flooring.salesportal.order.quote.QuotePdfModel.QuotePdfLine;
import com.flooring.salesportal.store.Store;
import com.flooring.salesportal.store.StoreRepository;
import com.flooring.salesportal.tenant.Business;
import com.flooring.salesportal.tenant.BusinessInvoiceConfigView;
import com.flooring.salesportal.tenant.BusinessRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link QuotePdfModelAssembler} with mocked repositories (no Spring/DB). Covers the
 * per-flooring-type terms selection (SOFT vs HARD — the SOFT-only controller seed cannot exercise the
 * HARD branch), the draft-line mapping (ITEM + ADJUSTMENT), and fail-soft behavior when the tenant
 * config / store / customer are absent. The real {@link InvoiceTermsSanitizer} is used so terms
 * sanitization is genuinely exercised.
 */
class QuotePdfModelAssemblerTest {

    private final BusinessRepository businessRepository = mock(BusinessRepository.class);
    private final StoreRepository storeRepository = mock(StoreRepository.class);
    private final OrderSalespersonResolver salespersonResolver = mock(OrderSalespersonResolver.class);
    private final FileStorageService fileStorageService = mock(FileStorageService.class);
    private final OrderCustomerRepository orderCustomerRepository = mock(OrderCustomerRepository.class);
    private final OrderAddressRepository orderAddressRepository = mock(OrderAddressRepository.class);

    private final QuotePdfModelAssembler assembler = new QuotePdfModelAssembler(
            businessRepository, storeRepository, salespersonResolver, fileStorageService,
            new InvoiceTermsSanitizer(), orderCustomerRepository, orderAddressRepository);

    private static final long BUSINESS_ID = 1L;
    private static final long ORDER_ID = 99L;

    private Business business() {
        Business b = mock(Business.class);
        when(b.getBusinessId()).thenReturn(BUSINESS_ID);
        when(b.getName()).thenReturn("Aussie Floors Group");
        when(b.getLogoPath()).thenReturn(null);
        return b;
    }

    private SalesOrder order(String flooringType) {
        SalesOrder o = mock(SalesOrder.class);
        when(o.getFlooringType()).thenReturn(flooringType);
        when(o.getStoreId()).thenReturn(1);
        when(o.getUserId()).thenReturn(1L);
        when(o.getOrderNumber()).thenReturn("SYD-CBD.LC1.00001");
        when(o.getDetailsOfSale()).thenReturn("Supply and install carpet.");
        return o;
    }

    private QuoteDraft draft() {
        QuoteDraft d = mock(QuoteDraft.class);
        when(d.isItemised()).thenReturn(true);
        when(d.getQuoteTotalExGst()).thenReturn(new BigDecimal("100.00"));
        when(d.getQuoteTotalIncGst()).thenReturn(new BigDecimal("110.00"));
        return d;
    }

    private QuoteDraftLine itemLine() {
        QuoteDraftLine l = mock(QuoteDraftLine.class);
        when(l.getLineType()).thenReturn("ITEM");
        when(l.getDescription()).thenReturn("Carpet");
        when(l.getQuantity()).thenReturn(new BigDecimal("2.00"));
        when(l.getUnitPriceExGst()).thenReturn(new BigDecimal("50.00"));
        when(l.getLineTotalExGst()).thenReturn(new BigDecimal("100.00"));
        return l;
    }

    private QuoteDraftLine adjustmentLine() {
        QuoteDraftLine l = mock(QuoteDraftLine.class);
        when(l.getLineType()).thenReturn("ADJUSTMENT");
        when(l.getDescription()).thenReturn("Discount");
        when(l.getQuantity()).thenReturn(null);
        when(l.getUnitPriceExGst()).thenReturn(null);
        when(l.getLineTotalExGst()).thenReturn(new BigDecimal("-10.00"));
        return l;
    }

    private BusinessInvoiceConfigView config(String soft, String hard) {
        BusinessInvoiceConfigView c = mock(BusinessInvoiceConfigView.class);
        when(c.getAbn()).thenReturn("11 222 333 444");
        when(c.getBankName()).thenReturn("Example Bank");
        when(c.getBsb()).thenReturn("062-000");
        when(c.getAccountName()).thenReturn("Aussie Floors Pty Ltd");
        when(c.getAccountNumber()).thenReturn("12345678");
        when(c.getTermsSoft()).thenReturn(soft);
        when(c.getTermsHard()).thenReturn(hard);
        return c;
    }

    @Test
    void assemble_softFlooring_selectsSoftTerms_notSeparatePage() {
        // Build the config mock BEFORE the outer when() — config() itself calls when()/thenReturn(),
        // which Mockito would otherwise see as a nested UnfinishedStubbing.
        BusinessInvoiceConfigView cfg = config("<p>Soft terms apply.</p>", "<p>Hard terms apply.</p>");
        when(businessRepository.findInvoiceConfigByBusinessId(BUSINESS_ID)).thenReturn(Optional.of(cfg));
        when(storeRepository.findByStoreIdAndBusinessId(1, BUSINESS_ID)).thenReturn(Optional.empty());
        when(salespersonResolver.resolveName(1L, BUSINESS_ID)).thenReturn("Liam Carter");
        when(orderCustomerRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());
        when(orderAddressRepository.findByOrderId(ORDER_ID)).thenReturn(List.of());

        QuotePdfModel m = assembler.assemble(business(), order("SOFT"), ORDER_ID, draft(), List.of(itemLine()));

        assertNotNull(m.termsHtml());
        assertTrue(m.termsHtml().contains("Soft terms apply."), () -> "expected soft terms, got " + m.termsHtml());
        assertFalse(m.termsHtml().contains("Hard terms"), "soft order must not show hard terms");
        assertEquals("Soft Flooring", m.flooringTypeLabel());
        assertEquals("11 222 333 444", m.abn());
        assertEquals("Example Bank", m.bankName());
        assertEquals("Liam Carter", m.salespersonName());
    }

    @Test
    void assemble_hardFlooring_selectsHardTerms_separatePage() {
        BusinessInvoiceConfigView cfg = config("<p>Soft terms apply.</p>", "<p>Hard terms apply.</p>");
        when(businessRepository.findInvoiceConfigByBusinessId(BUSINESS_ID)).thenReturn(Optional.of(cfg));
        when(storeRepository.findByStoreIdAndBusinessId(1, BUSINESS_ID)).thenReturn(Optional.empty());
        when(salespersonResolver.resolveName(1L, BUSINESS_ID)).thenReturn("Liam Carter");
        when(orderCustomerRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());
        when(orderAddressRepository.findByOrderId(ORDER_ID)).thenReturn(List.of());

        QuotePdfModel m = assembler.assemble(business(), order("HARD"), ORDER_ID, draft(), List.of(itemLine()));

        assertNotNull(m.termsHtml());
        assertTrue(m.termsHtml().contains("Hard terms apply."), () -> "expected hard terms, got " + m.termsHtml());
        assertFalse(m.termsHtml().contains("Soft terms"), "hard order must not show soft terms");
        assertEquals("Hard Flooring", m.flooringTypeLabel());
    }

    @Test
    void assemble_mapsItemAndAdjustmentLines() {
        when(businessRepository.findInvoiceConfigByBusinessId(BUSINESS_ID)).thenReturn(Optional.empty());
        when(storeRepository.findByStoreIdAndBusinessId(1, BUSINESS_ID)).thenReturn(Optional.empty());
        when(salespersonResolver.resolveName(1L, BUSINESS_ID)).thenReturn(null);
        when(orderCustomerRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());
        when(orderAddressRepository.findByOrderId(ORDER_ID)).thenReturn(List.of());

        QuotePdfModel m = assembler.assemble(business(), order("SOFT"), ORDER_ID, draft(),
                List.of(itemLine(), adjustmentLine()));

        assertEquals(2, m.lines().size());
        QuotePdfLine item = m.lines().get(0);
        assertEquals("ITEM", item.lineType());
        assertEquals("Carpet", item.description());
        assertEquals(0, new BigDecimal("100.00").compareTo(item.lineTotalExGst()));
        QuotePdfLine adj = m.lines().get(1);
        assertEquals("ADJUSTMENT", adj.lineType());
        assertNull(adj.quantity());
        assertNull(adj.unitPriceExGst());
        assertEquals(0, new BigDecimal("-10.00").compareTo(adj.lineTotalExGst()));
    }

    @Test
    void assemble_nullConfig_failsSoft_noTermsNoBankNoAbn() {
        when(businessRepository.findInvoiceConfigByBusinessId(BUSINESS_ID)).thenReturn(Optional.empty());
        when(storeRepository.findByStoreIdAndBusinessId(1, BUSINESS_ID)).thenReturn(Optional.empty());
        when(salespersonResolver.resolveName(1L, BUSINESS_ID)).thenReturn(null);
        when(orderCustomerRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());
        when(orderAddressRepository.findByOrderId(ORDER_ID)).thenReturn(List.of());

        QuotePdfModel m = assembler.assemble(business(), order("SOFT"), ORDER_ID, draft(), List.of(itemLine()));

        assertNull(m.termsHtml(), "no config -> no terms");
        assertNull(m.abn());
        assertNull(m.bankName());
        assertNull(m.storeName(), "no store -> null store fields");
        assertNull(m.customerName(), "no customer -> null name");
        assertEquals("Aussie Floors Group", m.businessName());
        assertEquals("SYD-CBD.LC1.00001", m.orderNumber());
    }

    @Test
    void assemble_withCustomerAndBillingAddress_buildsNameAndLines() {
        when(businessRepository.findInvoiceConfigByBusinessId(BUSINESS_ID)).thenReturn(Optional.empty());
        when(storeRepository.findByStoreIdAndBusinessId(1, BUSINESS_ID)).thenReturn(Optional.empty());
        when(salespersonResolver.resolveName(1L, BUSINESS_ID)).thenReturn(null);

        OrderCustomer customer = mock(OrderCustomer.class);
        when(customer.getFirstName()).thenReturn("James");
        when(customer.getMiddleName()).thenReturn(null);
        when(customer.getLastName()).thenReturn("Wilson");
        when(orderCustomerRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(customer));

        OrderAddress billing = mock(OrderAddress.class);
        when(billing.getAddressType()).thenReturn("BILLING");
        when(billing.getUnitNumber()).thenReturn(null);
        when(billing.getStreetNumber()).thenReturn("42");
        when(billing.getStreet()).thenReturn("Oxford Street");
        when(billing.getSuburb()).thenReturn("Paddington");
        when(billing.getStateCode()).thenReturn("NSW");
        when(billing.getPostcode()).thenReturn("2021");
        when(orderAddressRepository.findByOrderId(ORDER_ID)).thenReturn(List.of(billing));

        QuotePdfModel m = assembler.assemble(business(), order("SOFT"), ORDER_ID, draft(), List.of(itemLine()));

        assertEquals("James Wilson", m.customerName());
        assertEquals("42 Oxford Street", m.billingLine1());
        assertEquals("Paddington NSW 2021", m.billingLine2());
    }
}
