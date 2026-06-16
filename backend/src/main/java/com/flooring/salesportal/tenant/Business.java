package com.flooring.salesportal.tenant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "business")
@Getter
@Setter
@NoArgsConstructor
public class Business {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "business_id")
    private Long businessId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "slug", nullable = false, unique = true)
    private String slug;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    // Public branding (exposed via GET /api/v1/public/businesses/{slug}). Nullable.
    // Private tenant fields added in V12 (abn, bank_*, terms_and_conditions, stripe_*,
    // invoice_template_key) and the per-flooring-type terms added in V13 (terms_hard,
    // terms_soft) are intentionally NOT mapped here so the public endpoint cannot leak
    // them; they are read by the authenticated invoice-config endpoint in Phase 15A
    // (GET /api/v1/{slug}/invoice-config) via a native-query projection, not the entity.
    @Column(name = "logo_path")
    private String logoPath;

    @Column(name = "accent_colour")
    private String accentColour;
}
