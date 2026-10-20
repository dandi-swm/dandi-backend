# 프로젝트 규칙

## 작업 규칙

1. **API를 추가하거나 요청·응답을 바꾸면 같은 PR에서 E2E 시나리오를 추가하거나 고친다.** 내부 배치 API(`/api/v1/internal/**`)와 어드민 API(`/admin/v1/**` — ALB 밖이라 배포 서버에 HTTP로 닿지 않는다)는 제외한다. `src/e2eTest`의 도메인별 `~E2eTest`에 넣는다. E2E는 main 머지 후 dev 배포가 끝나면 Jenkins가 돌리므로, PR에서는 `./gradlew compileE2eTestKotlin`으로 컴파일까지 확인한다.
   - main 코드를 의존하지 않는다. 응답은 main DTO가 아니라 JSON 경로로 검증하고, 요청 바디는 키를 문자 그대로 적은 `Map`으로 만든다. DTO를 재사용하면 계약이 깨져도 테스트가 같이 바뀐다.
   - 고정 계정의 상태를 되돌린다. 바꾼 설정과 만든 리소스는 `finally`에서 원래대로 돌리고, 이미 지워졌을 수 있는 정리는 결과를 검증하지 않는다.
   - 거부(4xx) 테스트는 부작용이 없었는지도 확인한다. 4xx가 와도 저장이 먼저 일어났을 수 있다.
   - 고정 계정으로 로그아웃하면 `AuthFixture.resetAccessToken()`을 부른다. 로그아웃은 그 사용자의 기존 AccessToken을 모두 끊는다.
   - `@Smoke`는 조회 위주로, prod에 데이터를 남기지 않는 테스트에만 붙인다. 식사·문의 생성과 설정 변경에는 붙이지 않는다.
   - 테스트 이미지는 EXIF를 지운다. 촬영 시각이 남으면 다음 날부터 `api.meal.staleImage`로 실패하고, GPS가 남으면 위치가 공개된다.
2. **비즈니스 로직을 구현하거나 바꾸면 같은 PR에 단위 테스트를 올린다.** 대상은 서비스, `~Calculator`, 엔티티의 상태 전이 메서드다. mockk, `kotlin.test` 단언, 한글 백틱 테스트명(`%` 금지), 고정 `Clock`을 쓰고, 비즈니스 예외는 파일마다 private `assertBusinessException(errorCode) {}` 헬퍼를 두고 검증한다.
3. **스키마를 바꾸는 작업은 계획 단계에서 구버전과 같이 돌아도 되는지 판단한다.** 배포 중에는 구버전과 신버전이 같은 DB를 쓰고, 배포가 실패해 롤백해도 마이그레이션은 되돌아가지 않는다. `ddl-auto: validate`라 구버전이 매핑한 컬럼이 없으면 기동하지 못한다.
   - 한 번에 배포: 테이블 추가, nullable이거나 기본값 있는 컬럼 추가, 인덱스 추가
   - 나눠서 배포: 컬럼·테이블 삭제, 이름 변경, 기본값 없는 NOT NULL 추가, 타입 축소(길이 줄이기 등)
   - 나누는 순서: 추가(새 구조를 만들고 필요하면 값을 옮긴다) → 전환(코드가 새 구조를 쓰게 배포하고 옛 구조는 롤백용으로 남긴다) → 삭제(다음 배포에서 옛 구조를 지운다)

## 네이밍 사용 규칙

같은 개념에 여러 단어가 섞이지 않게 하는 규칙과 용어집이다. Kotlin·Spring 기본 관례(PascalCase 클래스, camelCase 함수·프로퍼티, `~Service`·`~Repository`·`~Controller`·`~Request` 등)는 적지 않고 그대로 따른다.

1. 아래에 있는 개념은 **반드시 그 단어를 쓰고** 금지어는 쓰지 않는다. 코드 이름(JSON 필드·DB 컬럼·설정 키 포함)뿐 아니라 계획·API 문서·커밋 메시지·PR 본문에 적는 이름도 같다.
2. 없는 개념은 쓰기 전에 계획에 **"새 용어" 목록**으로 보고하고 확인받는다. 확인받은 용어는 그 PR의 **마지막 커밋**에서 여기에 추가한다.
3. 여기에는 **개념 → 단어, 금지어, 헷갈리는 개념과의 구분**만 쓴다. enum 값, 수명·범위 같은 수치, 동작 설명, 이름 변경 이력은 쓰지 않는다(코드와 git 이력이 원본). 기준: 이 줄을 지우면 이름을 틀리게 짓는가.

