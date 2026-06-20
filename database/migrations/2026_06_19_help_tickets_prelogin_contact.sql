-- Allow login-screen support tickets before a user has an account/session.
ALTER TABLE help_tickets
    MODIFY user_role VARCHAR(20) NOT NULL,
    MODIFY user_id INT UNSIGNED NOT NULL DEFAULT 0,
    MODIFY phone VARCHAR(120) NOT NULL;

