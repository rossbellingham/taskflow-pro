-- Per-user security state, kept apart from the users table.
--   must_change_password  set when an admin resets the password; the user can do nothing but change it
--   sessions_valid_after  login sessions started before this moment are rejected (used to log a user out
--                         everywhere when their password is reset or changed)
-- A user with no row here has no flag set and no sessions revoked.
CREATE TABLE user_security (
    user_id              BIGINT                   PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    must_change_password BOOLEAN                  NOT NULL DEFAULT FALSE,
    sessions_valid_after TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
