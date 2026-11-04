-- V2: V1's UNIQUE constraints are case-sensitive, so "Ross" and "ross" could both register.
-- These functional indexes enforce uniqueness regardless of case.
-- (If V1 data already contains case-variant duplicates, this will fail: resolve them first.)

CREATE UNIQUE INDEX ux_users_username_lower ON users (lower(username));
CREATE UNIQUE INDEX ux_users_email_lower    ON users (lower(email));
