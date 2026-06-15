package com.flooring.salesportal.tenant;

import com.flooring.salesportal.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 14C — {@code GET /api/v1/{slug}/quick-descriptions}.
 *
 * <p>Tenant-scoped, authenticated read of a business's quick-add "details of sale" presets
 * (descriptions only, ordered by sort_order, {@code [BUSINESS_NAME]} substituted server-side).
 * Thin controller mirroring {@code OrderCatalogController}: it captures {@code slug} +
 * {@code HttpServletRequest} and delegates to the service, which applies the standard-protected
 * guard and business scoping. Returns the standard {@code ApiResponse} envelope wrapping a plain
 * string list ({@code { "data": ["..."] }}).
 */
@RestController
@RequestMapping("/api/v1/{slug}")
public class QuickDescriptionController {

    private final QuickDescriptionService quickDescriptionService;

    public QuickDescriptionController(QuickDescriptionService quickDescriptionService) {
        this.quickDescriptionService = quickDescriptionService;
    }

    @GetMapping("/quick-descriptions")
    public ApiResponse<List<String>> listQuickDescriptions(
            @PathVariable String slug,
            HttpServletRequest httpRequest) {
        return quickDescriptionService.listQuickDescriptions(slug, httpRequest);
    }
}
