-- icon
--
-- 여기만 더미가 아니라 실제 운영 데이터다. 아이콘은 추가만 되고 기존 행은 바뀌지 않는
-- append-only 테이블이라, DB를 새로 만들더라도 아래 행이 id까지 그대로 들어가야 한다.
-- meal.icon_id가 이 id를 그대로 가리키고, 앱이 내려주는 이미지 URL도 여기서 나온다.
--
-- 아래 12건은 2026-09-11 기준 실제 icon 테이블을 그대로 옮긴 것이다.
-- 운영에 아이콘을 추가하면 이 목록에도 같은 id/name/image_url로 추가할 것.
--
-- GeminiNutritionAnalysisClient는 IconService.getAllIcons()로 이 테이블을 읽어
-- 프롬프트에 아이콘 목록을 넣는다. 즉 여기 없는 id는 AI도 고를 수 없다.
-- 프롬프트가 "뚜렷하게 맞는 것이 없으면 5(밥)"를 fallback으로 지정하므로 id 5는 반드시 있어야 한다.
--
-- IconService는 @Cacheable("icons")로 Caffeine 캐시를 타므로,
-- 앱이 떠 있는 상태에서 이 스크립트를 돌렸다면 앱을 재시작해야 새 아이콘이 반영된다.

INSERT INTO icon (id, name, image_url)
VALUES (1, '샐러드', 'https://cdn.nyummy.co.kr/icons/1.jpeg'),
       (2, '빵', 'https://cdn.nyummy.co.kr/icons/2.jpeg'),
       (3, '샌드위치', 'https://cdn.nyummy.co.kr/icons/3.jpeg'),
       (4, '떡볶이', 'https://cdn.nyummy.co.kr/icons/4.jpeg'),
       (5, '밥', 'https://cdn.nyummy.co.kr/icons/5.jpeg'),
       (6, '아이스아메리카노', 'https://cdn.nyummy.co.kr/icons/6.jpeg'),
       (7, '햄버거', 'https://cdn.nyummy.co.kr/icons/7.jpeg'),
       (8, '피자', 'https://cdn.nyummy.co.kr/icons/8.jpeg'),
       (9, '케이크', 'https://cdn.nyummy.co.kr/icons/9.jpeg'),
       (10, '치킨', 'https://cdn.nyummy.co.kr/icons/10.jpeg'),
       (11, '김밥', 'https://cdn.nyummy.co.kr/icons/11.jpeg'),
       (12, '스파게티', 'https://cdn.nyummy.co.kr/icons/12.jpeg');
