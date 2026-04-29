-- V3__add_constraints_and_indexes.sql
-- Adds all UNIQUE constraints, FOREIGN KEYS, CHECK constraints, and indexes.
-- Ordering: UNIQUE first (composite FKs depend on them), then FKs, then CHECKs, then indexes.


-- ============================================================
-- 1. UNIQUE CONSTRAINTS
-- ============================================================

-- store
ALTER TABLE store
    ADD CONSTRAINT uq_store_business_code UNIQUE (business_id, store_code);
ALTER TABLE store
    ADD CONSTRAINT uq_store_business_store UNIQUE (business_id, store_id);

-- app_user
ALTER TABLE app_user
    ADD CONSTRAINT uq_app_user_business_code UNIQUE (business_id, salesperson_code);
ALTER TABLE app_user
    ADD CONSTRAINT uq_app_user_business_user UNIQUE (business_id, user_id);

-- user_store_access
ALTER TABLE user_store_access
    ADD CONSTRAINT uq_user_store_access_user_store UNIQUE (user_id, store_id);

-- store_product
ALTER TABLE store_product
    ADD CONSTRAINT uq_store_product_store_code UNIQUE (store_id, code);

-- store_charge
ALTER TABLE store_charge
    ADD CONSTRAINT uq_store_charge_store_code UNIQUE (store_id, code);

-- sales_order
ALTER TABLE sales_order
    ADD CONSTRAINT uq_sales_order_business_seq UNIQUE (business_id, order_sequence_number);

-- order_customer  (one customer record per order)
ALTER TABLE order_customer
    ADD CONSTRAINT uq_order_customer_order UNIQUE (order_id);

-- order_address  (one address per type per order)
ALTER TABLE order_address
    ADD CONSTRAINT uq_order_address_order_type UNIQUE (order_id, address_type);

-- order_attachment  (each file linked once)
ALTER TABLE order_attachment
    ADD CONSTRAINT uq_order_attachment_file UNIQUE (stored_file_id);

-- invoice  (one PDF per invoice, one version number per order)
ALTER TABLE invoice
    ADD CONSTRAINT uq_invoice_file UNIQUE (stored_file_id);
ALTER TABLE invoice
    ADD CONSTRAINT uq_invoice_order_version UNIQUE (order_id, version_number);


-- ============================================================
-- 2. FOREIGN KEY CONSTRAINTS
-- ============================================================

-- store
ALTER TABLE store
    ADD CONSTRAINT fk_store_business
    FOREIGN KEY (business_id) REFERENCES business (business_id);

-- app_user
ALTER TABLE app_user
    ADD CONSTRAINT fk_app_user_business
    FOREIGN KEY (business_id) REFERENCES business (business_id);

-- user_store_access  (simple FK to business + composite FKs for tenant safety)
ALTER TABLE user_store_access
    ADD CONSTRAINT fk_user_store_access_business
    FOREIGN KEY (business_id) REFERENCES business (business_id);

ALTER TABLE user_store_access
    ADD CONSTRAINT fk_user_store_access_user
    FOREIGN KEY (business_id, user_id) REFERENCES app_user (business_id, user_id);

ALTER TABLE user_store_access
    ADD CONSTRAINT fk_user_store_access_store
    FOREIGN KEY (business_id, store_id) REFERENCES store (business_id, store_id);

-- store_product
ALTER TABLE store_product
    ADD CONSTRAINT fk_store_product_store
    FOREIGN KEY (store_id) REFERENCES store (store_id);

-- store_charge
ALTER TABLE store_charge
    ADD CONSTRAINT fk_store_charge_store
    FOREIGN KEY (store_id) REFERENCES store (store_id);

-- sales_order  (simple FK to business + composite FKs for tenant safety)
ALTER TABLE sales_order
    ADD CONSTRAINT fk_sales_order_business
    FOREIGN KEY (business_id) REFERENCES business (business_id);

ALTER TABLE sales_order
    ADD CONSTRAINT fk_sales_order_user
    FOREIGN KEY (business_id, user_id) REFERENCES app_user (business_id, user_id);

ALTER TABLE sales_order
    ADD CONSTRAINT fk_sales_order_store
    FOREIGN KEY (business_id, store_id) REFERENCES store (business_id, store_id);

