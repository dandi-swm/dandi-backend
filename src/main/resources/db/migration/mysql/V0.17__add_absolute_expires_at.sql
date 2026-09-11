ALTER TABLE refresh_token
    ADD COLUMN absolute_expires_at DATETIME NOT NULL;
