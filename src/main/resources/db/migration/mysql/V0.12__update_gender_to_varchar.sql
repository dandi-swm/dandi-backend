ALTER TABLE profile
    MODIFY COLUMN gender VARCHAR(20);

UPDATE profile
SET gender = CASE gender
                 WHEN '0' THEN 'MALE'
                 WHEN '1' THEN 'FEMALE'
                 END
WHERE gender IN ('0', '1');