-- order_customer
ALTER TABLE order_customer
    ADD CONSTRAINT fk_order_customer_order
    FOREIGN KEY (order_id) REFERENCES sales_order (order_id);

-- order_address
ALTER TABLE order_address
    ADD CONSTRAINT fk_order_address_order
    FOREIGN KEY (order_id) REFERENCES sales_order (order_id);

-- order_product_line
ALTER TABLE order_product_line
    ADD CONSTRAINT fk_order_product_line_order
    FOREIGN KEY (order_id) REFERENCES sales_order (order_id);

ALTER TABLE order_product_line
    ADD CONSTRAINT fk_order_product_line_product
    FOREIGN KEY (product_id) REFERENCES store_product (product_id);

-- order_charge_line
ALTER TABLE order_charge_line
    ADD CONSTRAINT fk_order_charge_line_order
    FOREIGN KEY (order_id) REFERENCES sales_order (order_id);

ALTER TABLE order_charge_line
    ADD CONSTRAINT fk_order_charge_line_charge
    FOREIGN KEY (charge_id) REFERENCES store_charge (charge_id);

-- order_note
ALTER TABLE order_note
    ADD CONSTRAINT fk_order_note_order
    FOREIGN KEY (order_id) REFERENCES sales_order (order_id);

-- order_attachment
ALTER TABLE order_attachment
    ADD CONSTRAINT fk_order_attachment_order
    FOREIGN KEY (order_id) REFERENCES sales_order (order_id);

ALTER TABLE order_attachment
    ADD CONSTRAINT fk_order_attachment_file
    FOREIGN KEY (stored_file_id) REFERENCES stored_file (stored_file_id);

-- payment_transaction
ALTER TABLE payment_transaction
    ADD CONSTRAINT fk_payment_transaction_order
    FOREIGN KEY (order_id) REFERENCES sales_order (order_id);

-- invoice
ALTER TABLE invoice
    ADD CONSTRAINT fk_invoice_order
    FOREIGN KEY (order_id) REFERENCES sales_order (order_id);

ALTER TABLE invoice
    ADD CONSTRAINT fk_invoice_file
    FOREIGN KEY (stored_file_id) REFERENCES stored_file (stored_file_id);

ALTER TABLE invoice
    ADD CONSTRAINT fk_invoice_created_by
    FOREIGN KEY (created_by_user_id) REFERENCES app_user (user_id);


-- ============================================================
-- 3. CHECK CONSTRAINTS
-- ============================================================

-- business
ALTER TABLE business
    ADD CONSTRAINT chk_business_name_not_blank
    CHECK (trim(name) <> '');
ALTER TABLE business
    ADD CONSTRAINT chk_business_email_domain_not_blank
    CHECK (email_domain IS NULL OR trim(email_domain) <> '');

-- store
ALTER TABLE store
    ADD CONSTRAINT chk_store_name_not_blank       CHECK (trim(name) <> '');
ALTER TABLE store
    ADD CONSTRAINT chk_store_code_not_blank       CHECK (trim(store_code) <> '');
ALTER TABLE store
    ADD CONSTRAINT chk_store_phone_not_blank      CHECK (trim(phone) <> '');
ALTER TABLE store
    ADD CONSTRAINT chk_store_email_format         CHECK (email IS NULL OR (trim(email) <> '' AND email LIKE '%@%'));
ALTER TABLE store
    ADD CONSTRAINT chk_store_street_not_blank     CHECK (trim(street) <> '');
ALTER TABLE store
    ADD CONSTRAINT chk_store_suburb_not_blank     CHECK (trim(suburb) <> '');
ALTER TABLE store
    ADD CONSTRAINT chk_store_state_not_blank      CHECK (trim(state_code) <> '');
ALTER TABLE store
    ADD CONSTRAINT chk_store_postcode_not_blank   CHECK (trim(postcode) <> '');

-- app_user
ALTER TABLE app_user
    ADD CONSTRAINT chk_app_user_first_name_not_blank  CHECK (trim(first_name) <> '');
ALTER TABLE app_user
    ADD CONSTRAINT chk_app_user_last_name_not_blank   CHECK (trim(last_name) <> '');
