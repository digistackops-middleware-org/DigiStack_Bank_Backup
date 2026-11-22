-- ============================================================
-- Rollback   : V5__rollback_customer1_email.sql
-- Rolls back : V5__update_customer1_email.sql
-- Purpose    : Restore customer1's email to the original
--              placeholder value seeded at V2.
-- ============================================================

UPDATE users
SET    email = 'peta.venkatesh@digistack.cloud'
WHERE  username = 'customer1';