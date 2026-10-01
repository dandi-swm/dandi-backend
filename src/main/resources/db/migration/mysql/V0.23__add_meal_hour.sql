ALTER TABLE profile
    -- 온보딩 식사 희망 시간 (0~23시, 건너뛰기/미설정은 NULL)
    ADD COLUMN breakfast_hour INT NULL,
    ADD COLUMN lunch_hour     INT NULL,
    ADD COLUMN dinner_hour    INT NULL;
