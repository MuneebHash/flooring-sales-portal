package com.flooring.salesportal.tenant;

import com.flooring.salesportal.common.error.ErrorCode;
import com.flooring.salesportal.common.error.NotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BusinessSlugResolverTest {

    @Mock
    private BusinessRepository businessRepository;

    @InjectMocks
    private BusinessSlugResolver resolver;

    @Test
    void activeSlug_returnsBusiness() {
        Business b = new Business();
        b.setBusinessId(1L);
        b.setName("Aussie Floors Group");
        b.setSlug("aussie-floors-group");
        b.setActive(true);
        when(businessRepository.findBySlug("aussie-floors-group")).thenReturn(Optional.of(b));

        Business result = resolver.resolveActive("aussie-floors-group");

        assertThat(result).isSameAs(b);
    }

    @Test
    void inactiveSlug_throwsNotFoundWithGenericCode() {
        Business b = new Business();
        b.setActive(false);
        when(businessRepository.findBySlug("inactive-co")).thenReturn(Optional.of(b));

        assertThatThrownBy(() -> resolver.resolveActive("inactive-co"))
                .isInstanceOf(NotFoundException.class)
                .satisfies(ex -> {
                    NotFoundException nfe = (NotFoundException) ex;
                    assertThat(nfe.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
                    assertThat(nfe.getStatus().value()).isEqualTo(404);
                });
    }

    @Test
    void unknownSlug_throwsNotFound() {
        when(businessRepository.findBySlug("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolveActive("nope"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void nullSlug_throwsNotFoundWithoutRepoCall() {
        assertThatThrownBy(() -> resolver.resolveActive(null))
                .isInstanceOf(NotFoundException.class);

        verifyNoInteractions(businessRepository);
    }

    @Test
    void blankSlug_throwsNotFoundWithoutRepoCall() {
        assertThatThrownBy(() -> resolver.resolveActive("   "))
                .isInstanceOf(NotFoundException.class);

        verifyNoInteractions(businessRepository);
    }
}
