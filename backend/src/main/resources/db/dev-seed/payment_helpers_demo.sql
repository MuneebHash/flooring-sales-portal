-- Dev-only seed: payment helper demo data for PaymentsTab.
-- Safe to rerun. Updates only the local/demo Aussie Floors tenant.

UPDATE business
SET
    bank_name = 'Demo Bank',
    bsb = '123-456',
    account_number = '12345678',
    account_name = 'Aussie Floors Demo Account',
    stripe_payment_link_url = 'https://buy.stripe.com/test_demo_payment_link'
WHERE slug = 'aussie-floors-group';

SELECT
    slug,
    bank_name,
    bsb,
    account_number,
    account_name,
    stripe_payment_link_url
FROM business
WHERE slug = 'aussie-floors-group';
