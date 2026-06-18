-- Dev-only seed: demo per-flooring-type invoice terms.
-- Safe to rerun.
-- Updates EVERY business currently in the local DB.
-- Business name inside the terms comes from business.name.

UPDATE business b
SET
    terms_soft = replace($soft$
TERMS AND CONDITIONS — SOFT FLOORING

1. This agreement is for the sale and installation of the goods described on this invoice, at the value shown on this invoice.
2. A deposit may be required before work proceeds. The balance must be paid before installation, unless otherwise agreed in writing by {BUSINESS_NAME}.
3. If payment is made by cheque, the cheque must clear before installation.
4. The customer must confirm the lay date in advance and must ensure clear, clean access to all areas to be laid.
5. Furniture removal, replacement, uplift of existing floor coverings, floor preparation and door trimming are the customer's responsibility unless clearly stated in the details of sale.
6. Any additional floor preparation or site costs not included in the invoice may be charged separately.
7. The customer must provide adequate 240V power for installation.
8. Colour shades, dye lots and product appearance may vary from samples.
9. Carpet and soft-flooring products may show pile shading, tracking, watermarking, flattening, matting, shedding or other normal characteristics of the product.
10. No carpet is fully stainproof. Stain protection does not cover all substances, misuse, poor maintenance or damage outside the product warranty.
11. If the customer has a complaint, reasonable access must be provided so the product and installation can be inspected.
12. If payment is not made by the agreed terms, warranties may be affected and overdue amounts may attract recovery costs.
13. These terms form part of the agreement between the customer and {BUSINESS_NAME}. Nothing in these terms limits rights available under Australian Consumer Law.
$soft$, '{BUSINESS_NAME}', b.name),

    terms_hard = replace($hard$
TERMS AND CONDITIONS — HARD FLOORING

1. This agreement is for the sale and installation or supply of the hard-flooring goods described on this invoice, at the value shown on this invoice.
2. The customer must ensure the subfloor is clean, level, dry and suitable for installation before work begins.
3. Any floor preparation, subfloor levelling, moisture treatment, priming or protection not included in the invoice may be charged separately.
4. If existing floor coverings are lifted and installation cannot proceed because the subfloor is unsuitable, reinstatement or additional preparation costs are the customer's responsibility.
5. The customer must provide clear, clean access to all installation areas.
6. Furniture removal, replacement, uplift of existing floor coverings, floor preparation and door trimming are the customer's responsibility unless clearly stated in the details of sale.
7. The customer must provide adequate 240V power for installation.
8. Hard-flooring products may vary in colour, grain, shade, batch and appearance from samples.
9. Timber, laminate, hybrid and vinyl flooring must be maintained according to the manufacturer's care instructions.
10. Do not apply excessive water to hard-flooring surfaces. Water damage, moisture penetration and incorrect cleaning may void warranties.
11. Sunlight and UV exposure may cause fading, colour change, expansion, contraction, cracking or movement. Window coverings and normal care are recommended.
12. Supply-only products must be installed according to the manufacturer's instructions. The customer is responsible for quantities, underlay, accessories and installation suitability unless otherwise agreed.
13. If payment is not made by the agreed terms, warranties may be affected and overdue amounts may attract recovery costs.
14. These terms form part of the agreement between the customer and {BUSINESS_NAME}. Nothing in these terms limits rights available under Australian Consumer Law.
$hard$, '{BUSINESS_NAME}', b.name);

SELECT
    business_id,
    slug,
    name,
    terms_soft IS NOT NULL AS has_terms_soft,
    terms_hard IS NOT NULL AS has_terms_hard
FROM business
ORDER BY business_id;
