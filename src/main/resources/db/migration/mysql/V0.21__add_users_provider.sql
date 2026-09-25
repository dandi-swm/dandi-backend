ALTER TABLE users
    ADD COLUMN provider VARCHAR(20) NOT NULL DEFAULT 'EMAIL' AFTER id,
    ADD COLUMN provider_user_id VARCHAR(255) NULL AFTER provider,
    MODIFY COLUMN email VARCHAR(255) NULL,
    MODIFY COLUMN password VARCHAR(255) NULL;

ALTER TABLE users DROP INDEX email;

ALTER TABLE users
    ADD CONSTRAINT uk_users_provider_email UNIQUE (provider, email),
    ADD CONSTRAINT uk_users_provider_user_id UNIQUE (provider, provider_user_id);

ALTER TABLE users ALTER COLUMN provider DROP DEFAULT;
