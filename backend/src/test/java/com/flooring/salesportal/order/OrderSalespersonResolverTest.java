package com.flooring.salesportal.order;

import com.flooring.salesportal.user.AppUser;
import com.flooring.salesportal.user.AppUserRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Phase 15C PR2 — unit tests for {@link OrderSalespersonResolver}: the single order-bound salesperson
 * name source shared by the invoice PDF and the on-screen workspace read. Proves the lookup is
 * tenant-scoped (business id), joins first + last name with one space, and fails soft to {@code null}
 * (null user id / missing user / blank name) without throwing.
 */
class OrderSalespersonResolverTest {

    private static final long USER_ID = 9L;
    private static final long BUSINESS_ID = 1L;

    private AppUserRepository appUserRepository;
    private OrderSalespersonResolver resolver;

    @BeforeEach
    void setUp() {
        appUserRepository = mock(AppUserRepository.class);
        resolver = new OrderSalespersonResolver(appUserRepository);
    }

    private AppUser user(String firstName, String lastName) {
        AppUser u = new AppUser();
        u.setUserId(USER_ID);
        u.setBusinessId(BUSINESS_ID);
        u.setFirstName(firstName);
        u.setLastName(lastName);
        return u;
    }

    @Test
    void nullUserIdReturnsNullWithoutLookup() {
        Assertions.assertNull(resolver.resolveName(null, BUSINESS_ID));
        verifyNoInteractions(appUserRepository);
    }

    @Test
    void joinsFirstAndLastNameTenantScoped() {
        when(appUserRepository.findByUserIdAndBusinessId(USER_ID, BUSINESS_ID))
                .thenReturn(Optional.of(user("Liam", "Carter")));

        Assertions.assertEquals("Liam Carter", resolver.resolveName(USER_ID, BUSINESS_ID));
        // The lookup is scoped to the session business id (the order's user id can never resolve a
        // name from another tenant).
        verify(appUserRepository).findByUserIdAndBusinessId(USER_ID, BUSINESS_ID);
    }

    @Test
    void firstNameOnlyYieldsFirstName() {
        when(appUserRepository.findByUserIdAndBusinessId(USER_ID, BUSINESS_ID))
                .thenReturn(Optional.of(user("Liam", "   ")));
        Assertions.assertEquals("Liam", resolver.resolveName(USER_ID, BUSINESS_ID));
    }

    @Test
    void lastNameOnlyYieldsLastName() {
        when(appUserRepository.findByUserIdAndBusinessId(USER_ID, BUSINESS_ID))
                .thenReturn(Optional.of(user(null, "Carter")));
        Assertions.assertEquals("Carter", resolver.resolveName(USER_ID, BUSINESS_ID));
    }

    @Test
    void bothNamesBlankReturnsNull() {
        when(appUserRepository.findByUserIdAndBusinessId(USER_ID, BUSINESS_ID))
                .thenReturn(Optional.of(user("  ", null)));
        Assertions.assertNull(resolver.resolveName(USER_ID, BUSINESS_ID));
    }

    @Test
    void missingUserReturnsNull() {
        when(appUserRepository.findByUserIdAndBusinessId(USER_ID, BUSINESS_ID))
                .thenReturn(Optional.empty());
        Assertions.assertNull(resolver.resolveName(USER_ID, BUSINESS_ID));
    }

    @Test
    void neverUsesSalespersonCodeLookup() {
        when(appUserRepository.findByUserIdAndBusinessId(USER_ID, BUSINESS_ID))
                .thenReturn(Optional.of(user("Liam", "Carter")));
        resolver.resolveName(USER_ID, BUSINESS_ID);
        // Order-bound resolution must go through the user-id lookup, never the salesperson_code one.
        verify(appUserRepository, never()).findByBusinessIdAndSalespersonCode(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString());
    }
}
