-- Local usernames are optional so existing email-based accounts remain valid.
ALTER TABLE eperson ADD COLUMN username VARCHAR(64);
CREATE UNIQUE INDEX eperson_username_unique ON eperson(username);
ALTER TABLE eperson ADD COLUMN password_change_required BOOLEAN NOT NULL DEFAULT FALSE;
