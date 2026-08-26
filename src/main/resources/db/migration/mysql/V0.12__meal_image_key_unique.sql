ALTER TABLE meal
    ADD CONSTRAINT uk_meal_image_key UNIQUE (image_key);
