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