## 이름 규칙

1. 메서드는 **`동사 + 목적어`**로 짓는다. 목적어는 반환하거나 바꾸는 대상이다. 동사만 있는 이름(`send()` → `sendEmail()`), 목적어 자리에 형용사·부사구(`markSent()` → `createSentMark()`), 동사 두 개(`createOrRestart()` → `startSession()`), 동사 없는 이름(컨트롤러 포함, `me()` → `getMe()`)은 쓰지 않는다. 예외는 아래뿐이다.
   - Mapper 확장 함수 `toXxx`(Xxx = 반환 타입): `Meal.toNutrition()`
   - 인증 행위 자체: `login()`, `logout()`, `signUp()`, `refresh()`
   - 프레임워크·언어가 정하는 이름: override, `@Bean` 팩토리(반환 타입의 camelCase), Spring Data 파생 쿼리(`findBy~`·`existsBy~`·`deleteBy~`·`countBy~`), `operator fun`, `of`·`from`, `main`, Boolean 함수(`is~`·`has~`·`can~`)
   - 엔티티 자신의 상태 전이: `withdraw()`, `anonymize()`. 필드 하나 변경은 `update<필드>`
2. 종류를 나누는 enum은 `Type`·`Kind` 대신 **`<대상>Category`**로, 필드는 `category`로 쓴다. 따로 정한 개념(Status·Platform·Purpose 등)이 있으면 그 단어를 쓴다. 예외: `TokenType`.
3. 약어는 **첫 글자만 대문자**로 쓴다: `Jwt`, `Jdbc`, `E2e`, `Fcm`. 전체 대문자 금지, 예외는 `OAuth`.
4. 시각은 **`~At`**(만료 시각은 `expiresAt`), 유효 기간은 **`~TimeToLive`**로 쓴다. Expiration·Ttl·ExpiresIn·Validity 금지.
5. Boolean은 상태 `is~`, 보유 `has~`(프로퍼티 전용 — 존재를 확인하는 메서드는 `exists~`), 가능 `can~`, 설정 켜짐 `is~Enabled`.
6. 로거 변수는 `logger`. `OncePerRequestFilter`·`ResponseEntityExceptionHandler` 하위 클래스만 상위 필드와 이름이 충돌해(KT-56386) `log`를 쓴다.
7. DB 이름은 snake_case, 테이블은 복수형(`meals`), 엔티티는 단수형 + `@Table(name = "meals")`.
   - 외래 키 `fk_{테이블}_{참조 테이블 단수형}`(`fk_meals_user`). MySQL이 FK 컬럼에 자동으로 만드는 인덱스도 이 이름을 그대로 쓴다
   - 유니크 제약 `uk_{테이블}_{컬럼들}`. 컬럼에 `UNIQUE`를 바로 붙이면 MySQL이 컬럼명으로 이름을 정하므로 항상 `CONSTRAINT uk_... UNIQUE (...)`로 쓴다
   - 인덱스 `idx_{테이블}_{컬럼들}`(컬럼은 인덱스 순서대로, 64자 이하)
   - 테이블 이름을 바꾸면 제약·인덱스 이름도 같은 마이그레이션에서 바꾼다. `RENAME TABLE`은 제약 이름을 바꾸지 않으므로 인덱스는 `RENAME INDEX`, FK는 지우고 다시 만든다

## 동사

| 개념 | 단어 | 금지 |
|---|---|---|
| 생성 | create | add, register, make, new |
| 조회 / 목록 조회 | get / get + 복수형 | find, fetch, retrieve, read, list — 리포지토리 안에서만 Spring Data 관례대로 find(단건·목록·Redis 모두) |
| 수정 | update | modify, edit, change |
| 삭제 / 소프트 삭제 복구 | delete / restore | remove, destroy, drop / undo, recover, undelete |
| 조건 검색 | search | query, lookup |
| 존재 확인 | exists | has, contains |
| 계산 / 개수 조회 | calculate / count | compute. 세기만 하면 calculate 대신 count |
| 검증 | validate | check, verify |
| 증가 / 감소 | increase / decrease | add, plus / minus, subtract |
| 변환 | convert | parse, transform, map, to(Mapper 확장 함수 제외) |
| 원본(헤더·EXIF 등)에서 일부 꺼내기 | extract | pull |
| 확정(임시 → 최종) | confirm | finalize, promote, complete |
| 외부 스토리지에서 바이너리 받기 | download | fetch, retrieve. get은 도메인 리소스 조회 전용 |
| 초기화 | reset | clear, init, reinitialize |
| 인코딩 / 디코딩 | encode / decode | hash, serialize, deserialize |
| 후보 중 일부 선택 | pick | select, choose. 조건 조회는 search |
| 일치 확인(Boolean 반환) | matches | equals, compare |
| 정적 리소스 적재(~Loader) | load | read, fetch |
| 배치 실행 요청(내부 배치 컨트롤러) | trigger | run, execute |
| 세션 시작 / 재시작 | start / restart | begin, renew |
| 세션을 유지한 토큰 교체 | rotate | replace, swap, renew |
| 탈퇴 | withdraw | leave, quit, deactivate, resign, signout |
| 익명화 | anonymize | mask, scrub, pseudonymize(가명처리는 다른 개념) |
| 보상 받기 | claim | receive, accept, collect(수집 뜻의 collect는 그대로) |
| 관측 종료 | stop | end, finish, close. 시작은 Observation API의 `start()` |

