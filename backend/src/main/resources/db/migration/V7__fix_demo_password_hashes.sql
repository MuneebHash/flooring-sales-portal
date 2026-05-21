-- V7__fix_demo_password_hashes.sql
-- Replace the V4-seeded demo password_hash values with a BCrypt hash that actually
-- verifies "password123" under Spring's BCryptPasswordEncoder. The previous hash was
-- a placeholder that did not match the documented demo password, which forced tests
-- to rewrite app_user.password_hash at runtime to authenticate as a seeded user.
--
-- Hash below was generated with org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
-- (strength 10) and verified against "password123" before being committed here.
-- All 9 demo users share the same plaintext "password123" per V4's seed contract.

UPDATE app_user
SET    password_hash = '$2a$10$0gPygrnySenLQRxPuc6yFuEMKMCZRDigDt4Kn0T1KpNYqJzbBVi0a'
WHERE  user_id IN (1, 2, 3, 4, 5, 6, 7, 8, 9);
