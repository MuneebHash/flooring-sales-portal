-- V10__add_invoice_acceptance_email_fields.sql
-- Phase 13 (Invoice Acceptance + Signature + Email) data-model contract §4: adds the four nullable
-- acceptance/email columns to the existing invoice table. All four start NULL on every existing and
-- newly created invoice row (an invoice is unaccepted/unemailed until the Phase 13 Accept/email flows
-- — implemented on later branches — set them).
--
--   accepted_at                 set when the current invoice is accepted; NULL = unaccepted
--   accepted_customer_name      name captured with the signature
--   accepted_signature_file_id  FK -> stored_file(stored_file_id); deliberately NOT unique (contrast
--                               uq_invoice_file on stored_file_id, the PDF): a payment-carried-forward
--                               version references the SAME signature stored_file as the version it
--                               was copied from
--   last_emailed_at             last time THIS invoice version was emailed; NULL = never. Mutable
--                               delivery marker (Resend updates it in place); not part of the
--                               immutable acceptance snapshot
--
-- No CHECK constraint on accepted_customer_name and no other constraints: the Phase 13 contract
-- documents exactly these four nullable columns plus the non-unique FK, nothing more.

ALTER TABLE invoice
    ADD COLUMN accepted_at TIMESTAMP;

ALTER TABLE invoice
    ADD COLUMN accepted_customer_name VARCHAR(150);

ALTER TABLE invoice
    ADD COLUMN accepted_signature_file_id BIGINT;

ALTER TABLE invoice
    ADD COLUMN last_emailed_at TIMESTAMP;

ALTER TABLE invoice
    ADD CONSTRAINT fk_invoice_accepted_signature_file
    FOREIGN KEY (accepted_signature_file_id) REFERENCES stored_file (stored_file_id);