## 클래스 접미사

기본 관례 외에 아래만 따로 정한다. `~Dto`·`~Manager`·`~Dao`·`~Command`는 쓰지 않는다.

| 용도 | 접미사 | 금지·구분 |
|---|---|---|
| HTTP 응답 DTO(중첩 DTO 포함) | ~Response | 값 객체 `Nutrition`은 예외 |
| HTTP 응답이 아닌 내부 반환 DTO | ~Result | 입력 DTO(`PushMessage`), 외부 API 역직렬화 타입, ~Claims는 대상 아님 |
| 설정 바인딩 / 빈 등록 | ~Properties / ~Config | ~Configuration |
| DTO ↔ 엔티티, 요청 → 값 객체 변환 | ~Mapper | ~Converter, ~Transformer |
| 계산 로직(최상위 함수 파일) | ~Calculator | ~Util, ~Calc, ~Calculation |
| 예외 → 에러 응답 | ~ExceptionHandler | ~Writer, ~Responder |
| 토큰 발급·검증 | ~Provider | ~Manager, ~Generator, ~Factory, ~TokenService(엔티티 서비스 `RefreshTokenService`는 해당 없음). 감싸서 무효화까지 보는 서비스는 `JwtService` |
| 외부 호출과 분리한 짧은 트랜잭션 | ~TransactionService | 같은 클래스의 `@Transactional` 호출은 프록시를 거치지 않아 별도 빈으로 둔다 |
| 외부 시스템 연동 | ~Client | ~Adapter, ~Gateway, ~Api |
| 입력값 검증 | ~Validator | ~Checker, ~Verifier |
| JWT 클레임 묶음 | ~Claims | ~Payload, ~Info |
| 외부 저장소의 정적 리소스 적재 | ~Loader | ~Fetcher |
| 파일·바이너리 포맷 해석 | ~Reader | ~Parser, ~Extractor |
| 서블릿·보안 필터 | ~Filter | ~Interceptor(MVC 계층의 다른 개념) |
| 컨트롤러 파라미터 주입 | ~ArgumentResolver | ~Resolver 단독, ~Injector |
| 프레임워크 확장 인터페이스 구현 | 인터페이스 이름 | `EnumOpenApiCustomizer` |

## 용어

### 공통

- 프로덕션 코드의 임시 대역 `Stub`(Fake·Mock 금지 — Mock은 테스트 전용), 확정 전·임시 `Temp`(Temporary·Tmp 금지)
- 같은 기능을 쓰는 흐름 구분 `~Purpose`(`AuthPurpose`), 외부 스케줄러가 부르는 배치 API `Internal`(`/api/v1/internal/**`)
- 운영자 `Admin`(패키지 admin, 경로 상수 `AdminPaths.PREFIX` = `/admin/v1`, 에러 코드 `api.admin.*`). Manager·Operator·Backoffice 금지. 배치 API `Internal`과 다른 것. 어드민 서비스에서만 만드는 빈은 `@ConditionalOnAdminEnabled`(`app.admin.enabled`)
- 커서 `Cursor` — 무한 스크롤 기준점(직전 묶음의 마지막 id), 응답은 `nextCursor` + `hasNext`. 페이지 번호 방식은 `Offset`
- 허용 오차 `Tolerance` — 목표 대비 비율(0.2 = ±20%). Margin·Threshold 금지
- 노출 비율·뽑힐 확률 `Rate` — 0~100 정수 퍼센트. Ratio·Percentage·Chance·Probability·Sampling 금지
- 비관적 잠금 조회 `~WithLock~`(`findProfileWithLockByUserId`, ForUpdate·Locked 금지)
- E2E: 테스트 클래스 `~E2eTest`(source set `e2eTest`), 공유 준비 상태 `Fixture`(Setup·Helper 금지), 남의 리소스를 확인하는 두 번째 계정 `Other`(Second·Another·Stranger 금지), REST Assured 요청 명세 `Spec`(Template·Builder 금지), prod 전환 전 최소 시나리오 `Smoke`(Sanity 금지)

