-- Dev-only seed: demo per-flooring-type invoice terms.
-- Safe to rerun.
-- Updates EVERY business currently in the local DB.
-- Business name inside the terms comes from business.name.
--
-- Phase 16A PR1: terms are now safe HTML ordered lists (<ol><li>…</li></ol> with a little
-- <strong>) so they render as proper numbered, two-column terms on the Invoice tab instead of
-- a cramped run-on paragraph. Only tags already permitted by BOTH sanitizers are used — the
-- frontend DOMPurify allowlist (strips ALL attributes, incl. class) and the backend jsoup
-- InvoiceTermsSanitizer — so NO sanitizer change is needed. Do not add class/style attributes
-- (the frontend strips them) and do not copy any third-party legal wording. The per-type
-- heading is intentionally omitted: the Invoice tab renders its own "Terms and Conditions"
-- heading above this HTML.

UPDATE business b
SET
    terms_soft = replace($soft$<ol>
<li>This agreement is for the sale and installation of the goods described on this invoice, at the value shown on this invoice.</li>
<li>A deposit may be required before work proceeds. The balance <strong>must be paid before installation</strong>, unless otherwise agreed in writing by {BUSINESS_NAME}.</li>
<li>If payment is made by cheque, the cheque must clear before installation.</li>
<li>The customer must confirm the lay date in advance and must ensure clear, clean access to all areas to be laid.</li>
<li>Furniture removal, replacement, uplift of existing floor coverings, floor preparation and door trimming are the customer's responsibility unless clearly stated in the details of sale.</li>
<li>Any additional floor preparation or site costs not included in the invoice may be charged separately.</li>
<li>The customer must provide adequate 240V power for installation.</li>
<li>Colour shades, dye lots and product appearance may vary from samples.</li>
<li>Carpet and soft-flooring products may show pile shading, tracking, watermarking, flattening, matting, shedding or other normal characteristics of the product.</li>
<li>No carpet is fully stainproof. Stain protection does not cover all substances, misuse, poor maintenance or damage outside the product warranty.</li>
<li>If the customer has a complaint, reasonable access must be provided so the product and installation can be inspected.</li>
<li>If payment is not made by the agreed terms, warranties may be affected and overdue amounts may attract recovery costs.</li>
<li>These terms form part of the agreement between the customer and {BUSINESS_NAME}. Nothing in these terms limits rights available under Australian Consumer Law.</li>
</ol>$soft$, '{BUSINESS_NAME}', b.name),

    terms_hard = replace($hard$<ol>
<li>This agreement is for the sale and installation or supply of the hard-flooring goods described on this invoice, at the value shown on this invoice.</li>
<li>The customer must ensure the subfloor is clean, level, dry and suitable for installation before work begins.</li>
<li>Any floor preparation, subfloor levelling, moisture treatment, priming or protection not included in the invoice may be charged separately.</li>
<li>If existing floor coverings are lifted and installation cannot proceed because the subfloor is unsuitable, reinstatement or additional preparation costs are the customer's responsibility.</li>
<li>The customer must provide clear, clean access to all installation areas.</li>
<li>Furniture removal, replacement, uplift of existing floor coverings, floor preparation and door trimming are the customer's responsibility unless clearly stated in the details of sale.</li>
<li>The customer must provide adequate 240V power for installation.</li>
<li>Hard-flooring products may vary in colour, grain, shade, batch and appearance from samples.</li>
<li>Timber, laminate, hybrid and vinyl flooring must be maintained according to the manufacturer's care instructions.</li>
<li>Do not apply excessive water to hard-flooring surfaces. <strong>Water damage, moisture penetration and incorrect cleaning may void warranties.</strong></li>
<li>Sunlight and UV exposure may cause fading, colour change, expansion, contraction, cracking or movement. Window coverings and normal care are recommended.</li>
<li>Supply-only products must be installed according to the manufacturer's instructions. The customer is responsible for quantities, underlay, accessories and installation suitability unless otherwise agreed.</li>
<li>If payment is not made by the agreed terms, warranties may be affected and overdue amounts may attract recovery costs.</li>
<li>These terms form part of the agreement between the customer and {BUSINESS_NAME}. Nothing in these terms limits rights available under Australian Consumer Law.</li>
</ol>$hard$, '{BUSINESS_NAME}', b.name);

SELECT
    business_id,
    slug,
    name,
    terms_soft IS NOT NULL AS has_terms_soft,
    terms_hard IS NOT NULL AS has_terms_hard
FROM business
ORDER BY business_id;
