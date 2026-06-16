package com.flooring.salesportal.order;

import com.flooring.salesportal.common.storage.FileStorageService;
import com.flooring.salesportal.order.InvoicePdfGenerator.InvoicePdfModel;
import com.flooring.salesportal.order.InvoicePdfModelAssembler.Inputs;
import com.flooring.salesportal.store.Store;
import com.flooring.salesportal.store.StoreRepository;
import com.flooring.salesportal.tenant.Business;
import com.flooring.salesportal.tenant.BusinessInvoiceConfigView;
import com.flooring.salesportal.tenant.BusinessRepository;
import com.flooring.salesportal.user.AppUser;
import com.flooring.salesportal.user.AppUserRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Optional;
import javax.imageio.ImageIO;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 15C — unit tests for {@link InvoicePdfModelAssembler}: the single enrichment path all three
 * invoice-PDF build sites (Create/Rewrite, Accept, Payment) route through. Proves tenant config / store
 * / salesperson / logo / per-type terms are wired in, that lookups are tenant-scoped (business id), and
 * that every missing/unsafe input fails soft instead of throwing.
 */
class InvoicePdfModelAssemblerTest {

    private static final long BUSINESS_ID = 1L;
    private static final int STORE_ID = 7;
    private static final long USER_ID = 9L;
    private static final byte[] ONE_PIXEL_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    private BusinessRepository businessRepository;
    private StoreRepository storeRepository;
    private AppUserRepository appUserRepository;
    private FileStorageService fileStorageService;
    private InvoicePdfModelAssembler assembler;

