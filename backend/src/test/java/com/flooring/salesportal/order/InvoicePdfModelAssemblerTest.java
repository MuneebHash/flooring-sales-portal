package com.flooring.salesportal.order;

import com.flooring.salesportal.common.storage.FileStorageService;
import com.flooring.salesportal.order.InvoicePdfGenerator.InvoicePdfModel;
import com.flooring.salesportal.order.InvoicePdfModelAssembler.Inputs;
import com.flooring.salesportal.store.Store;
import com.flooring.salesportal.store.StoreRepository;
import com.flooring.salesportal.tenant.Business;
import com.flooring.salesportal.tenant.BusinessInvoiceConfigView;
import com.flooring.salesportal.tenant.BusinessRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import javax.imageio.ImageIO;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Phase 15C — unit tests for {@link InvoicePdfModelAssembler}: the single enrichment path all three
 * invoice-PDF build sites (Create/Rewrite, Accept, Payment) route through. Proves tenant config / store
 * / salesperson / logo are wired in, that lookups are tenant-scoped (business id), and that every
 * missing/unsafe input fails soft instead of throwing.
 *
 * <p>Phase 16G PR 2 (#125): the Invoice To name, billing lines and terms are the version's stored words,
 * passed in through {@link Inputs} and rendered verbatim - for both terms sources, including null.
 * {@link InvoicePdfModelAssembler#assemble} never reads the business's per-type terms; only the
 * freeze-time reader {@link InvoicePdfModelAssembler#businessTermsHtml} (Create / Rewrite) does.
 */
class InvoicePdfModelAssemblerTest {

    private static final long BUSINESS_ID = 1L;
    private static final int STORE_ID = 7;
    private static final long USER_ID = 9L;
    private static final byte[] ONE_PIXEL_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    private BusinessRepository businessRepository;
    private StoreRepository storeRepository;
    private OrderSalespersonResolver salespersonResolver;
    private FileStorageService fileStorageService;
    private InvoicePdfModelAssembler assembler;

    @BeforeEach
    void setUp() {
        businessRepository = mock(BusinessRepository.class);
        storeRepository = mock(StoreRepository.class);
        // The salesperson lookup moved to the shared OrderSalespersonResolver (used by both the PDF
        // and the on-screen workspace read); the assembler now delegates to it. Its own
        // first/last-name + tenant-scoping logic is covered by OrderSalespersonResolverTest.
        salespersonResolver = mock(OrderSalespersonResolver.class);
        fileStorageService = mock(FileStorageService.class);
        assembler = new InvoicePdfModelAssembler(
                businessRepository, storeRepository, salespersonResolver,
                fileStorageService, new InvoiceTermsSanitizer());
    }

    private Business business(String logoPath) {
        Business b = new Business();
        b.setBusinessId(BUSINESS_ID);
        b.setName("Aussie Floors Group");
        b.setLogoPath(logoPath);
        return b;
    }

    private SalesOrder order(String flooringType) {
        SalesOrder order = mock(SalesOrder.class);
        when(order.getFlooringType()).thenReturn(flooringType);
        when(order.getStoreId()).thenReturn(STORE_ID);
        when(order.getUserId()).thenReturn(USER_ID);
        when(order.getOrderNumber()).thenReturn("SYD-CBD.LC1.00001");
        return order;
    }

    private Store store() {
        Store s = new Store();
        s.setStoreId(STORE_ID);
        s.setBusinessId(BUSINESS_ID);
        s.setName("Sydney CBD");
        s.setStoreCode("SYD-CBD");
        s.setPhone("02 9000 0000");
        s.setEmail("cbd@aussiefloors.example");
        s.setStreet("100 George Street");
        s.setSuburb("Sydney");
        s.setStateCode("NSW");
        s.setPostcode("2000");
        return s;
    }

    private BusinessInvoiceConfigView config(String termsSoft, String termsHard) {
        BusinessInvoiceConfigView v = mock(BusinessInvoiceConfigView.class);
        when(v.getAbn()).thenReturn("11 222 333 444");
        when(v.getBankName()).thenReturn("Example Bank");
        when(v.getBsb()).thenReturn("062-000");
        when(v.getAccountName()).thenReturn("Aussie Floors Pty Ltd");
        when(v.getAccountNumber()).thenReturn("12345678");
        when(v.getTermsSoft()).thenReturn(termsSoft);
        when(v.getTermsHard()).thenReturn(termsHard);
        return v;
    }

    /**
     * The terms STORED on the version under render (a BUSINESS row: frozen at Create / Rewrite). Distinct
     * from every config value below, so a model holding it proves the stored words - not the business's
     * current terms - reached the PDF.
     */
    private static final String STORED_BUSINESS_TERMS = "<p>Stored business terms frozen on this version.</p>";

    private Inputs inputs(SalesOrder order) {
        return inputs(business(null), order, InvoiceTermsSelection.forInvoice(null, STORED_BUSINESS_TERMS));
    }

    private Inputs inputs(Business business, SalesOrder order) {
        return inputs(business, order, InvoiceTermsSelection.forInvoice(null, STORED_BUSINESS_TERMS));
    }

    // Every build site passes an explicit terms selection: the source and terms of the row it writes.
    private Inputs inputs(Business business, SalesOrder order, InvoiceTermsSelection terms) {
        return new Inputs(
                business, order, 1,
                LocalDate.of(2026, 4, 14), LocalDate.of(2026, 4, 29),
                "James Wilson", "42 Oxford Street", "Paddington NSW 2021",
                "Supply and install plush carpet.",
                new BigDecimal("924.00"), new BigDecimal("500.00"), new BigDecimal("424.00"),
                null, null, null,
                terms);
    }

    @Test
    void enrichesSoftOrderWithConfigStoreSalespersonAndLogo() {
        BusinessInvoiceConfigView cfg = config("<p>Soft flooring terms.</p>", "<p>Hard flooring terms.</p>");
        when(businessRepository.findInvoiceConfigByBusinessId(BUSINESS_ID)).thenReturn(Optional.of(cfg));
        when(storeRepository.findByStoreIdAndBusinessId(STORE_ID, BUSINESS_ID)).thenReturn(Optional.of(store()));
        when(salespersonResolver.resolveName(USER_ID, BUSINESS_ID)).thenReturn("Liam Carter");
        when(fileStorageService.readWithLimit(eq("/uploads/1/branding/logo.png"), anyLong())).thenReturn(ONE_PIXEL_PNG);

        InvoicePdfModel m = assembler.assemble(inputs(business("/uploads/1/branding/logo.png"), order("SOFT")));

        Assertions.assertEquals("Aussie Floors Group", m.businessName());
        Assertions.assertEquals("11 222 333 444", m.abn());
        Assertions.assertEquals("Soft Flooring", m.flooringTypeLabel());
        Assertions.assertEquals("Sydney CBD", m.storeName());
        Assertions.assertEquals("100 George Street", m.storeAddressLine1());
        Assertions.assertEquals("Sydney NSW 2000", m.storeAddressLine2());
        Assertions.assertEquals("02 9000 0000", m.storePhone());
        Assertions.assertEquals("cbd@aussiefloors.example", m.storeEmail());
        Assertions.assertEquals("Liam Carter", m.salespersonName());
        Assertions.assertEquals("Example Bank", m.bankName());
        Assertions.assertEquals("062-000", m.bsb());
        Assertions.assertEquals("Aussie Floors Pty Ltd", m.accountName());
        Assertions.assertEquals("12345678", m.accountNumber());
        Assertions.assertNotNull(m.logoDataUri());
        Assertions.assertTrue(m.logoDataUri().startsWith("data:image/png;base64,"), m.logoDataUri());
        // 16G PR 2: the version's stored terms, verbatim - never the config's current soft or hard terms.
        Assertions.assertEquals(STORED_BUSINESS_TERMS, m.termsHtml());
        Assertions.assertFalse(m.termsOnSeparatePage(),
                "SOFT keeps termsOnSeparatePage false (unread by the template; terms still get a dedicated page)");

        // Lookups are tenant-scoped to the session business; salesperson resolution is delegated
        // to the shared resolver, called with the order's user id + the session business id.
        verify(storeRepository).findByStoreIdAndBusinessId(STORE_ID, BUSINESS_ID);
        verify(salespersonResolver).resolveName(USER_ID, BUSINESS_ID);
    }

    @Test
    void hardOrderRendersTheStoredTermsOnSeparatePage_notTheConfigsHardTerms() {
        // Per-type SOFT / HARD selection now happens once, when Create / Rewrite freeze the terms
        // (businessTermsHtml, below); the render takes the stored value whatever the type.
        BusinessInvoiceConfigView cfg = config("<p>Soft flooring terms.</p>", "<p>Hard flooring terms.</p>");
        when(businessRepository.findInvoiceConfigByBusinessId(BUSINESS_ID)).thenReturn(Optional.of(cfg));
        when(storeRepository.findByStoreIdAndBusinessId(STORE_ID, BUSINESS_ID)).thenReturn(Optional.of(store()));
        when(salespersonResolver.resolveName(USER_ID, BUSINESS_ID)).thenReturn("Liam Carter");

        InvoicePdfModel m = assembler.assemble(inputs(order("HARD")));

        Assertions.assertEquals("Hard Flooring", m.flooringTypeLabel());
        Assertions.assertEquals(STORED_BUSINESS_TERMS, m.termsHtml(),
                "a HARD version renders its stored terms, never the business's current hard terms");
        Assertions.assertTrue(m.termsOnSeparatePage(), "HARD terms render on a dedicated page");
    }

    @Test
    void missingConfigFailsSoft() {
        when(businessRepository.findInvoiceConfigByBusinessId(BUSINESS_ID)).thenReturn(Optional.empty());
        when(storeRepository.findByStoreIdAndBusinessId(STORE_ID, BUSINESS_ID)).thenReturn(Optional.of(store()));
        when(salespersonResolver.resolveName(USER_ID, BUSINESS_ID)).thenReturn("Liam Carter");

        InvoicePdfModel m = assembler.assemble(inputs(order("SOFT")));

        Assertions.assertNull(m.abn());
        Assertions.assertNull(m.bankName());
        // 16G PR 2: the stored terms do not depend on the config row - they still render.
        Assertions.assertEquals(STORED_BUSINESS_TERMS, m.termsHtml());
        // Store + salesperson still resolved.
        Assertions.assertEquals("Sydney CBD", m.storeName());
        Assertions.assertEquals("Liam Carter", m.salespersonName());
    }

    @Test
    void missingStoreFailsSoft() {
        BusinessInvoiceConfigView cfg = config("<p>Soft.</p>", null);
        when(businessRepository.findInvoiceConfigByBusinessId(BUSINESS_ID)).thenReturn(Optional.of(cfg));
        when(storeRepository.findByStoreIdAndBusinessId(STORE_ID, BUSINESS_ID)).thenReturn(Optional.empty());
        when(salespersonResolver.resolveName(USER_ID, BUSINESS_ID)).thenReturn("Liam Carter");

        InvoicePdfModel m = assembler.assemble(inputs(order("SOFT")));

        Assertions.assertNull(m.storeName());
        Assertions.assertNull(m.storeAddressLine1());
        Assertions.assertNull(m.storePhone());
        Assertions.assertNull(m.storeEmail());
    }

    @Test
    void missingSalespersonFailsSoft() {
        when(businessRepository.findInvoiceConfigByBusinessId(BUSINESS_ID)).thenReturn(Optional.empty());
        when(storeRepository.findByStoreIdAndBusinessId(STORE_ID, BUSINESS_ID)).thenReturn(Optional.of(store()));
        when(salespersonResolver.resolveName(USER_ID, BUSINESS_ID)).thenReturn(null);

        InvoicePdfModel m = assembler.assemble(inputs(order("SOFT")));
        Assertions.assertNull(m.salespersonName());
    }

    @Test
    void unsupportedLogoTypeFailsSoftAndNeverReads() {
        when(businessRepository.findInvoiceConfigByBusinessId(BUSINESS_ID)).thenReturn(Optional.empty());
        when(storeRepository.findByStoreIdAndBusinessId(STORE_ID, BUSINESS_ID)).thenReturn(Optional.of(store()));
        when(salespersonResolver.resolveName(USER_ID, BUSINESS_ID)).thenReturn("Liam Carter");

        InvoicePdfModel m = assembler.assemble(inputs(business("/uploads/1/branding/logo.gif"), order("SOFT")));

        Assertions.assertNull(m.logoDataUri(), "unsupported logo type falls back to business name");
        verify(fileStorageService, never()).readWithLimit(eq("/uploads/1/branding/logo.gif"), anyLong());
    }

    @Test
    void unreadableLogoFailsSoftAndNeverThrows() {
        when(businessRepository.findInvoiceConfigByBusinessId(BUSINESS_ID)).thenReturn(Optional.empty());
        when(storeRepository.findByStoreIdAndBusinessId(STORE_ID, BUSINESS_ID)).thenReturn(Optional.of(store()));
        when(salespersonResolver.resolveName(USER_ID, BUSINESS_ID)).thenReturn("Liam Carter");
        when(fileStorageService.readWithLimit(eq("/uploads/1/branding/logo.png"), anyLong()))
                .thenThrow(new UncheckedIOException("missing", new java.io.IOException("nope")));

        InvoicePdfModel m = Assertions.assertDoesNotThrow(() ->
                assembler.assemble(inputs(business("/uploads/1/branding/logo.png"), order("SOFT"))));
        Assertions.assertNull(m.logoDataUri(), "unreadable logo falls back to business name");
    }

    // ------------------------------------------------------------------
    // Phase 15C hardening — logo magic-byte + decode validation (must never 500 the invoice)
    // ------------------------------------------------------------------

    /**
     * Resolve only the logo: stub config/store/salesperson empty, and stub the bounded read for
     * {@code path} to return {@code logoBytes} (a {@code null} value simulates the over-limit rejection
     * {@code readWithLimit} returns).
     */
    private InvoicePdfModel assembleWithLogo(String path, byte[] logoBytes) {
        when(businessRepository.findInvoiceConfigByBusinessId(BUSINESS_ID)).thenReturn(Optional.empty());
        when(storeRepository.findByStoreIdAndBusinessId(STORE_ID, BUSINESS_ID)).thenReturn(Optional.empty());
        when(salespersonResolver.resolveName(USER_ID, BUSINESS_ID)).thenReturn(null);
        when(fileStorageService.readWithLimit(eq(path), anyLong())).thenReturn(logoBytes);
        return assembler.assemble(inputs(business(path), order("SOFT")));
    }

    @Test
    void garbageBytesUnderPngPathFailSoft() {
        byte[] garbage = "this is definitely not an image".getBytes(StandardCharsets.UTF_8);
        InvoicePdfModel m = Assertions.assertDoesNotThrow(() ->
                assembleWithLogo("/uploads/1/branding/logo.png", garbage));
        Assertions.assertNull(m.logoDataUri(), "non-image bytes must not produce a data URI");
    }

    @Test
    void pngMagicButCorruptBytesFailSoft() {
        // Valid PNG signature followed by truncated/garbage data: passes the magic + extension check but
        // must fail the decode step (ImageIO.read returns null or throws) -> null, no throw.
        byte[] corruptPng = {
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x01, 0x02, 0x03, 0x04};
        InvoicePdfModel m = Assertions.assertDoesNotThrow(() ->
                assembleWithLogo("/uploads/1/branding/logo.png", corruptPng));
        Assertions.assertNull(m.logoDataUri(), "corrupt PNG must not produce a data URI");
    }

    @Test
    void validOnePixelPngProducesPngDataUri() {
        InvoicePdfModel m = assembleWithLogo("/uploads/1/branding/logo.png", ONE_PIXEL_PNG);
        Assertions.assertNotNull(m.logoDataUri());
        Assertions.assertTrue(m.logoDataUri().startsWith("data:image/png;base64,"), m.logoDataUri());
    }

    @Test
    void oversizedLogoRejectedByBoundedReadFailsSoft() {
        // readWithLimit returns null for an over-cap file WITHOUT materialising it (proven end-to-end in
        // FileStorageServiceTest); here we assert the assembler treats that null as fail-soft. No large
        // array is built in memory — the over-limit rejection happens in the bounded read, not here.
        InvoicePdfModel m = Assertions.assertDoesNotThrow(() ->
                assembleWithLogo("/uploads/1/branding/logo.png", null));
        Assertions.assertNull(m.logoDataUri(), "over-limit logo must fall back to business name");
    }

    @Test
    void jpegBytesUnderPngPathFailSoftOnTypeMismatch() {
        // Real JPEG magic but a .png path -> extension/content mismatch -> null.
        byte[] jpegMagic = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10};
        InvoicePdfModel m = Assertions.assertDoesNotThrow(() ->
                assembleWithLogo("/uploads/1/branding/logo.png", jpegMagic));
        Assertions.assertNull(m.logoDataUri(), "JPEG bytes under a .png path must fail soft");
    }

    @Test
    void validJpegUnderJpgPathProducesJpegDataUri() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "jpg", baos);
        byte[] jpeg = baos.toByteArray();
        InvoicePdfModel m = assembleWithLogo("/uploads/1/branding/logo.jpg", jpeg);
        Assertions.assertNotNull(m.logoDataUri());
        Assertions.assertTrue(m.logoDataUri().startsWith("data:image/jpeg;base64,"), m.logoDataUri());
    }

    @Test
    void blankConfigTerms_neverChangeTheStoredTerms_eitherWay() {
        // The business's current terms are blank / unset: a version that stored terms still renders them,
        // and a version that stored null still renders none (blank -> null applies only at the freeze).
        BusinessInvoiceConfigView cfg = config("   ", null);
        when(businessRepository.findInvoiceConfigByBusinessId(BUSINESS_ID)).thenReturn(Optional.of(cfg));
        when(storeRepository.findByStoreIdAndBusinessId(STORE_ID, BUSINESS_ID)).thenReturn(Optional.of(store()));
        when(salespersonResolver.resolveName(USER_ID, BUSINESS_ID)).thenReturn("Liam Carter");

        Assertions.assertEquals(STORED_BUSINESS_TERMS, assembler.assemble(inputs(order("SOFT"))).termsHtml());
        Assertions.assertNull(assembler.assemble(inputs(business(null), order("SOFT"),
                InvoiceTermsSelection.forInvoice(null, null))).termsHtml());
    }

    @Test
    void carriesFrozenSnapshotInputsThroughUnchanged() {
        when(businessRepository.findInvoiceConfigByBusinessId(BUSINESS_ID)).thenReturn(Optional.empty());
        when(storeRepository.findByStoreIdAndBusinessId(STORE_ID, BUSINESS_ID)).thenReturn(Optional.empty());
        when(salespersonResolver.resolveName(USER_ID, BUSINESS_ID)).thenReturn(null);

        InvoicePdfModel m = assembler.assemble(inputs(order("SOFT")));

        Assertions.assertEquals("SYD-CBD.LC1.00001", m.orderNumber());
        Assertions.assertEquals("James Wilson", m.customerName());
        Assertions.assertEquals("42 Oxford Street", m.billingLine1());
        Assertions.assertEquals("Paddington NSW 2021", m.billingLine2());
        Assertions.assertEquals("Supply and install plush carpet.", m.detailsOfSale());
        Assertions.assertEquals(new BigDecimal("924.00"), m.salePriceIncGst());
        Assertions.assertEquals(new BigDecimal("500.00"), m.totalPaid());
        Assertions.assertEquals(new BigDecimal("424.00"), m.balanceDue());
        Assertions.assertEquals(1, m.versionNumber());
        Assertions.assertEquals(STORED_BUSINESS_TERMS, m.termsHtml());
    }

    @Test
    void nullIdentityWords_reachTheModelAsNull_neverFilledFromAnywhere() {
        // Characterisation of the unchanged pass-through (16G PR 2 relies on it): a version whose stored
        // Invoice To name or billing lines are null reaches the model as null; the assembler has no live
        // customer or address to fill them from. The end-to-end proof that a stored null renders as an
        // empty Invoice To block is in InvoiceFrozenWordsTest and QuoteInvoiceFrozenWordsTest.
        when(businessRepository.findInvoiceConfigByBusinessId(BUSINESS_ID)).thenReturn(Optional.empty());
        when(storeRepository.findByStoreIdAndBusinessId(STORE_ID, BUSINESS_ID)).thenReturn(Optional.of(store()));
        when(salespersonResolver.resolveName(USER_ID, BUSINESS_ID)).thenReturn("Liam Carter");

        InvoicePdfModel m = assembler.assemble(new Inputs(
                business(null), order("SOFT"), 3,
                LocalDate.of(2026, 4, 14), LocalDate.of(2026, 4, 29),
                null, null, null,
                "Supply and install plush carpet.",
                new BigDecimal("924.00"), new BigDecimal("500.00"), new BigDecimal("424.00"),
                null, null, null,
                InvoiceTermsSelection.forInvoice(null, null)));

        Assertions.assertNull(m.customerName());
        Assertions.assertNull(m.billingLine1());
        Assertions.assertNull(m.billingLine2());
        Assertions.assertNull(m.termsHtml());
    }

    // ------------------------------------------------------------------
    // Phase 16G PR 2 (#125) - stored terms render verbatim for BOTH sources (QUOTE and BUSINESS); the
    // render never reads the business's current terms. Sanitising happens once, at the freeze.
    // ------------------------------------------------------------------

    // Frozen terms holding markup the sanitizer WOULD strip (an id and a style attribute) plus
    // surrounding whitespace: a selection must hand them to the model verbatim (no re-sanitise, no
    // trim), which is only provable with input the sanitizer would change.
    private static final String FROZEN_QUOTE_TERMS =
            "  <p id=\"frozen\" style=\"color:red\">Frozen quote terms.</p>\n";
    private static final String LIVE_SOFT_TERMS = "<p>Live soft terms.</p>";
    private static final String LIVE_HARD_TERMS = "<p>Live hard terms.</p>";
    private static final Long SOURCE_QUOTE_VERSION_ID = 77L;

    /** Config with DIFFERENT business terms + a store + a salesperson, so only the stored terms can differ. */
    private void stubLayoutWithLiveTerms(String termsSoft, String termsHard) {
        // Build the config mock BEFORE the outer when(...): nesting its own stubbing inside thenReturn(...)
        // would leave the outer stubbing unfinished.
        BusinessInvoiceConfigView cfg = config(termsSoft, termsHard);
        when(businessRepository.findInvoiceConfigByBusinessId(BUSINESS_ID)).thenReturn(Optional.of(cfg));
        when(storeRepository.findByStoreIdAndBusinessId(STORE_ID, BUSINESS_ID)).thenReturn(Optional.of(store()));
        when(salespersonResolver.resolveName(USER_ID, BUSINESS_ID)).thenReturn("Liam Carter");
    }

    @Test
    void storedTerms_reachTheModelVerbatim_neverResanitised_forBothSources_evenWhenTheBusinessTermsDiffer() {
        stubLayoutWithLiveTerms(LIVE_SOFT_TERMS, LIVE_HARD_TERMS);
        // Fixture guard: the sanitizer really would change this input, so verbatim output proves no re-sanitise.
        Assertions.assertNotEquals(FROZEN_QUOTE_TERMS, new InvoiceTermsSanitizer().sanitize(FROZEN_QUOTE_TERMS));

        for (Long sourceQuoteVersionId : Arrays.asList(SOURCE_QUOTE_VERSION_ID, null)) {
            for (String flooringType : List.of("SOFT", "HARD")) {
                String label = (sourceQuoteVersionId == null ? "BUSINESS " : "QUOTE ") + flooringType;
                InvoicePdfModel m = assembler.assemble(inputs(business(null), order(flooringType),
                        InvoiceTermsSelection.forInvoice(sourceQuoteVersionId, FROZEN_QUOTE_TERMS)));

                Assertions.assertEquals(FROZEN_QUOTE_TERMS, m.termsHtml(),
                        () -> label + ": the stored terms must render verbatim, not the business's current terms");
                // Only the terms are frozen: the tenant config still feeds the other (live) layout fields.
                Assertions.assertEquals("11 222 333 444", m.abn(), label);
                Assertions.assertEquals("Example Bank", m.bankName(), label);
                Assertions.assertEquals("Sydney CBD", m.storeName(), label);
            }
        }
    }

    @Test
    void storedNullTerms_renderNoTerms_forBothSourcesAndTypes_evenWhenTheBusinessHasTerms() {
        stubLayoutWithLiveTerms(LIVE_SOFT_TERMS, LIVE_HARD_TERMS);

        for (Long sourceQuoteVersionId : Arrays.asList(SOURCE_QUOTE_VERSION_ID, null)) {
            for (String flooringType : List.of("SOFT", "HARD")) {
                InvoicePdfModel noTerms = assembler.assemble(inputs(business(null), order(flooringType),
                        InvoiceTermsSelection.forInvoice(sourceQuoteVersionId, null)));
                Assertions.assertNull(noTerms.termsHtml(),
                        () -> (sourceQuoteVersionId == null ? "BUSINESS " : "QUOTE ") + flooringType
                                + ": stored null terms must never fall back to the business's current terms");
            }
        }
    }

    @Test
    void sameRawMarkup_isSanitisedOnceWhenFrozen_thenRenderedVerbatimFromTheStoredSnapshot() {
        // The business's raw SOFT terms: businessTermsHtml (the Create / Rewrite freeze) sanitises them;
        // a version that stored those raw bytes (a copied quote snapshot) renders them verbatim - the
        // render never sanitises a second time.
        stubLayoutWithLiveTerms(FROZEN_QUOTE_TERMS, LIVE_HARD_TERMS);
        String expectedFrozen = new InvoiceTermsSanitizer().sanitize(FROZEN_QUOTE_TERMS);

        String frozen = assembler.businessTermsHtml(BUSINESS_ID, "SOFT");
        InvoicePdfModel fromFrozen = assembler.assemble(inputs(business(null), order("SOFT"),
                InvoiceTermsSelection.forInvoice(null, frozen)));
        InvoicePdfModel fromRaw = assembler.assemble(inputs(business(null), order("SOFT"),
                InvoiceTermsSelection.forInvoice(SOURCE_QUOTE_VERSION_ID, FROZEN_QUOTE_TERMS)));

        Assertions.assertEquals(expectedFrozen, frozen, "the freeze goes through the sanitizer");
        Assertions.assertFalse(frozen.contains("style="), frozen);
        Assertions.assertEquals(frozen, fromFrozen.termsHtml(), "the frozen value renders unchanged");
        Assertions.assertEquals(FROZEN_QUOTE_TERMS, fromRaw.termsHtml(), "a stored value is never re-sanitised");
    }

    @Test
    void assemble_neverReadsTheBusinessTerms_forEitherSource_includingNull() {
        BusinessInvoiceConfigView cfg = config(LIVE_SOFT_TERMS, LIVE_HARD_TERMS);
        when(businessRepository.findInvoiceConfigByBusinessId(BUSINESS_ID)).thenReturn(Optional.of(cfg));
        when(storeRepository.findByStoreIdAndBusinessId(STORE_ID, BUSINESS_ID)).thenReturn(Optional.of(store()));
        when(salespersonResolver.resolveName(USER_ID, BUSINESS_ID)).thenReturn("Liam Carter");

        for (Long sourceQuoteVersionId : Arrays.asList(SOURCE_QUOTE_VERSION_ID, null)) {
            for (String terms : Arrays.asList(FROZEN_QUOTE_TERMS, null)) {
                for (String flooringType : List.of("SOFT", "HARD")) {
                    assembler.assemble(inputs(business(null), order(flooringType),
                            InvoiceTermsSelection.forInvoice(sourceQuoteVersionId, terms)));
                }
            }
        }

        // The config row still feeds ABN / bank (live presentation), but no terms getter is ever read
        // at render time: no per-type terms, and no legacy single block.
        verify(cfg, never()).getTermsSoft();
        verify(cfg, never()).getTermsHard();
        verify(cfg, never()).getTermsAndConditions();
    }

    @Test
    void businessTermsHtml_returnsTheSanitisedPerTypeTerms_readByTheBusinessId() {
        // The Create / Rewrite freeze-time reader: the business's CURRENT per-type terms, sanitised once.
        String rawSoft = "<p style=\"color:red\" onclick=\"steal()\">Live soft terms.</p>";
        String rawHard = "<p id=\"h\">Live hard terms.</p><script>alert('x')</script>";
        BusinessInvoiceConfigView cfg = config(rawSoft, rawHard);
        when(businessRepository.findInvoiceConfigByBusinessId(BUSINESS_ID)).thenReturn(Optional.of(cfg));
        InvoiceTermsSanitizer sanitizer = new InvoiceTermsSanitizer();

        String soft = assembler.businessTermsHtml(BUSINESS_ID, "SOFT");
        String hard = assembler.businessTermsHtml(BUSINESS_ID, "HARD");

        // SOFT -> terms_soft, HARD -> terms_hard, each through the sanitizer (never the raw value).
        Assertions.assertEquals(sanitizer.sanitize(rawSoft), soft);
        Assertions.assertNotEquals(rawSoft, soft, "the raw business terms must not pass through unsanitised");
        Assertions.assertTrue(soft.contains("Live soft terms."), soft);
        Assertions.assertFalse(soft.contains("onclick") || soft.contains("style="), soft);
        Assertions.assertFalse(soft.contains("Live hard terms."), "SOFT must never get the hard terms");

        Assertions.assertEquals(sanitizer.sanitize(rawHard), hard);
        Assertions.assertTrue(hard.contains("Live hard terms."), hard);
        Assertions.assertFalse(hard.contains("script") || hard.contains("alert"), hard);
        Assertions.assertFalse(hard.contains("Live soft terms."), "HARD must never get the soft terms");

        // Tenant-scoped read (the business id) once per freeze; nothing else is touched.
        verify(businessRepository, times(2)).findInvoiceConfigByBusinessId(BUSINESS_ID);
        verifyNoInteractions(storeRepository, salespersonResolver, fileStorageService);
    }

    @Test
    void businessTermsHtml_returnsNullWhenTheTypesTermsAreBlankUnsetUnsafeOrTheConfigIsMissing() {
        // Blank SOFT terms and unset HARD terms; the legacy single block is set but never used.
        BusinessInvoiceConfigView blankAndUnset = config("   ", null);
        when(blankAndUnset.getTermsAndConditions()).thenReturn("<p>Legacy terms must never be used.</p>");
        when(businessRepository.findInvoiceConfigByBusinessId(BUSINESS_ID)).thenReturn(Optional.of(blankAndUnset));
        Assertions.assertNull(assembler.businessTermsHtml(BUSINESS_ID, "SOFT"), "blank business terms -> null");
        Assertions.assertNull(assembler.businessTermsHtml(BUSINESS_ID, "HARD"),
                "unset business terms -> null (no cross-type or legacy fallback)");

        // Terms that sanitise down to nothing visible -> null.
        BusinessInvoiceConfigView unsafeOrEmpty = config("<script>alert(1)</script>", "<p></p>");
        when(businessRepository.findInvoiceConfigByBusinessId(BUSINESS_ID)).thenReturn(Optional.of(unsafeOrEmpty));
        Assertions.assertNull(assembler.businessTermsHtml(BUSINESS_ID, "SOFT"));
        Assertions.assertNull(assembler.businessTermsHtml(BUSINESS_ID, "HARD"));

        // No tenant config row at all -> null.
        when(businessRepository.findInvoiceConfigByBusinessId(BUSINESS_ID)).thenReturn(Optional.empty());
        Assertions.assertNull(assembler.businessTermsHtml(BUSINESS_ID, "SOFT"));
        Assertions.assertNull(assembler.businessTermsHtml(BUSINESS_ID, "HARD"));
    }

    @Test
    void inputs_requireAnExplicitTermsSelection_nullIsRejected() {
        // Every build site states the source and stored terms of the row it writes; a null selection
        // could be mistaken for "no terms" or for a missing argument.
        Assertions.assertThrows(NullPointerException.class,
                () -> inputs(business(null), order("SOFT"), null));
    }
}
