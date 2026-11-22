-- ============================================================
-- Migration : V5__update_customer1_email.sql
-- Version   : P01 v13
-- Purpose   : Update customer1's email address to a real,
--             deliverable address so NotificationService can
--             send withdrawal confirmation emails.
--
--             V2__create_users.sql seeded customer1 with
--             peta.venkatesh@digistack.cloud — a placeholder
--             domain with no real mailbox. JavaMail / Gmail
--             SMTP will reject delivery to that address.
--
--             v13 NotificationService fetches the recipient
--             email directly from the users table using
--             jdbc/BankDS. This migration puts a real,
--             reachable address in that row.
--
--             In a real bank environment this would never be
--             a migration — customer contact details are
--             managed through a customer-facing profile update
--             flow. For this learning project it is a migration
--             because the users table is seeded by SQL scripts
--             and there is no profile-update feature yet
--             (deferred to a later version).
--
-- Rollback  : db/rollback/V5__rollback_customer1_email.sql
--             (restores the original placeholder value)
-- ============================================================

-- ── UP ────────────────────────────────────────────────────────

-- Update customer1's email to a real inbox.
-- Replace the value below with any address you can receive at.
-- venkateshsap413@gmail.com is used here because it is the
-- same account whose App Password is stored in BankMailSession —
-- Gmail will accept delivery to itself (sender = recipient is
-- valid for testing).

UPDATE users
SET    email = 'venkateshsap413@gmail.com'
WHERE  username = 'customer1';

-- Safety check — confirm exactly one row was updated.
-- Run this manually after applying the migration:
-- SELECT id, username, email FROM users WHERE username = 'customer1';
-- Expected: email = venkateshsap413@gmail.com

-- ── DOWN — ROLLBACK ───────────────────────────────────────────
-- Restores the original placeholder value from V2.
-- Run only if rolling back v13 entirely.
--
-- UPDATE users
-- SET    email = 'peta.venkatesh@digistack.cloud'
-- WHERE  username = 'customer1';