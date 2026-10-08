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
import com.flooring.salesportal.order.quote.QuoteVersionRepository.QuoteVersionLineRow;
import com.flooring.salesportal.order.quote.QuoteVersionRepository.QuoteVersionRow;
import com.flooring.salesportal.store.Store;
import com.flooring.salesportal.store.StoreRepository;
import com.flooring.salesportal.tenant.Business;
import com.flooring.salesportal.tenant.BusinessInvoiceConfigView;
import com.flooring.salesportal.tenant.BusinessRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link QuotePdfModelAssembler} with mocked repositories (no Spring/DB). Covers the
 * per-flooring-type terms selection (SOFT vs HARD — the SOFT-only controller seed cannot exercise the
 * HARD branch), the draft-line mapping (ITEM + ADJUSTMENT), and fail-soft behavior when the tenant
 * config / store / customer are absent. The real {@link InvoiceTermsSanitizer} is used so terms
 * sanitization is genuinely exercised.
 *
 * <p>Phase 16F PR1: {@link QuotePdfModelAssembler#assembleAccepted} builds the SIGNED model from the
 * immutable issued version only — its fixtures deliberately DIFFER from the live order / customer /
 * billing address / tenant terms, so every assertion proves which source was used, and the live
 * customer/address repositories must see no interaction at all. The preview ({@code assemble}) and
 * issued ({@code assembleIssued}) models stay unsigned.
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

    // ------------------------------------------------------------------
    // Phase 16F PR1 fixtures — the immutable issued version (signed-PDF source)
    // ------------------------------------------------------------------

    private static final LocalDateTime ISSUED_AT = LocalDateTime.of(2026, 9, 1, 10, 0);
    private static final LocalDateTime ACCEPTED_AT = LocalDateTime.of(2026, 9, 3, 14, 31);
    // Passed through untouched (the generator embeds it); the assembler never decodes it.
    private static final byte[] SIGNATURE_PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    /** An ISSUED quote_version row (ex 150.00 / inc 165.00) with the given frozen snapshot columns. */
    private static QuoteVersionRow version(boolean itemised, String flooringTypeSnapshot, String termsSnapshot,
                                           String detailsOfSaleSnapshot, String customerNameSnapshot,
                                           String addressLine1Snapshot, String addressLine2Snapshot) {
        return new QuoteVersionRow(
                501L, ORDER_ID, 2, "ISSUED", itemised,
                new BigDecimal("150.00"), new BigDecimal("165.00"),
                flooringTypeSnapshot, termsSnapshot, detailsOfSaleSnapshot,
                customerNameSnapshot, addressLine1Snapshot, addressLine2Snapshot,
                "EMAIL", ISSUED_AT, ISSUED_AT, ISSUED_AT, null, ISSUED_AT);
    }

    /** The version's frozen snapshot lines (ITEM 2 x 80.00 + ADJUSTMENT -10.00 = 150.00 ex GST). */
    private static List<QuoteVersionLineRow> versionLines() {
        return List.of(
                new QuoteVersionLineRow("ITEM", "Frozen hybrid plank", new BigDecimal("2.00"),
                        new BigDecimal("80.00"), new BigDecimal("160.00"), 1),
                new QuoteVersionLineRow("ADJUSTMENT", "Frozen discount", null, null,
                        new BigDecimal("-10.00"), 2));
    }

    /**
     * Stub a LIVE order_customer + BILLING address that differ from every version snapshot. Stubbing
     * is not an interaction, so {@code verifyNoInteractions} still proves they were never read.
     */
    private void stubLiveCustomerAndBillingThatDifferFromSnapshot() {
        OrderCustomer live = mock(OrderCustomer.class);
        when(live.getFirstName()).thenReturn("Live");
        when(live.getMiddleName()).thenReturn(null);
        when(live.getLastName()).thenReturn("Renamed");
        OrderAddress liveBilling = mock(OrderAddress.class);
        when(liveBilling.getAddressType()).thenReturn("BILLING");
        when(liveBilling.getUnitNumber()).thenReturn(null);
        when(liveBilling.getStreetNumber()).thenReturn("1");
        when(liveBilling.getStreet()).thenReturn("Live Street");
        when(liveBilling.getSuburb()).thenReturn("Liveville");
        when(liveBilling.getStateCode()).thenReturn("VIC");
        when(liveBilling.getPostcode()).thenReturn("3000");
        when(orderCustomerRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(live));
        when(orderAddressRepository.findByOrderId(ORDER_ID)).thenReturn(List.of(liveBilling));
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

    // ------------------------------------------------------------------
    // Phase 16F PR1 — assembleAccepted (signed quote PDF model)
    // ------------------------------------------------------------------

    @Test
    void assembleAccepted_takesIdentityTermsAndBodyFromFrozenVersion_neverReadsLiveCustomerOrAddress() {
        // Live state DIFFERS from the version everywhere: tenant terms, order flooring type (SOFT vs the
        // version's HARD), order details of sale, customer name and billing address.
        BusinessInvoiceConfigView cfg = config("<p>Live soft terms.</p>", "<p>Live hard terms.</p>");
        when(businessRepository.findInvoiceConfigByBusinessId(BUSINESS_ID)).thenReturn(Optional.of(cfg));
        when(storeRepository.findByStoreIdAndBusinessId(1, BUSINESS_ID)).thenReturn(Optional.empty());
        when(salespersonResolver.resolveName(1L, BUSINESS_ID)).thenReturn("Liam Carter");
        stubLiveCustomerAndBillingThatDifferFromSnapshot();

        QuoteVersionRow version = version(true, "HARD", "<p>Frozen hard terms at issue.</p>",
                "Frozen details of sale at issue.", "Frozen Customer", "7 Frozen Lane", "Frozenville NSW 2000");
        QuotePdfModel m = assembler.assembleAccepted(business(), order("SOFT"), version, versionLines(),
                ACCEPTED_AT, "Frozen Customer", SIGNATURE_PNG);

        // "Quotation To" = the V17 issue snapshot columns; the live rows are never even read.
        assertEquals("Frozen Customer", m.customerName());
        assertEquals("7 Frozen Lane", m.billingLine1());
        assertEquals("Frozenville NSW 2000", m.billingLine2());
        verifyNoInteractions(orderCustomerRepository, orderAddressRepository);

        // Terms = the frozen terms_snapshot verbatim, never the live per-type tenant terms.
        assertEquals("<p>Frozen hard terms at issue.</p>", m.termsHtml());
        // Body = the version: flooring type, details of sale, itemised flag, totals, snapshot lines.
        assertEquals("Hard Flooring", m.flooringTypeLabel());
        assertEquals("Frozen details of sale at issue.", m.detailsOfSale());
        assertTrue(m.itemised());
        assertEquals(0, new BigDecimal("150.00").compareTo(m.quoteTotalExGst()));
        assertEquals(0, new BigDecimal("165.00").compareTo(m.quoteTotalIncGst()));
        assertEquals(2, m.lines().size());
        QuotePdfLine item = m.lines().get(0);
        assertEquals("ITEM", item.lineType());
        assertEquals("Frozen hybrid plank", item.description());
        assertEquals(0, new BigDecimal("2.00").compareTo(item.quantity()));
        assertEquals(0, new BigDecimal("80.00").compareTo(item.unitPriceExGst()));
        assertEquals(0, new BigDecimal("160.00").compareTo(item.lineTotalExGst()));
        QuotePdfLine adj = m.lines().get(1);
        assertEquals("ADJUSTMENT", adj.lineType());
        assertEquals("Frozen discount", adj.description());
        assertNull(adj.quantity());
        assertNull(adj.unitPriceExGst());
        assertEquals(0, new BigDecimal("-10.00").compareTo(adj.lineTotalExGst()));

        // Acceptance fields are set (the signed rendering).
        assertEquals(ACCEPTED_AT, m.acceptedAt());
        assertEquals("Frozen Customer", m.acceptedCustomerName());
        assertSame(SIGNATURE_PNG, m.signaturePng());
        assertTrue(m.accepted());

        // Presentation context is still read live (the approved model).
        assertEquals("Aussie Floors Group", m.businessName());
        assertEquals("11 222 333 444", m.abn());
        assertEquals("Example Bank", m.bankName());
        assertEquals("Liam Carter", m.salespersonName());
        assertEquals("SYD-CBD.LC1.00001", m.orderNumber());
    }

    @Test
    void assembleAccepted_nullTermsAndBlankDetailsAndNullAddressSnapshots_stayAbsent_noLiveFallback() {
        // Issued with NO terms (frozen absence), blank details of sale and no billing address. The
        // tenant now HAS soft terms and the live customer has an address: none of it may appear.
        BusinessInvoiceConfigView cfg = config("<p>Live soft terms.</p>", "<p>Live hard terms.</p>");
        when(businessRepository.findInvoiceConfigByBusinessId(BUSINESS_ID)).thenReturn(Optional.of(cfg));
        when(storeRepository.findByStoreIdAndBusinessId(1, BUSINESS_ID)).thenReturn(Optional.empty());
        when(salespersonResolver.resolveName(1L, BUSINESS_ID)).thenReturn(null);
        stubLiveCustomerAndBillingThatDifferFromSnapshot();

        QuoteVersionRow version = version(true, "SOFT", null, "   ", "Frozen Customer", null, null);
        QuotePdfModel m = assembler.assembleAccepted(business(), order("SOFT"), version, versionLines(),
                ACCEPTED_AT, "Frozen Customer", SIGNATURE_PNG);

        assertNull(m.termsHtml(), "a null terms_snapshot stays null even though the tenant now has soft terms");
        assertNull(m.detailsOfSale(), "a blank details_of_sale_snapshot never falls back to the live order text");
        assertNull(m.billingLine1(), "a null address snapshot never falls back to the live billing address");
        assertNull(m.billingLine2(), "a null address snapshot never falls back to the live billing address");
        assertEquals("Frozen Customer", m.customerName());
        assertEquals("Soft Flooring", m.flooringTypeLabel());
        verifyNoInteractions(orderCustomerRepository, orderAddressRepository);
        assertTrue(m.accepted());
    }

    @Test
    void assembleAccepted_nonItemisedVersion_ignoresAnyVersionLinesPassed() {
        when(businessRepository.findInvoiceConfigByBusinessId(BUSINESS_ID)).thenReturn(Optional.empty());
        when(storeRepository.findByStoreIdAndBusinessId(1, BUSINESS_ID)).thenReturn(Optional.empty());
        when(salespersonResolver.resolveName(1L, BUSINESS_ID)).thenReturn(null);

        QuoteVersionRow version = version(false, "SOFT", null, "Supply and install carpet.",
                "Frozen Customer", "7 Frozen Lane", "Frozenville NSW 2000");
        QuotePdfModel m = assembler.assembleAccepted(business(), order("SOFT"), version, versionLines(),
                ACCEPTED_AT, "Frozen Customer", SIGNATURE_PNG);

        assertFalse(m.itemised());
        assertTrue(m.lines().isEmpty(), () -> "a non-itemised signed quote must carry no lines, got " + m.lines());
        assertEquals(0, new BigDecimal("150.00").compareTo(m.quoteTotalExGst()));
        assertEquals(0, new BigDecimal("165.00").compareTo(m.quoteTotalIncGst()));
        assertEquals("Supply and install carpet.", m.detailsOfSale());
        assertTrue(m.accepted());
        verifyNoInteractions(orderCustomerRepository, orderAddressRepository);
    }

    @Test
    void assemble_draftPreview_staysUnsigned() {
        BusinessInvoiceConfigView cfg = config("<p>Soft terms apply.</p>", "<p>Hard terms apply.</p>");
        when(businessRepository.findInvoiceConfigByBusinessId(BUSINESS_ID)).thenReturn(Optional.of(cfg));
        when(storeRepository.findByStoreIdAndBusinessId(1, BUSINESS_ID)).thenReturn(Optional.empty());
        when(salespersonResolver.resolveName(1L, BUSINESS_ID)).thenReturn("Liam Carter");
        when(orderCustomerRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());
        when(orderAddressRepository.findByOrderId(ORDER_ID)).thenReturn(List.of());

        QuotePdfModel m = assembler.assemble(business(), order("SOFT"), ORDER_ID, draft(), List.of(itemLine()));

        assertNull(m.acceptedAt(), "the draft preview is never a signed rendering");
        assertNull(m.acceptedCustomerName());
        assertNull(m.signaturePng());
        assertFalse(m.accepted());
    }

    @Test
    void assembleIssued_issuedPdf_staysUnsigned_bodyFromSnapshot() {
        BusinessInvoiceConfigView cfg = config("<p>Live soft terms.</p>", "<p>Live hard terms.</p>");
        when(businessRepository.findInvoiceConfigByBusinessId(BUSINESS_ID)).thenReturn(Optional.of(cfg));
        when(storeRepository.findByStoreIdAndBusinessId(1, BUSINESS_ID)).thenReturn(Optional.empty());
        when(salespersonResolver.resolveName(1L, BUSINESS_ID)).thenReturn("Liam Carter");
        when(orderCustomerRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());
        when(orderAddressRepository.findByOrderId(ORDER_ID)).thenReturn(List.of());

        QuoteIssueSnapshot snapshot = new QuoteIssueSnapshot(
                true, new BigDecimal("100.00"), new BigDecimal("110.00"),
                "SOFT", "<p>Issued frozen terms.</p>", "Issued details of sale.",
                "James Wilson", "42 Oxford Street", "Paddington NSW 2021",
                List.of(new QuoteIssueSnapshot.Line("ITEM", "Carpet", new BigDecimal("2.00"),
                        new BigDecimal("50.00"), new BigDecimal("100.00"), 1)));
        QuotePdfModel m = assembler.assembleIssued(business(), order("SOFT"), ORDER_ID, snapshot);

        assertNull(m.acceptedAt(), "the issued PDF is never a signed rendering");
        assertNull(m.acceptedCustomerName());
        assertNull(m.signaturePng());
        assertFalse(m.accepted());
        assertEquals("<p>Issued frozen terms.</p>", m.termsHtml());
        assertEquals(1, m.lines().size());
        assertEquals(0, new BigDecimal("110.00").compareTo(m.quoteTotalIncGst()));
    }
}
