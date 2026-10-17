# dandi-backend

## E2E 테스트

배포된 서버에 HTTP로 요청해 API가 실제로 동작하는지 확인한다. 코드는 `src/e2eTest`에 있고, `./gradlew test`와 분리된 `e2eTest` 태스크로만 실행된다.

| 실행 위치 | 범위 | 언제 |
|---|---|---|
| dev | 전체 | Jenkins `E2E Dev` stage — main 머지 후 dev 배포가 끝나면 |
| prod | `@Smoke`만 | blue/green 전환 전 green 태스크 대상 (예정) |

### 로컬에서 실행

```bash
read -rs E2E_USER_PASSWORD && export E2E_USER_PASSWORD
read -rs E2E_OTHER_USER_PASSWORD && export E2E_OTHER_USER_PASSWORD
E2E_BASE_URL=https://ddanddi.nyummy.co.kr E2E_USER_EMAIL=<첫 번째 계정> E2E_OTHER_USER_EMAIL=<두 번째 계정> ./gradlew e2eTest
```

- smoke만: `-Pe2eTags=smoke` (두 번째 계정 값 없이도 돈다)
- 한 클래스만: `--tests '*MealE2eTest'`
- 결과: `build/reports/tests/e2eTest/index.html`

| 환경변수 | 용도 |
|---|---|
| `E2E_BASE_URL` | 대상 서버. 기본값 없음(엉뚱한 서버를 테스트하지 않도록) |
| `E2E_USER_EMAIL` / `E2E_USER_PASSWORD` | 고정 테스트 계정. 대부분의 테스트와 smoke가 쓴다 |
| `E2E_OTHER_USER_EMAIL` / `E2E_OTHER_USER_PASSWORD` | 두 번째 고정 계정. 남의 리소스 접근(403·invalidKey) 확인용, dev 전용 |

Jenkins는 credential `e2e-dev-account`, `e2e-dev-account-2`로 넣는다.

### 테스트 계정 전제

- 첫 번째 계정: 고양이 생성 완료, 신체 정보 입력(목표 섭취량 계산), 기기 토큰 미등록, 마케팅 수신 미동의
- dev 서버 `.env.dev`에 `APP_MEAL_MAXDAILYCOUNT`를 크게 둔다. 식사 삭제로는 하루 기록 시도 횟수(기본 5)가 돌아오지 않아, 머지가 많은 날 상한에 걸린다. prod에는 넣지 않는다.

### 테스트를 추가할 때

- **main 코드를 의존하지 않는다.** 응답은 main DTO가 아니라 JSON 경로(`body("keySet()", hasItems(...))`)로, 요청 바디는 키를 문자 그대로 적은 `Map`으로 만든다. DTO를 재사용하면 응답 계약이 깨져도 테스트가 같이 바뀐다.
- **고정 계정의 상태를 되돌린다.** 바꾼 설정은 `finally`에서 원래 값으로, 만든 리소스는 `finally`에서 삭제한다. 이미 지워졌을 수 있는 정리는 결과를 검증하지 않는다.
- **거부 테스트는 부작용이 없었는지도 확인한다.** 4xx가 와도 저장이 먼저 일어났을 수 있다.
- **고정 계정으로 로그아웃하면 `AuthFixture.resetAccessToken()`을 부른다.** 로그아웃은 그 사용자의 기존 AccessToken을 모두 끊는다.
- **`@Smoke`는 조회 위주로, prod 데이터를 남기지 않는 테스트에만 붙인다.** 식사·문의 생성, 설정 변경은 붙이지 않는다.
- **이미지 리소스는 EXIF를 지운다.** 촬영 시각이 남으면 다음 날부터 `api.meal.staleImage`로 실패하고, GPS가 남으면 위치가 공개된다.

### 주의

- 로컬과 Jenkins에서 같은 dev 계정으로 **동시에 돌리지 않는다.** 설정 변경·로그아웃이 서로의 상태를 덮어쓴다.
- 식사 시나리오는 실제 Gemini를 호출한다. 분석이 `FAILED`면 재시도 API로 두 번까지 다시 시도하고, 그래도 실패하면 dev 로그의 `영양 분석 실패`를 확인한다.
- prod smoke의 업로드 테스트는 확정하지 않은 temp 이미지를 남긴다. 버킷에 `status=temp` lifecycle 규칙이 있어야 한다.

## DB 마이그레이션

Flyway가 `src/main/resources/db/migration/mysql`의 SQL을 앱 기동 시 적용한다. prod는 V0.34까지 수동 SQL로 적용했고, 이력 테이블이 없던 첫 배포에서 V0.34를 기준(baseline)으로 기록한 뒤 V0.35부터 Flyway가 적용한다.

### 구버전과 같이 돌아도 되는 변경만 한 번에 배포한다

배포 중에는 구버전과 신버전이 같은 DB를 쓴다. 마이그레이션이 적용된 뒤에도 구버전 태스크가 남아 있고, 배포가 실패해 롤백해도 마이그레이션은 되돌아가지 않는다. 앱은 `ddl-auto: validate`라 구버전이 매핑한 컬럼이 없으면 기동하지 못한다.

| 변경 | 한 번에 배포 |
|---|---|
| 테이블 추가, nullable·기본값 있는 컬럼 추가, 인덱스 추가 | 가능 |
| 컬럼·테이블 삭제 | 나눈다 |
| 컬럼·테이블 이름 변경 | 나눈다 |
| 기본값 없는 NOT NULL 추가 | 나눈다 |
| 타입 축소(길이 줄이기 등) | 나눈다 |

### 나눌 때는 추가 → 전환 → 삭제 순서로

1. **추가**: 새 구조를 만들고 필요하면 값을 옮긴다. 구버전은 옛 구조를 계속 쓴다.
2. **전환**: 코드가 새 구조를 쓰도록 바꿔 배포한다. 옛 구조는 남겨 두어 롤백해도 동작한다.
3. **삭제**: 다음 배포에서 옛 구조를 지운다. 이때의 구버전은 이미 새 구조를 쓰는 2번 버전이다.
