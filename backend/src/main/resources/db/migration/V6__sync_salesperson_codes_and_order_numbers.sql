-- V6__sync_salesperson_codes_and_order_numbers.sql
-- Sync seeded salesperson codes and order numbers with locked API convention:
-- salesperson_code = ^[A-Z]{2}[0-9]$
-- order_number = {store_code}.{salesperson_code}.{order_sequence_number_padded_5}

-- 1. Update seeded salesperson codes from old 4-character values to locked 3-character values.
UPDATE app_user SET salesperson_code = 'LC1' WHERE salesperson_code = 'LC01';
UPDATE app_user SET salesperson_code = 'SN1' WHERE salesperson_code = 'SN01';
UPDATE app_user SET salesperson_code = 'JW1' WHERE salesperson_code = 'JW01';
UPDATE app_user SET salesperson_code = 'EP1' WHERE salesperson_code = 'EP01';
UPDATE app_user SET salesperson_code = 'OS1' WHERE salesperson_code = 'OS01';
UPDATE app_user SET salesperson_code = 'MJ1' WHERE salesperson_code = 'MJ01';
UPDATE app_user SET salesperson_code = 'NB1' WHERE salesperson_code = 'NB01';
UPDATE app_user SET salesperson_code = 'CT1' WHERE salesperson_code = 'CT01';
UPDATE app_user SET salesperson_code = 'EL1' WHERE salesperson_code = 'EL01';

-- 2. Update seeded order numbers to readable locked format.
UPDATE sales_order SET order_number = 'SYD-CBD.LC1.00001'  WHERE order_id = 1;
UPDATE sales_order SET order_number = 'SYD-CBD.LC1.00002'  WHERE order_id = 2;
UPDATE sales_order SET order_number = 'SYD-CBD.SN1.00003'  WHERE order_id = 3;
UPDATE sales_order SET order_number = 'SYD-CBD.SN1.00004'  WHERE order_id = 4;
UPDATE sales_order SET order_number = 'SYD-PARR.JW1.00005' WHERE order_id = 5;
UPDATE sales_order SET order_number = 'SYD-PARR.JW1.00006' WHERE order_id = 6;
UPDATE sales_order SET order_number = 'SYD-PARR.EP1.00007' WHERE order_id = 7;
UPDATE sales_order SET order_number = 'SYD-PARR.EP1.00008' WHERE order_id = 8;
UPDATE sales_order SET order_number = 'MEL-CBD.OS1.00001'  WHERE order_id = 9;
UPDATE sales_order SET order_number = 'MEL-CBD.OS1.00002'  WHERE order_id = 10;
UPDATE sales_order SET order_number = 'MEL-CBD.MJ1.00003'  WHERE order_id = 11;
UPDATE sales_order SET order_number = 'MEL-CBD.MJ1.00004'  WHERE order_id = 12;
UPDATE sales_order SET order_number = 'MEL-CBD.NB1.00005'  WHERE order_id = 13;
UPDATE sales_order SET order_number = 'MEL-CBD.NB1.00006'  WHERE order_id = 14;
UPDATE sales_order SET order_number = 'MEL-CBD.CT1.00007'  WHERE order_id = 15;
UPDATE sales_order SET order_number = 'MEL-CBD.CT1.00008'  WHERE order_id = 16;
UPDATE sales_order SET order_number = 'MEL-CBD.EL1.00009'  WHERE order_id = 17;
UPDATE sales_order SET order_number = 'MEL-CBD.EL1.00010'  WHERE order_id = 18;

-- 3. Update seeded invoice PDF stored file name/path for order 1 invoice v1.
UPDATE stored_file
   SET file_name = 'invoice-SYD-CBD.LC1.00001-v1.pdf',
       storage_path = '/uploads/1/orders/1/invoice-SYD-CBD.LC1.00001-v1.pdf'
 WHERE stored_file_id = 2;

-- 4. Add locked salesperson_code format constraint after data is synced.
ALTER TABLE app_user
    ADD CONSTRAINT chk_app_user_salesperson_code_format
    CHECK (salesperson_code ~ '^[A-Z]{2}[0-9]$');

-- 5. Add locked order_number format constraint after data is synced.
ALTER TABLE sales_order
    ADD CONSTRAINT chk_sales_order_number_format
    CHECK (order_number ~ '^[A-Z0-9-]+\.[A-Z]{2}[0-9]\.[0-9]{5}$');