### 사용자·인증

- 사용자 `User`, 신체·개인 정보 `Profile`, 성별 `Gender`, 본인 리소스 `Me`(`/users/me`, `getMe`)
- 로그인 제공자 `Provider`(`AuthProvider`, `providerUserId`) — 접미사 ~Provider(토큰 컴포넌트)와 다른 뜻
- 인증 `Auth`(패키지 auth, 에러 코드 `api.auth.*`), Spring Security 기반 시설 `Security`(패키지 security — 인증 API의 컨트롤러·DTO는 auth)
- 현재 사용자 principal `AuthUser`(Principal·LoginUser·SessionUser 금지), 컨트롤러 파라미터 주입 `@CurrentUser`
- 현재 운영자 principal `AuthAdmin`(AdminUser 금지 — 앱 사용자 `User`와 다른 주체), 컨트롤러 파라미터 주입 `@CurrentAdmin`. 권한 이름은 principal의 `AUTHORITY`(`ROLE_USER`·`ROLE_ADMIN`)
- 소유권 `Ownership`(`validateOwnership`, OwnerShip 금지)
- 토큰 `AccessToken`·`RefreshToken`, 이메일 확인 중 `EmailChallengeToken`, 확인 완료 `VerifiedToken`, 운영자 `AdminToken`(`createAdminToken`, 필드 `adminToken`). type 클레임 값은 `access`·`refresh`·`emailChallenge`·`verified`·`admin`. 설정 문자열·키 객체 모두 `secretKey`
- 어드민 토큰을 싣는 쿠키 `AdminTokenCookie`(쿠키 이름 `admin_token`). 앱은 Authorization 헤더, 어드민 웹은 쿠키로만 보낸다
- 이메일 인증 코드 `AuthCode`(엔티티·테이블만 `Code`·`codes`)
- 세션 `Session` — RefreshToken 한 행 = 로그인 1회. 절대 만료 `absoluteExpiresAt`, 재발급은 rotate, 재로그인은 restart. 이전 AccessToken 무효화 `Invalidation`(`invalidatedAt`)

### 고양이

- 고양이 `Cat`, 재화 `Coin`, 애정도 `Love`, 경험치 `Exp`
- 코인 잔액 `Balance` — 우편의 지급 개수 `Quantity`, 여러 우편 보상의 합 `Total`과 다른 뜻
- 고양이 체형 `CatWeight` — 사용자 몸무게 `Profile.weight`와 단어가 겹쳐 Cat·Profile 맥락으로 구분. 체형이 움직이는 칸 수 `Step`(Delta·Diff 금지)

### 식사·영양

- 식사 기록 `Meal`(History 금지), 끼니 `MealTime`, 식사 상태 `MealStatus`, 아이콘 `Icon`, 연속 기록 `Streak`(`streakDays`)
- 하루 제한을 세는 식사 기록 요청 `Attempt`(`countDailyAttempts`)
- 영양 분석 `Analysis` — 명사 자리(`AnalysisService`). 동사는 analyze이고 analysis·analyse를 동사로 쓰지 않는다
- 분석 결과에 대한 사용자 평가 `AnalysisFeedback`(Review·Survey 금지, Evaluation은 하루 평가 전용) — 별점 `Rating`(Star·Score 금지 — Score는 식사 영양 점수), 이유 `Reason`(Comment 금지 — catComment와 충돌), 평가 가능 여부 `canCreateAnalysisFeedback`(hasAnalysisFeedback·isRated 금지)
- 영양 4종 값 객체 `Nutrition`, 개별 항목 `Nutrient`(목록 `Nutrients`), 탄단지 묶음 `MacroNutrient`(Macro 단독 금지)
- 칼로리 `calory`(calories 금지), 탄수화물 `carbs`(carbohydrate 금지), 단백질 `protein`, 지방 `fat` — 단수형
- 하루 평가 `DailyNutritionEvaluation`, 권장 섭취량 `RecommendedDailyIntake`(전용 클래스 없이 `calculateRecommendedDailyIntake()`가 `Nutrition` 반환)
- 하루 섭취 합계 `Current`와 목표 섭취량 `Target` — 응답에서 쌍으로 쓴다

