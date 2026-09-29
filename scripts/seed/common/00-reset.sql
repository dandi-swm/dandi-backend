-- 세션 설정과 초기화. 모든 시드 파일은 하나의 mysql 세션에 이어 붙여 실행되므로
-- 여기서 정한 문자셋/시간대가 이후 파일에도 그대로 적용된다.

SET NAMES utf8mb4;

-- CURDATE()/NOW()를 KST 기준으로 계산한다.
-- 조회 API가 Asia/Seoul 경계로 날짜를 자르므로, 컨테이너 기본 시간대(UTC)를 쓰면
-- 오늘 만든 데이터가 어제 칸에 찍힐 수 있다.
SET time_zone = '+09:00';

-- TRUNCATE는 FK로 참조되는 테이블에 걸리므로 잠시 검사를 끈다.
-- AUTO_INCREMENT도 함께 초기화되어 이후 파일의 명시적 ID와 어긋나지 않는다.
SET FOREIGN_KEY_CHECKS = 0;

TRUNCATE TABLE meal;
TRUNCATE TABLE cat;
TRUNCATE TABLE profile;
TRUNCATE TABLE refresh_token;
TRUNCATE TABLE code;
TRUNCATE TABLE icon;
TRUNCATE TABLE users;

SET FOREIGN_KEY_CHECKS = 1;