ALTER TABLE app_user
    ADD CONSTRAINT chk_app_user_code_not_blank        CHECK (trim(salesperson_code) <> '');
ALTER TABLE app_user
    ADD CONSTRAINT chk_app_user_email_format          CHECK (trim(email) <> '' AND email LIKE '%@%');
ALTER TABLE app_user
    ADD CONSTRAINT chk_app_user_password_not_blank    CHECK (trim(password_hash) <> '');

-- store_product
ALTER TABLE store_product
    ADD CONSTRAINT chk_store_product_code_not_blank  CHECK (trim(code) <> '');
ALTER TABLE store_product
    ADD CONSTRAINT chk_store_product_name_not_blank  CHECK (trim(name) <> '');
ALTER TABLE store_product
    ADD CONSTRAINT chk_store_product_price_gte_zero  CHECK (price >= 0);
ALTER TABLE store_product
    ADD CONSTRAINT chk_store_product_cost_gte_zero   CHECK (cost >= 0);
ALTER TABLE store_product
    ADD CONSTRAINT chk_store_product_stock_qty_gte_zero
    CHECK (stock_quantity IS NULL OR stock_quantity >= 0);
ALTER TABLE store_product
    ADD CONSTRAINT chk_store_product_stock_pair
    CHECK ((stock_quantity IS NULL AND stock_unit IS NULL)
        OR (stock_quantity IS NOT NULL AND stock_unit IS NOT NULL));

-- store_charge
ALTER TABLE store_charge
    ADD CONSTRAINT chk_store_charge_code_not_blank  CHECK (trim(code) <> '');
ALTER TABLE store_charge
    ADD CONSTRAINT chk_store_charge_name_not_blank  CHECK (trim(name) <> '');
ALTER TABLE store_charge
    ADD CONSTRAINT chk_store_charge_price_gte_zero  CHECK (price >= 0);
ALTER TABLE store_charge
    ADD CONSTRAINT chk_store_charge_cost_gte_zero   CHECK (cost >= 0);

-- sales_order
ALTER TABLE sales_order
    ADD CONSTRAINT chk_sales_order_seq_positive
    CHECK (order_sequence_number > 0);
ALTER TABLE sales_order
    ADD CONSTRAINT chk_sales_order_number_not_blank
    CHECK (trim(order_number) <> '');
ALTER TABLE sales_order
    ADD CONSTRAINT chk_sales_order_lay_date_pair
    CHECK ((proposed_lay_date IS NULL AND lay_date_status IS NULL)
        OR (proposed_lay_date IS NOT NULL AND lay_date_status IS NOT NULL));
ALTER TABLE sales_order
    ADD CONSTRAINT chk_sales_order_sale_price_gte_zero
    CHECK (sale_price_ex_gst IS NULL OR sale_price_ex_gst >= 0);
ALTER TABLE sales_order
    ADD CONSTRAINT chk_sales_order_total_cost_gte_zero
    CHECK (total_cost IS NULL OR total_cost >= 0);
ALTER TABLE sales_order
    ADD CONSTRAINT chk_sales_order_week_number
    CHECK (week_number BETWEEN 1 AND 53);
ALTER TABLE sales_order
    ADD CONSTRAINT chk_sales_order_week_year
    CHECK (week_year BETWEEN 2000 AND 2100);

-- order_customer
ALTER TABLE order_customer
    ADD CONSTRAINT chk_order_customer_first_name_not_blank  CHECK (trim(first_name) <> '');
ALTER TABLE order_customer
    ADD CONSTRAINT chk_order_customer_last_name_not_blank   CHECK (trim(last_name) <> '');
ALTER TABLE order_customer
    ADD CONSTRAINT chk_order_customer_email_format          CHECK (trim(email) <> '' AND email LIKE '%@%');
ALTER TABLE order_customer
    ADD CONSTRAINT chk_order_customer_mobile_not_blank      CHECK (trim(mobile) <> '');
ALTER TABLE order_customer
    ADD CONSTRAINT chk_order_customer_home_phone_not_blank
    CHECK (home_phone IS NULL OR trim(home_phone) <> '');
