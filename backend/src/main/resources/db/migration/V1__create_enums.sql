-- V1__create_enums.sql
-- Creates all PostgreSQL enum types used across the schema.
-- These must exist before any table references them.

CREATE TYPE flooring_type AS ENUM ('SOFT', 'HARD');

CREATE TYPE order_status AS ENUM (
    'LEAD',
    'NEW_ACHIEVED_SALE',
    'FOLLOW_UP',
    'ACCEPTED',
    'LAID',
    'CANCELLED'
);

CREATE TYPE lay_date_status AS ENUM ('CONFIRMED', 'TO_BE_CONFIRMED');

CREATE TYPE payment_method AS ENUM ('CASH', 'CREDIT_CARD', 'EFTPOS', 'BANK_TRANSFER');

CREATE TYPE attachment_kind AS ENUM ('PHOTO', 'SIGNATURE');

CREATE TYPE address_type AS ENUM ('BILLING', 'INSTALLATION');

CREATE TYPE stock_unit AS ENUM ('LM', 'SQM');

CREATE TYPE pricing_unit AS ENUM ('LM', 'SQM');