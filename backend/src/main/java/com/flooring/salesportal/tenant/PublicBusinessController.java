package com.flooring.salesportal.tenant;

import com.flooring.salesportal.common.api.ApiResponse;
import com.flooring.salesportal.tenant.dto.PublicBusinessResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public, no-login tenant branding lookup.
 *
 * <p>Returns only public tenant branding. It resolves active businesses through
 * {@link BusinessSlugResolver}, which returns a clean 404 for unknown, inactive, or reserved
 * slugs. This endpoint is public because it deliberately does NOT call RequestContextGuard;
 * protected tenant/store/user data belongs behind authenticated endpoints. Private tenant config
 * (ABN, bank details, T&Cs, Stripe link, quick-add descriptions) is never exposed here.
 */
@RestController
@RequestMapping("/api/v1/public/businesses")
public class PublicBusinessController {

    private final BusinessSlugResolver businessSlugResolver;

    public PublicBusinessController(BusinessSlugResolver businessSlugResolver) {
        this.businessSlugResolver = businessSlugResolver;
    }

    @GetMapping("/{slug}")
    public ApiResponse<PublicBusinessResponse> getPublicBusiness(@PathVariable String slug) {
        Business business = businessSlugResolver.resolveActive(slug);
        return ApiResponse.ok(new PublicBusinessResponse(
                business.getName(),
                business.getLogoPath(),
                business.getAccentColour()));
    }
}