ALTER TABLE order_customer
    ADD CONSTRAINT chk_order_customer_work_phone_not_blank
    CHECK (work_phone IS NULL OR trim(work_phone) <> '');
ALTER TABLE order_customer
    ADD CONSTRAINT chk_order_customer_company_not_blank
    CHECK (company_name IS NULL OR trim(company_name) <> '');

-- order_address
ALTER TABLE order_address
    ADD CONSTRAINT chk_order_address_unit_not_blank
    CHECK (unit_number IS NULL OR trim(unit_number) <> '');
ALTER TABLE order_address
    ADD CONSTRAINT chk_order_address_street_num_not_blank  CHECK (trim(street_number) <> '');
ALTER TABLE order_address
    ADD CONSTRAINT chk_order_address_street_not_blank      CHECK (trim(street) <> '');
ALTER TABLE order_address
    ADD CONSTRAINT chk_order_address_suburb_not_blank      CHECK (trim(suburb) <> '');
ALTER TABLE order_address
    ADD CONSTRAINT chk_order_address_state_not_blank       CHECK (trim(state_code) <> '');
ALTER TABLE order_address
    ADD CONSTRAINT chk_order_address_postcode_not_blank    CHECK (trim(postcode) <> '');

-- order_product_line
ALTER TABLE order_product_line
    ADD CONSTRAINT chk_opl_code_not_blank           CHECK (trim(product_code_snapshot) <> '');
ALTER TABLE order_product_line
    ADD CONSTRAINT chk_opl_name_not_blank           CHECK (trim(product_name_snapshot) <> '');
ALTER TABLE order_product_line
    ADD CONSTRAINT chk_opl_price_snapshot_gte_zero  CHECK (price_snapshot >= 0);
ALTER TABLE order_product_line
    ADD CONSTRAINT chk_opl_cost_snapshot_gte_zero   CHECK (cost_snapshot >= 0);
ALTER TABLE order_product_line
    ADD CONSTRAINT chk_opl_qty_lm_positive          CHECK (quantity_lm > 0);
ALTER TABLE order_product_line
    ADD CONSTRAINT chk_opl_qty_sqm_positive         CHECK (quantity_sqm > 0);
ALTER TABLE order_product_line
    ADD CONSTRAINT chk_opl_unit_price_positive      CHECK (unit_price > 0);
ALTER TABLE order_product_line
    ADD CONSTRAINT chk_opl_line_total_positive      CHECK (line_total > 0);
ALTER TABLE order_product_line
    ADD CONSTRAINT chk_opl_line_cost_gte_zero       CHECK (line_cost >= 0);

-- order_charge_line
ALTER TABLE order_charge_line
    ADD CONSTRAINT chk_ocl_code_not_blank           CHECK (trim(charge_code_snapshot) <> '');
ALTER TABLE order_charge_line
    ADD CONSTRAINT chk_ocl_name_not_blank           CHECK (trim(charge_name_snapshot) <> '');
ALTER TABLE order_charge_line
    ADD CONSTRAINT chk_ocl_price_snapshot_gte_zero  CHECK (price_snapshot >= 0);
ALTER TABLE order_charge_line
    ADD CONSTRAINT chk_ocl_cost_snapshot_gte_zero   CHECK (cost_snapshot >= 0);
ALTER TABLE order_charge_line
    ADD CONSTRAINT chk_ocl_quantity_positive         CHECK (quantity > 0);
ALTER TABLE order_charge_line
    ADD CONSTRAINT chk_ocl_unit_price_positive       CHECK (unit_price > 0);
ALTER TABLE order_charge_line
    ADD CONSTRAINT chk_ocl_line_total_positive       CHECK (line_total > 0);
ALTER TABLE order_charge_line
    ADD CONSTRAINT chk_ocl_line_cost_gte_zero        CHECK (line_cost >= 0);

-- order_note
ALTER TABLE order_note
    ADD CONSTRAINT chk_order_note_text_not_blank
    CHECK (trim(note_text) <> '');

-- stored_file
ALTER TABLE stored_file
    ADD CONSTRAINT chk_stored_file_name_not_blank     CHECK (trim(file_name) <> '');
