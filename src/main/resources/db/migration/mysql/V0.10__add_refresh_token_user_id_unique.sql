ALTER TABLE refresh_token
    ADD CONSTRAINT uk_refresh_token_user_id UNIQUE (user_id);