### 기간·캘린더

- 월간 `Monthly`, 일일 `Daily`(생기면 `Yearly`·`Weekly`), 캘린더 `Calendar`
- 조회 구간 `Period` — 반개구간 [start, end). 달력 그리드의 양끝 포함 구간은 `Range`

### 알림

- 요청한 기능의 알림 `ServicePush`(분석 완료·식사 리마인더), 광고성으로 볼 여지가 있는 알림 `MarketingPush`(리텐션 등)
- 끼니 시각 알림 `MealReminder`(`breakfastHour`·`lunchHour`·`dinnerHour`), 미접속 사용자 복귀 유도 `Retention`
- 수신 설정 `PushSetting`(PushConfig·PushPreference·PushOption 금지), 광고 수신 동의 `Agreed`(`marketingAgreedAt`, Consent·Approval·Accept 금지)
- 기기 토큰 `DeviceToken`, 기기 플랫폼 `DevicePlatform`
- 발송 대상 `Target`(영양의 목표 섭취량 Target과 다른 뜻), Redis 중복 발송 방지 표시 `SentMark`

### 문의

- 문의 `Inquiry` — 사용자가 쓴 질문 `Question`(`questionTitle`·`questionContent`)과 운영자 답변 `Answer`(`answerTitle`·`answerContent`, Reply 금지)
- 답변 여부는 상태 컬럼 없이 `isAnswered`(= `answeredAt` 있음), 답변 읽음 `answerReadAt` — 도메인의 Read이고 메서드 동사로는 쓰지 않는다
- 안 읽은 답변 `UnreadAnswer`(`hasUnreadAnswer`·`existsUnreadAnswer`, New 금지)

### 우편

- 우편 `Mail` — 보상을 받는 우편함의 한 건. 문의와 다른 리소스이고, 한 건을 Item이라 부르지 않는다
- 받기 `claim`, 받은 상태 `isClaimed`, 얻은 경로 문구 `content`(문의 본문 `questionContent`와 다른 뜻)
- 우편함 배지 `MailboxBadge`(`hasMailboxBadge`) — 받을 수 있는 우편과 안 읽은 문의 답변을 함께 알리는 홈 표시. 우편 한 건 `Mail`과 다른 뜻이고, Notification·Alert·Dot 금지
- 보상 `Reward`(하나 받기 `RewardResponse`, 모두 받기 `TotalRewardResponse`), 보상 상자 `RewardBox`(Box 단독·Chest 금지), 여러 우편 보상의 합 `Total`(Sum 금지), 열기 애니메이션 `animationUrl`(ActionUrl 금지)
- 보상 상자 등급 `RewardBoxCategory`(Grade·Tier 금지) — 우편 종류 `MailCategory`와 다른 enum
- 게임 아이템 `Item`(`itemId`), 지급 개수 `Quantity`(Amount·Count 금지)

### 이미지

- 이미지를 가리키는 S3 키 `imageKey`(스토리지 계층 파라미터는 `objectKey`), 업로드용 presigned URL `uploadUrl`, 조회용 URL `imageUrl`(presigned·CDN 공개 URL 공통)
- EXIF 촬영 시각 `capturedAt`(TakenAt·ShotAt 금지)

### 마이룸

- 마이룸 `Room`(MyRoom 금지), 마이룸 아이템 `RoomItem`(Furniture·Decoration 금지)
- 보유 아이템 `OwnedRoomItem` — 구매한 한 개. 같은 상품을 여러 개 사면 각각 따로이고 식별자 `ownedRoomItemId`는 그 한 개를 가리킨다(Inventory·UserItem 금지, roomItemId 금지 — 상품 id `itemId`와 헷갈림)
- 장착 `Equipped`(`isEquipped`, `equippedRoomItems`, Placement·Placed 금지), 배율 `Scale`(Size 금지 — 페이지 size와 충돌), 쌓임 순서 `ZIndex`(Order·Layer 금지)

### 관측

- 관측 `Observation`(Tracing·Instrumentation 금지). 우리가 만드는 관측·메트릭 이름은 `nyummy.`로 시작하고 점으로 구분한다(라이브러리 이름은 그대로)
- 트랜잭션 span `Transaction`(Tx 단독 금지, span 이름 접두 `tx`만 예외), 처리 결과 태그 `Outcome`(Result·Status 금지)
- 앱이 요청을 받을 준비가 되기 전 구간 `Startup`(Boot·Init 금지)
