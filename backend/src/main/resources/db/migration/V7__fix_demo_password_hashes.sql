-- V7__fix_demo_password_hashes.sql
-- Replace ONLY the V4-seeded placeholder password_hash with a BCrypt hash that
-- actually verifies "password123" under Spring's BCryptPasswordEncoder. The V4
-- placeholder did not match the documented demo password, which previously forced
-- tests to rewrite app_user.password_hash at runtime to authenticate as a seeded user.
--
-- Safety constraint: the WHERE clause matches BOTH the seeded user_id range AND the
-- exact V4 placeholder hash. If a long-lived dev/staging database has already had any
-- of those users change their password after V4-V6 (so the stored hash no longer
-- equals the V4 placeholder), this migration leaves that row untouched. The fix is
-- therefore strictly a correction of untouched seed rows, never a password reset.
--
-- The new hash below was generated with
--   org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder (strength 10)
-- and verified to accept "password123" and reject other inputs before being committed.

UPDATE app_user
SET    password_hash = '$2a$10$0gPygrnySenLQRxPuc6yFuEMKMCZRDigDt4Kn0T1KpNYqJzbBVi0a'
WHERE  user_id IN (1, 2, 3, 4, 5, 6, 7, 8, 9)
  AND  password_hash = '$2a$10$dXJ3SW6G7P50lGmMQoeqhOvXjDPGEzVImdKUcQBiY7FYitGRoMvCi';