    @BeforeEach
    void setUp() {
        businessRepository = mock(BusinessRepository.class);
        storeRepository = mock(StoreRepository.class);
        appUserRepository = mock(AppUserRepository.class);
        fileStorageService = mock(FileStorageService.class);
        assembler = new InvoicePdfModelAssembler(
                businessRepository, storeRepository, appUserRepository,
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

    private AppUser salesperson() {
        AppUser u = new AppUser();
        u.setUserId(USER_ID);
        u.setBusinessId(BUSINESS_ID);
        u.setFirstName("Liam");
        u.setLastName("Carter");
        u.setSalespersonCode("LC1");
        return u;
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

    private Inputs inputs(SalesOrder order) {
        return new Inputs(
                business(null), order, 1,
                LocalDate.of(2026, 4, 14), LocalDate.of(2026, 4, 29),
                "James Wilson", "42 Oxford Street", "Paddington NSW 2021",
                "Supply and install plush carpet.",
                new BigDecimal("924.00"), new BigDecimal("500.00"), new BigDecimal("424.00"),
                null, null, null);
    }

    private Inputs inputs(Business business, SalesOrder order) {
        return new Inputs(
                business, order, 1,
                LocalDate.of(2026, 4, 14), LocalDate.of(2026, 4, 29),
                "James Wilson", "42 Oxford Street", "Paddington NSW 2021",
                "Supply and install plush carpet.",
                new BigDecimal("924.00"), new BigDecimal("500.00"), new BigDecimal("424.00"),
                null, null, null);
    }

    @Test
    void enrichesSoftOrderWithConfigStoreSalespersonAndLogo() {
        BusinessInvoiceConfigView cfg = config("<p>Soft flooring terms.</p>", "<p>Hard flooring terms.</p>");
        when(businessRepository.findInvoiceConfigByBusinessId(BUSINESS_ID)).thenReturn(Optional.of(cfg));
        when(storeRepository.findByStoreIdAndBusinessId(STORE_ID, BUSINESS_ID)).thenReturn(Optional.of(store()));
        when(appUserRepository.findByUserIdAndBusinessId(USER_ID, BUSINESS_ID)).thenReturn(Optional.of(salesperson()));
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
        Assertions.assertNotNull(m.termsHtml());
        Assertions.assertTrue(m.termsHtml().contains("Soft flooring terms."), m.termsHtml());
        Assertions.assertFalse(m.termsHtml().contains("Hard flooring terms."), "must not include hard terms");
        Assertions.assertFalse(m.termsOnSeparatePage(), "SOFT terms render inline (page 1)");

        // Lookups are tenant-scoped to the session business.
        verify(storeRepository).findByStoreIdAndBusinessId(STORE_ID, BUSINESS_ID);
        verify(appUserRepository).findByUserIdAndBusinessId(USER_ID, BUSINESS_ID);
    }

    @Test
    void hardOrderSelectsHardTermsOnSeparatePage() {
        BusinessInvoiceConfigView cfg = config("<p>Soft flooring terms.</p>", "<p>Hard flooring terms.</p>");
        when(businessRepository.findInvoiceConfigByBusinessId(BUSINESS_ID)).thenReturn(Optional.of(cfg));
        when(storeRepository.findByStoreIdAndBusinessId(STORE_ID, BUSINESS_ID)).thenReturn(Optional.of(store()));
        when(appUserRepository.findByUserIdAndBusinessId(USER_ID, BUSINESS_ID)).thenReturn(Optional.of(salesperson()));

        InvoicePdfModel m = assembler.assemble(inputs(order("HARD")));

        Assertions.assertEquals("Hard Flooring", m.flooringTypeLabel());
        Assertions.assertNotNull(m.termsHtml());
        Assertions.assertTrue(m.termsHtml().contains("Hard flooring terms."), m.termsHtml());
        Assertions.assertFalse(m.termsHtml().contains("Soft flooring terms."), "must not include soft terms");
        Assertions.assertTrue(m.termsOnSeparatePage(), "HARD terms render on a dedicated page");
    }

    @Test
    void missingConfigFailsSoft() {
        when(businessRepository.findInvoiceConfigByBusinessId(BUSINESS_ID)).thenReturn(Optional.empty());
        when(storeRepository.findByStoreIdAndBusinessId(STORE_ID, BUSINESS_ID)).thenReturn(Optional.of(store()));
        when(appUserRepository.findByUserIdAndBusinessId(USER_ID, BUSINESS_ID)).thenReturn(Optional.of(salesperson()));

        InvoicePdfModel m = assembler.assemble(inputs(order("SOFT")));

        Assertions.assertNull(m.abn());
        Assertions.assertNull(m.bankName());
        Assertions.assertNull(m.termsHtml());
        // Store + salesperson still resolved.
        Assertions.assertEquals("Sydney CBD", m.storeName());
        Assertions.assertEquals("Liam Carter", m.salespersonName());
    }

    @Test
    void missingStoreFailsSoft() {
        BusinessInvoiceConfigView cfg = config("<p>Soft.</p>", null);
        when(businessRepository.findInvoiceConfigByBusinessId(BUSINESS_ID)).thenReturn(Optional.of(cfg));
        when(storeRepository.findByStoreIdAndBusinessId(STORE_ID, BUSINESS_ID)).thenReturn(Optional.empty());
        when(appUserRepository.findByUserIdAndBusinessId(USER_ID, BUSINESS_ID)).thenReturn(Optional.of(salesperson()));

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
        when(appUserRepository.findByUserIdAndBusinessId(USER_ID, BUSINESS_ID)).thenReturn(Optional.empty());

        InvoicePdfModel m = assembler.assemble(inputs(order("SOFT")));
        Assertions.assertNull(m.salespersonName());
    }

    @Test
    void unsupportedLogoTypeFailsSoftAndNeverReads() {
        when(businessRepository.findInvoiceConfigByBusinessId(BUSINESS_ID)).thenReturn(Optional.empty());
        when(storeRepository.findByStoreIdAndBusinessId(STORE_ID, BUSINESS_ID)).thenReturn(Optional.of(store()));
        when(appUserRepository.findByUserIdAndBusinessId(USER_ID, BUSINESS_ID)).thenReturn(Optional.of(salesperson()));

        InvoicePdfModel m = assembler.assemble(inputs(business("/uploads/1/branding/logo.gif"), order("SOFT")));

        Assertions.assertNull(m.logoDataUri(), "unsupported logo type falls back to business name");
        verify(fileStorageService, never()).readWithLimit(eq("/uploads/1/branding/logo.gif"), anyLong());
    }

    @Test
    void unreadableLogoFailsSoftAndNeverThrows() {
        when(businessRepository.findInvoiceConfigByBusinessId(BUSINESS_ID)).thenReturn(Optional.empty());
        when(storeRepository.findByStoreIdAndBusinessId(STORE_ID, BUSINESS_ID)).thenReturn(Optional.of(store()));
        when(appUserRepository.findByUserIdAndBusinessId(USER_ID, BUSINESS_ID)).thenReturn(Optional.of(salesperson()));
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
        when(appUserRepository.findByUserIdAndBusinessId(USER_ID, BUSINESS_ID)).thenReturn(Optional.empty());
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
    void blankTermsYieldNullTerms() {
        BusinessInvoiceConfigView cfg = config("   ", null);
        when(businessRepository.findInvoiceConfigByBusinessId(BUSINESS_ID)).thenReturn(Optional.of(cfg));
        when(storeRepository.findByStoreIdAndBusinessId(STORE_ID, BUSINESS_ID)).thenReturn(Optional.of(store()));
        when(appUserRepository.findByUserIdAndBusinessId(USER_ID, BUSINESS_ID)).thenReturn(Optional.of(salesperson()));

        InvoicePdfModel m = assembler.assemble(inputs(order("SOFT")));
        Assertions.assertNull(m.termsHtml());
    }

    @Test
    void carriesFrozenSnapshotInputsThroughUnchanged() {
        when(businessRepository.findInvoiceConfigByBusinessId(BUSINESS_ID)).thenReturn(Optional.empty());
        when(storeRepository.findByStoreIdAndBusinessId(STORE_ID, BUSINESS_ID)).thenReturn(Optional.empty());
        when(appUserRepository.findByUserIdAndBusinessId(USER_ID, BUSINESS_ID)).thenReturn(Optional.empty());

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
    }
}