ALTER TABLE stored_file
    ADD CONSTRAINT chk_stored_file_path_not_blank     CHECK (trim(storage_path) <> '');
ALTER TABLE stored_file
    ADD CONSTRAINT chk_stored_file_mime_not_blank     CHECK (trim(mime_type) <> '');
ALTER TABLE stored_file
    ADD CONSTRAINT chk_stored_file_size_positive      CHECK (file_size > 0);

-- payment_transaction
ALTER TABLE payment_transaction
    ADD CONSTRAINT chk_payment_amount_positive
    CHECK (amount > 0);
ALTER TABLE payment_transaction
    ADD CONSTRAINT chk_payment_reference_not_blank
    CHECK (payment_reference IS NULL OR trim(payment_reference) <> '');
ALTER TABLE payment_transaction
    ADD CONSTRAINT chk_payment_gateway_id_not_blank
    CHECK (gateway_transaction_id IS NULL OR trim(gateway_transaction_id) <> '');
ALTER TABLE payment_transaction
    ADD CONSTRAINT chk_payment_status_not_blank
    CHECK (response_status IS NULL OR trim(response_status) <> '');
ALTER TABLE payment_transaction
    ADD CONSTRAINT chk_payment_message_not_blank
    CHECK (response_message IS NULL OR trim(response_message) <> '');

-- invoice
ALTER TABLE invoice
    ADD CONSTRAINT chk_invoice_version_positive
    CHECK (version_number > 0);
ALTER TABLE invoice
    ADD CONSTRAINT chk_invoice_details_not_blank
    CHECK (trim(details_of_sale_snapshot) <> '');
ALTER TABLE invoice
    ADD CONSTRAINT chk_invoice_sale_price_ex_positive
    CHECK (sale_price_ex_gst > 0);
ALTER TABLE invoice
    ADD CONSTRAINT chk_invoice_sale_price_inc_valid
    CHECK (sale_price_inc_gst > 0 AND sale_price_inc_gst >= sale_price_ex_gst);
ALTER TABLE invoice
    ADD CONSTRAINT chk_invoice_total_paid_gte_zero
    CHECK (total_paid >= 0);
ALTER TABLE invoice
    ADD CONSTRAINT chk_invoice_balance_due_gte_zero
    CHECK (balance_due >= 0);


-- ============================================================
-- 4. PERFORMANCE INDEXES
-- ============================================================
-- PostgreSQL auto-creates indexes for PRIMARY KEY and UNIQUE constraints.
-- These cover FK columns and common query filters that don't already have one.

-- tenant lookups
CREATE INDEX idx_store_business              ON store (business_id);
CREATE INDEX idx_app_user_business           ON app_user (business_id);

-- user_store_access lookups
CREATE INDEX idx_user_store_access_business  ON user_store_access (business_id);
CREATE INDEX idx_user_store_access_user      ON user_store_access (user_id);
CREATE INDEX idx_user_store_access_store     ON user_store_access (store_id);

-- catalog by store
CREATE INDEX idx_store_product_store         ON store_product (store_id);
CREATE INDEX idx_store_charge_store          ON store_charge (store_id);

-- dashboard queries on sales_order
CREATE INDEX idx_sales_order_store           ON sales_order (store_id);
CREATE INDEX idx_sales_order_user            ON sales_order (user_id);
CREATE INDEX idx_sales_order_status          ON sales_order (order_status);
CREATE INDEX idx_sales_order_week            ON sales_order (week_year, week_number);

-- order detail lookups by order_id
CREATE INDEX idx_order_product_line_order    ON order_product_line (order_id);
CREATE INDEX idx_order_product_line_product  ON order_product_line (product_id);
CREATE INDEX idx_order_charge_line_order     ON order_charge_line (order_id);
CREATE INDEX idx_order_charge_line_charge    ON order_charge_line (charge_id);
CREATE INDEX idx_order_note_order            ON order_note (order_id);
CREATE INDEX idx_order_attachment_order      ON order_attachment (order_id);
CREATE INDEX idx_payment_transaction_order   ON payment_transaction (order_id);
CREATE INDEX idx_invoice_order               ON invoice (order_id);
CREATE INDEX idx_invoice_created_by          ON invoice (created_by_user_id);