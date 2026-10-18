# 네이밍 컨벤션 사전

코드, 메서드, 클래스 이름을 지을 때 같은 개념에 여러 영어 단어가 혼용되는 것을 막기 위한 용어 통일 표입니다.

## 사용 규칙

1. 이름에 개념을 표현할 때, 아래 표에 해당 개념이 있으면 **반드시 그 단어를 사용**한다.
2. 표에 없는 개념이면 단어를 하나 정해 사용하고, **같은 커밋/PR에서 이 표에도 추가**한다.
3. 금지(동의어) 열에 있는 단어는 사용하지 않는다.
4. Claude도 동일 규칙을 따른다: 코드를 작성하다 표에 없는 새 개념의 단어를 쓰게 되면, 이 표를 함께 갱신할 것.
5. 메서드 이름은 **`동사 + 목적어`**로 짓는다. 목적어는 그 메서드가 반환하거나 변경하는 대상이다. 예외는 아래 "동사" 절의 예외 목록만 허용한다.
   - 동사만 있는 이름 금지: `send()` → `sendEmail()`, `rotate()` → `rotateToken()`
   - 목적어 자리에 형용사·부사구만 오는 이름 금지: `markSent()` → `createSentMark()`, `validateBeforeUpload()` → 검증 대상을 목적어로
   - 동사는 하나만: `createOrRestart()` → `startSession()`
   - 동사가 없는 이름 금지(컨트롤러 포함): `me()` → `getMe()`, `home()` → `getHome()`

## 동사 (메서드 네이밍)

| 개념 | 사용 단어 | 금지(동의어) | 예시 |
|---|---|---|---|
| 생성 | create | add, register, make, new | `createMeal()` |
| 조회(단건) | get | find(리포지토리 제외), fetch, retrieve, read | `getMeal()` |
| 조회(목록) | get + 복수형 | list, findAll(리포지토리 제외) | `getMeals()` |
| 리포지토리 조회 | find | get — Spring Data 관례. 단건·목록·Redis 리포지토리 모두. 리포지토리 밖에서는 get | `findByUserId()`, `findMealsByUserIdAndPeriod()` |
| 수정 | update | modify, edit, change | `updateCat()` |
| 삭제 | delete | remove, destroy, drop | `deleteMeal()` |
| 복구(소프트 삭제 취소) | restore | undo, recover, undelete | `restoreInquiry()` |
| 검색(조건) | search | query, lookup | `searchMeals()` |
| 존재 확인 | exists | has, contains | `existsUser()` |
| 계산 | calculate | compute (개수 조회는 아래 count) | `calculateScore()` |
| 개수 조회 | count | calculate (계산이 아니라 세기만 할 때) | `countDailyAttempts()`, `countMealsByUserIdAndPeriod()` |
| 검증 | validate | check, verify | `validateEmail()` |
| 증가 | increase | add, plus | `increaseCoin()` |
| 감소 | decrease | minus, subtract | `decreaseCoin()` |
| 변환 | convert | parse, transform, map, to | `convertMealStatus()`, `convertSpanName()` |
| 재시도 | retry | resume, redo | `retryNutritionAnalysis()` |
| 분석 | analyze | analysis(명사를 동사 자리에 금지), analyse | `analyzeNutrition()` |
| 확정(임시→최종 승격) | confirm | finalize, promote, complete | `confirmUpload()` |
| 바이너리 콘텐츠 조회(외부 스토리지) | download | fetch, retrieve (단, `get`은 도메인 리소스 조회 전용) | `downloadObject()`, `downloadText()` |
| 전송 | send | transmit, deliver, emit | `sendEmail()`, `sendAuthCode()`, `sendPushes()` |
| 초기화 | reset | clear, init, reinitialize | `resetAttemptCount()`, `resetPassword()` |
| 추출 | extract | pull (원본 데이터(헤더·EXIF 등)에서 일부를 꺼낼 때) | `extractCapturedAt()`, `extractToken()` |
| 인코딩 / 디코딩 | encode / decode | hash, serialize, deserialize | `encodePassword()`, `decodeIdToken()` |
| 수집 | collect | gather, accumulate | `collectEnumFields()` |
| 선택(후보 중 일부) | pick | select, choose (조건 조회는 search) | `pickFoodIconIds()` |
| 일치 확인(Boolean 반환) | matches | equals, compare | `matchesPassword()`, `matchesApiKey()` |
| 정적 리소스 적재(~Loader) | load | read, fetch | `loadCatAnimation()` |
| 배치 실행 요청(내부 배치 컨트롤러) | trigger | run, execute | `triggerMealReminders()` |
| 세션 시작 / 재시작 | start / restart | begin, renew | `startSession()`, `restartSession()` |
| 토큰 교체(세션 유지) | rotate | replace, swap, renew | `rotateToken()` |
| 탈퇴 | withdraw | leave, quit, deactivate, resign, signout | `withdrawUser()` |
| 익명화 | anonymize | mask, scrub, pseudonymize(가명처리는 다른 개념) | `anonymize()` |
| 받기(보상 수령) | claim | receive, accept, collect(수집과 별개) | `claimMail()`, `claimMails()` |
| 관측 종료 | stop | end, finish, close | `stopObservation()` — Micrometer Observation의 start/stop을 따른다. 관측 시작은 Observation API의 `start()`를 그대로 쓴다 |

※ 예외 (위 표의 동사와 사용 규칙 5항 `동사 + 목적어`를 따르지 않아도 되는 경우)

- **Mapper 변환 확장 함수**: Kotlin 관례에 따라 `toXxx`를 사용한다. Xxx는 반환 타입 이름과 일치시킨다. 예: `Meal.toNutrition()`, `CreateMealRequest.toEntity()`
- **인증 행위 자체가 이름인 메서드**: `login()`, `logout()`, `signUp()`, `refresh()` — 목적어(세션·토큰)가 행위에 들어 있다
- **프레임워크가 이름을 정하는 메서드**: 인터페이스·상위 클래스 override(`doFilterInternal`, `shouldNotFilter`, `commence`, `handle`, `customize`), `@Bean` 팩토리 메서드(반환 타입의 camelCase, 예: `s3Client()`, `passwordEncoder()`)
- **Spring Data 파생 쿼리**: `findBy~`, `existsBy~`, `deleteBy~`, `countBy~` — 목적어 대신 `By` 뒤 조건이 대상을 나타낸다
- **Kotlin 관례 함수**: 연산자 함수(`operator fun plus`), companion 팩토리(`of`, `from`), `main`
- **Boolean 반환 함수**: `is~`/`has~`/`can~` 접두 함수(`isAbsoluteExpired()`)는 Boolean 네이밍을 따른다
- **엔티티 자신의 상태 전이**: `withdraw()`, `anonymize()` — 목적어가 수신자 자신이라 붙일 것이 없다. 단일 필드 변경은 기존 `update<필드>` 패턴을 쓴다(`updateDeletedAt`)

## 클래스 접미사

| 용도 | 접미사 | 금지 |
|---|---|---|
| 요청 DTO | ~Request | ~Req, ~Command |
| HTTP 응답 DTO | ~Response | ~Res, ~Dto, ~Result — 응답 안에 중첩된 DTO도 ~Response (예: `StreakResponse`). 값 객체 `Nutrition`은 예외 |
| 내부 반환 DTO(HTTP 응답 아님) | ~Result | ~Response, ~Dto — 예: `S3UploadResult`, `PushResult`, `UploadedImageResult`. 입력용 DTO(`PushMessage`, `EmailMessage`), 외부 API 역직렬화 타입(`GeminiGenerateContentResponse` 등), ~Claims는 대상 아님 |
| 서비스 | ~Service | ~Manager, ~Handler |
| 리포지토리 | ~Repository | ~Dao |
| 컨트롤러 | ~Controller | ~Api, ~Resource |
| 설정 바인딩(@ConfigurationProperties) | ~Properties | ~Config |
| 빈 등록 설정(@Configuration) | ~Config | ~Configuration, ~Provider — 예: `S3Config`, `ArgonConfig` |
| 변환(DTO↔엔티티, 요청 → 값 객체) | ~Mapper | ~Converter, ~Transformer — 예: `CatMapper`, `CommonHeaderMapper`(HttpServletRequest → CommonHeader) |
| 계산 로직(최상위 함수 파일) | ~Calculator | ~Calc, ~Calculation, ~Util |
| 서블릿/보안 필터 | ~Filter | ~Interceptor (인터셉터는 MVC 계층의 다른 개념) |
| 예외를 에러 응답으로 변환 | ~ExceptionHandler | ~Writer, ~Responder — 예: `GlobalExceptionHandler`(@RestControllerAdvice), `SecurityExceptionHandler`(필터 단계 예외를 HandlerExceptionResolver로 위임) |
| 토큰 발급/검증 컴포넌트 | ~Provider | ~Manager, ~Generator, ~Factory, ~TokenService — 예: `JwtProvider`. 이를 감싸 무효화 확인까지 하는 서비스는 `JwtService`. 엔티티 이름이 Token으로 끝나는 도메인 서비스(`RefreshTokenService`)는 ~TokenService 금지 대상이 아님 |
| 외부 호출과 분리한 짧은 트랜잭션 구간(별도 빈) | ~TransactionService | 같은 클래스의 @Transactional 메서드 호출은 프록시를 거치지 않아 별도 빈으로 둔다 — 예: `InquiryTransactionService` |
| 외부 시스템 연동 | ~Client | ~Adapter, ~Gateway, ~Api — 예: `S3StorageClient`, `EmailClient`, `PushClient`, `OAuthClient`, `NutritionAnalysisClient` |
| 입력값 검증 컴포넌트 | ~Validator | ~Checker, ~Verifier — 예: `ImageUploadValidator` |
| JWT 클레임 묶음 | ~Claims | ~Payload, ~Info — 예: `AccessTokenClaims`, `VerifiedClaims` |
| 외부 저장소의 정적 리소스를 읽어 앱에서 쓰는 형태로 구성 | ~Loader | ~Fetcher — 예: `CatAnimationLoader`(S3의 애니메이션 JSON) |
| 파일·바이너리 포맷을 해석해 값을 꺼냄 | ~Reader | ~Parser, ~Extractor — 예: `ExifCaptureTimeReader` |
| 프레임워크 확장 인터페이스 구현 | 인터페이스 이름을 접미사로 | 예: `EnumOpenApiCustomizer`(springdoc Customizer), `ObservationTransactionExecutionListener`(Spring TransactionExecutionListener) |
| 컨트롤러 파라미터 주입 | ~ArgumentResolver | ~Resolver 단독, ~Injector — 예: `CommonHeaderArgumentResolver` |

## Boolean 네이밍

| 개념 | 접두사 | 예시 |
|---|---|---|
| 상태/여부 | is~ | `isActive`, `isDeleted` |
| 보유/존재 여부 | has~ | `hasCat`, `hasNext`, `hasUnreadAnswer` — Boolean 프로퍼티 전용. 존재를 확인하는 메서드는 exists 동사 |
| 가능 여부 | can~ | `canEvolve` |
| 설정 켜짐 여부 | is~Enabled | `isServicePushEnabled`, `isMarketingPushEnabled` |

## 도메인 용어

이 프로젝트 고유 명사는 아래로 고정한다.

### 공통

| 개념 | 사용 단어 | 비고 |
|---|---|---|
| 임시 구현 | Stub | 프로덕션 코드의 임시 대역 접두 (예: StubPushClient). Fake, Mock 금지 — Mock은 테스트 전용 |
| 커서 | Cursor | 무한 스크롤에서 다음 묶음의 기준점(직전 묶음의 마지막 id). 응답은 nextCursor + hasNext, 조회는 id < cursor. 페이지 번호 방식은 Offset으로 구분. 단, 마이룸 보유 아이템은 장착 먼저 → 최신순으로 정렬하므로 커서 아이템의 장착 여부를 함께 비교해 그보다 뒤 순서만 조회한다(커서 값은 여전히 roomItemId 하나) |
| 유효 기간 | TimeToLive | 토큰·인증 코드·presigned URL 등의 수명(Duration). accessTimeToLive / refreshTimeToLive / uploadUrlTimeToLiveMinutes. Expiration, Ttl, ExpiresIn, Validity 금지 |
| 만료 시각 | ExpiresAt | 유효 기간이 끝나는 시점(Instant). expiresAt, absoluteExpiresAt. 기간은 TimeToLive |
| 임시 | Temp | 확정 전 상태(S3 temp 태그)·임시 비밀번호(tempPassword). Temporary, Tmp 금지 |
| 용도 | Purpose | 같은 기능을 어떤 흐름에 쓰는지 구분하는 enum (AuthPurpose, ImagePurpose). 토큰 종류는 TokenType |
| 내부 API | Internal | 외부 스케줄러가 호출하는 배치 엔드포인트(/api/v1/internal/**, API 키 인증). 패키지 internal |
| 허용 오차 | Tolerance | 목표값 대비 허용 범위를 비율로 표현(0.2 = ±20%). 영양 판정(app.meal.intake-tolerance)과 고양이 체형(app.cat.weight-update-tolerance)이 값은 같아도 각각 선언한다. Margin, Threshold 금지 — Range는 캘린더 범위에 쓴다 |
| 노출 비율 | Rate | 0~100 정수 퍼센트로 표현하는 노출·적용 비율 (analysisFeedbackRate). Ratio, Percentage, Chance, Probability, Sampling 금지 |
| E2E 테스트 | E2e | 배포된 서버에 HTTP로 요청하는 테스트. source set `e2eTest`, 클래스 `~E2eTest`, 설정 `E2eProperties`. Jwt처럼 식별자는 E2e로 쓴다. E2E 전체 대문자 금지 |
| 스모크 | Smoke | prod blue/green 전환 전에 돌리는 최소 시나리오 태그(`@Smoke`, tag `smoke`). Sanity 금지 |
| 테스트 사전 상태 | Fixture | E2E 테스트가 공유하는 준비 상태(로그인 토큰, 업로드한 이미지 등). `AuthFixture`, `ImageFixture`. Setup, Helper 금지 |
| 다른 사용자(E2E) | Other | 남의 리소스 접근(403·invalidKey)을 확인하는 두 번째 고정 계정. `otherAccessToken`, `E2E_OTHER_USER_EMAIL`. Second, Another, Stranger 금지 |
| 요청 명세 | Spec | REST Assured `RequestSpecification` 묶음. `RequestSpecs`, `createAuthorizedSpec()`. Template, Builder 금지 |

### 사용자

| 개념 | 사용 단어 | 비고 |
|---|---|---|
| 사용자 | User | |
| 프로필 | Profile | 신체/개인 정보 (users에서 분리됨) |
| 성별 | Gender | MALE / FEMALE / OTHER. DB는 VARCHAR(20)에 enum 이름 저장 (V0.14에서 TINYINT 0/1 인코딩 대체) |
| 본인 | Me | 인증된 사용자 자신의 리소스 경로(/users/me)와 조회(getMe) |
| 로그인 제공자 | Provider | AuthProvider(EMAIL / KAKAO / GOOGLE / NAVER), users.provider, providerUserId. 클래스 접미사 ~Provider(토큰 컴포넌트)와 다른 의미 |

### 고양이

| 개념 | 사용 단어 | 비고 |
|---|---|---|
| 고양이 캐릭터 | Cat | |
| 재화 | Coin | |
| 애정도 | Love | |
| 경험치 | Exp | |
| 고양이 체형 | Weight | CatWeight(-2~2, LEAN~PLUMP). 사용자 몸무게(Profile.weight, kg)와 단어가 겹치니 Cat/Profile 맥락으로 구분 |
| 체형 변화 단계 | Step | 한 평가 구간에서 체형이 움직이는 칸 수(-1/0/1). calculateWeightStep, updateWeightByStep. Delta, Diff 금지 |

### 식사

| 개념 | 사용 단어 | 비고 |
|---|---|---|
| 식사 기록 | Meal | history 아님 (V0.4에서 history → meal, V0.31에서 meal → meals로 rename됨) |
| 아이콘 | Icon | |
| 식사 상태 | MealStatus | WAITING / ANALYZING / COMPLETED / FAILED / UNKNOWN. DB status 문자열을 enum으로 변환해 응답 |
| 분석 | Analysis | 영양 분석. 명사 위치에 사용 (AnalysisService, retryNutritionAnalysis). 진행 중 상태는 MealStatus.ANALYZING |
| 끼니 | MealTime | BREAKFAST / LUNCH / DINNER |
| 기록 시도 | Attempt | 식사 기록 요청 횟수(하루 제한 대상). todayAttemptCount, countDailyAttempts |
| 연속 기록 | Streak | 연속으로 식사를 기록한 일수. streakDays |
| 분석 피드백 | AnalysisFeedback | AI 영양 분석 결과에 대한 사용자 평가(별점+이유). 테이블 analysis_feedbacks. 식사당 1건·수정 불가. Review, Survey, Evaluation 금지 (Evaluation은 DailyNutritionEvaluation 전용) |
| 별점 | Rating | 0.5~5.0, 0.5 단위 (0.0 불허). Star, Score 금지 — Score는 Meal의 영양 점수 |
| 평가 이유 | Reason | 별점 사유 자유 입력(500자). Comment 금지 — catComment와 충돌 |
| 평가 가능 여부 | CanCreateAnalysisFeedback | 응답 필드 canCreateAnalysisFeedback(분석 완료 + 추첨 대상 + 미평가). 조회는 existsByMealId. hasAnalysisFeedback, isRated 금지 |

### 영양

| 개념 | 사용 단어 | 비고 |
|---|---|---|
| 영양 | Nutrition | 영양 4종(calory/carbs/protein/fat) 값 객체로도 사용 (응답 current/target 공용) |
| 영양소 | Nutrient | Nutrition의 개별 항목(calory/carbs/protein/fat)을 가리킬 때. 목록은 복수형 Nutrients |
| 다량영양소 | MacroNutrient | 탄수화물·단백질·지방 묶음(칼로리 제외). 하루 평가에서 칼로리는 필수 조건, 다량영양소는 과반만 본다(MACRO_NUTRIENTS, minPositiveMacroCount). Macro 단독 사용 금지 |
| 칼로리 | Calory | 코드·응답·엔티티/DB 모두 calory로 통일 (calories 금지) |
| 탄수화물 | Carbs | carbohydrate 아님 |
| 단백질 | Protein | 단수형 고정 (proteins 아님) |
| 지방 | Fat | 단수형 고정 (fats 아님) |
| 하루 평가 | DailyNutritionEvaluation | POSITIVE / NEGATIVE / UNRECORDED |
| 권장 섭취량 | RecommendedDailyIntake | Recommended(권장) + Intake(섭취량). 전용 클래스 없음 — calculateRecommendedDailyIntake()가 Nutrition을 반환 |
| 현재 섭취량 | Current | 하루 영양 합계. 응답에서 Target과 쌍으로 사용 |
| 목표 섭취량 | Target | 응답 표기용. 값은 RecommendedDailyIntake 계산 결과. 알림의 발송 대상(Target)과 다른 의미 |

### 기간·캘린더

| 개념 | 사용 단어 | 비고 |
|---|---|---|
| 월간 | Monthly | 연간/주간이 생기면 Yearly/Weekly로 통일 |
| 일일 | Daily | |
| 캘린더 | Calendar | 주 시작 = 일요일 (프론트 확인 전 임시 가정) |
| 기간 | Period | 시작~종료 구간. 조회는 반개구간 [start, end) |
| 캘린더 범위 | Range | 달력 그리드의 [시작일, 종료일] 양끝 포함 구간 (calculateMonthlyCalendarRange). 조회용 반개구간은 Period |

### 인증·토큰

| 개념 | 사용 단어 | 비고 |
|---|---|---|
| 인증 | Auth | authentication 축약. 패키지명(auth), 에러코드(api.auth.*) 접두로 사용 |
| 인증된 현재 사용자 | AuthUser | SecurityContext의 principal 타입. Principal, LoginUser, SessionUser 금지 |
| 현재 사용자 주입 | CurrentUser | @AuthenticationPrincipal 메타 어노테이션(@CurrentUser). 인증 필수 경로의 컨트롤러 파라미터 전용 |
| 소유권 | Ownership | 리소스가 요청 사용자 소유인지. 검증은 validateOwnership — ownership은 한 단어 (OwnerShip 금지) |
| 보안 인프라 | Security | Spring Security 메커니즘(설정·필터·principal)의 패키지명(security). 인증 API(컨트롤러/DTO)는 auth 패키지와 분리 |
| JWT | Jwt | 식별자 표기는 Jwt (클래스 `JwtProvider`, 패키지 security.jwt). JWT 전체 대문자, JsonWebToken 금지 |
| OAuth | OAuth | 소셜 로그인 프로토콜. 식별자 표기는 OAuth(대문자 A) — `OAuthService`, `OAuthClient`, `loginOAuth()`. Jwt와 달리 기존 코드에 굳은 표기를 따른다. Oauth 금지 |
| 접근 토큰 | AccessToken | 30분 수명, API 인증용. type 클레임 값은 "access". 필드명 accessToken |
| 갱신 토큰 | RefreshToken | 15일 수명, 재발급용. type 클레임 값은 "refresh". 필드명 refreshToken |
| 시크릿 키 | SecretKey | 설정의 Base64 문자열(JwtProperties.secretKey, .env의 JWT_SECRET_KEY)과 디코딩된 키 객체 모두 secretKey |
| 인증 코드 | AuthCode | 이메일로 보내는 확인 코드. 엔티티·테이블은 Code(codes), 메서드·DTO는 AuthCode (sendAuthCode, validateAuthCode) |
| 이메일 확인 토큰 | EmailChallengeToken | 코드 발송 후 확인까지 들고 다니는 토큰. type 클레임 "emailChallenge", JWE |
| 확인 완료 토큰 | VerifiedToken | 코드 확인 후 발급, 회원가입·비밀번호 재설정에 제출. type 클레임 "verified", JWE |
| 세션 | Session | RefreshToken 행 하나 = 로그인 1회의 유효 구간. 절대 만료 absoluteExpiresAt. 재발급은 rotate, 재로그인은 restart |
| 토큰 무효화 | Invalidation | 비밀번호 변경 등으로 그 이전에 발급된 AccessToken을 무효 처리. 시각은 invalidatedAt |

### 알림

| 개념 | 사용 단어 | 비고 |
|---|---|---|
| 서비스 알림 | ServicePush | 사용자가 요청한 기능을 수행하는 알림(분석 완료·식사 리마인더). 기본 수신 |
| 마케팅 알림 | MarketingPush | 광고성 정보로 볼 여지가 있는 알림(리텐션 복귀 유도 등). 사전 동의 필요, 기본 거부 |
| 알림 수신 설정 | PushSetting | profile의 수신 여부 묶음. PushConfig, PushPreference, PushOption 금지 |
| 동의 | Agreed | 광고성 정보 수신 동의. 시점은 marketingAgreedAt. Consent, Approval, Accept 금지 |
| 기기 토큰 | DeviceToken | FCM 기기 토큰. 테이블 device_tokens |
| 기기 플랫폼 | DevicePlatform | ANDROID / IOS |
| 식사 리마인더 | MealReminder | 끼니 시각에 보내는 서비스 알림. 시각은 profile의 breakfastHour / lunchHour / dinnerHour(0~23) |
| 리텐션 | Retention | 미접속 사용자 복귀 유도 알림(마케팅 알림) |
| 발송 대상 | Target | 알림을 받을 사용자 묶음 (MealReminderTargetResult, findRetentionPushTargets). 영양의 Target과 다른 의미 |
| 중복 발송 방지 | SentMark | 같은 알림을 두 번 보내지 않도록 Redis에 남기는 발송 표시 (createSentMark, deleteSentMarks) |

### 문의

| 개념 | 사용 단어 | 비고 |
|---|---|---|
| 문의 | Inquiry | 문의함의 문의 한 건. 테이블 inquiries. 보상을 받는 우편함은 별도 리소스 Mail |
| 문의 유형 | InquiryCategory | BUG_REPORT / QUESTION / SUGGESTION. Type, Kind 금지 |
| 질문 | Question | 사용자가 작성한 문의 부분(유형·제목·내용·첨부 이미지). 필드는 questionTitle / questionContent, 수정은 updateQuestion |
| 답변 | Answer | 운영자가 작성한 답변. 필드는 answerTitle / answerContent. Reply 금지 |
| 답변 시각 | AnsweredAt | 최초 답변 시각으로 고정. 답변 여부는 상태 컬럼 없이 isAnswered(= answeredAt != null)로 계산 |
| 답변 읽음 | Read | 사용자가 답변을 읽음. 시각은 answerReadAt(처음 읽은 시각 고정). 조회 동사 read 금지와는 별개의 도메인 의미이며, 메서드 동사로는 쓰지 않는다 |
| 안 읽은 답변 | UnreadAnswer | 답변이 있고 아직 읽지 않음. hasUnreadAnswer(문의 단건), hasUnreadInquiryAnswer(홈), existsUnreadAnswer(조회). New 금지 |

### 우편

| 개념 | 사용 단어 | 비고 |
|---|---|---|
| 우편 | Mail | 보상(코인·아이템·보상 상자)을 받는 우편함의 한 건. 문의(Inquiry)와 다른 리소스 |
| 우편 종류 | MailCategory | REWARD_BOX / COIN / ITEM. 응답 필드 category. InquiryCategory와 맞춰 Type, Kind 금지 |
| 받은 상태 | Claimed | isClaimed. 받기 동사는 claim |
| 얻은 경로 | Content | 우편을 얻게 된 경로 문구(필드 content). 문의의 questionContent(본문)와 뜻이 다름 |
| 보상 | Reward | 받기로 지급되는 코인·아이템 묶음. 하나 받기는 RewardResponse, 모두 받기는 TotalRewardResponse |
| 보상 상자 | RewardBox | 코인·아이템이 든 상자. 하나 받기 응답의 rewardBox로 열기 연출. Box 단독, Chest 금지 |
| 아이템 | Item | 게임 아이템(itemId). 우편 한 건은 Item이 아니라 Mail |
| 수량 | Quantity | 지급되는 코인·아이템 개수. Amount 금지, Count 금지(count는 개수 조회 동사) |
| 합계 | Total | 여러 우편의 보상을 합친 값(TotalRewardResponse). Sum 금지. 영양의 Current(하루 섭취 합계)와 다른 의미 |
| 애니메이션 URL | AnimationUrl | 보상 상자 열기 애니메이션 리소스 URL(필드 animationUrl). 지금은 스프라이트 시트, 이후 애니메이션 파일 형식으로 바뀔 수 있음. CatAnimation과 같은 Animation 표기. ActionUrl 금지 |

### 이미지

| 개념 | 사용 단어 | 비고 |
|---|---|---|
| 이미지 키 | ImageKey | 도메인에서 이미지를 가리키는 S3 객체 키(필드 imageKey). 스토리지 계층(S3StorageClient) 파라미터는 objectKey |
| 업로드 URL | UploadUrl | 업로드용 presigned PUT URL (createUploadUrl) |
| 이미지 URL | ImageUrl | 조회용 presigned GET URL (createImageUrl). 마이룸 아이템 이미지는 CDN base URL + imageKey로 만든 공개 URL에도 같은 이름을 쓴다 |
| 촬영 시각 | CapturedAt | EXIF에서 읽은 촬영 시각. TakenAt, ShotAt 금지 |

### 마이룸

| 개념 | 사용 단어 | 비고 |
|---|---|---|
| 마이룸 | Room | 보유 아이템을 장착해 꾸미는 방. 패키지 room, 경로 /rooms. MyRoom 금지 |
| 마이룸 아이템 | RoomItem | 마이룸에 놓는 아이템. Furniture, Decoration 금지 |
| 보유 아이템 | OwnedRoomItem | 사용자가 구매한 아이템 한 개. 같은 아이템을 여러 개 사면 각각 따로 존재하며 좌표·장착 여부를 따로 가진다. 식별자는 roomItemId(상품이 아니라 구매한 한 개를 가리킴). Inventory, UserItem 금지 |
| 아이템 카테고리 | RoomItemCategory | BACKGROUND / BED / CAT_WHEEL / CARPET / ETC. Type, Kind 금지 |
| 장착 | Equipped | 마이룸에 놓은 상태. isEquipped, equippedRoomItems, updateEquippedRoomItems. 홈 응답에도 같은 equippedRoomItems(zIndex 오름차순)로 포함. Placement, Placed 금지 |
| 배율 | Scale | 원본 대비 크기(Float, 1.0 = 원본). Size 금지 — 페이지네이션 size와 충돌 |
| 쌓임 순서 | ZIndex | 장착 아이템이 겹칠 때 위아래 순서. 클수록 위. Order, Layer 금지 |

### 관측

| 개념 | 사용 단어 | 비고 |
|---|---|---|
| 관측 | Observation | Micrometer Observation 한 건. 메트릭과 span을 함께 만든다(ObservationConfig, stopObservation). Tracing, Instrumentation 금지 |
| 관측 이름 접두 | nyummy. | 우리가 만드는 Observation·메트릭 이름은 `nyummy.`로 시작하고 점으로 구분한다(nyummy.transaction). 라이브러리가 만드는 이름(http.server.requests, jdbc.*)은 그대로 둔다 |
| 트랜잭션 span | Transaction | 트랜잭션 하나 = span 하나(관측 이름 nyummy.transaction, span 이름 "tx 클래스.메서드"). 태그 transaction.name / transaction.read-only / transaction.outcome. 메트릭은 끄고 span으로만 본다. Tx 단독 금지(span 이름 접두 tx는 예외) |
| 결과 | Outcome | 관측 태그의 처리 결과 값. 트랜잭션은 commit / rollback / begin-failed / commit-failed / rollback-failed. 비즈니스 거절로 인한 rollback은 에러가 아니다. Result 금지(~Result 접미사와 충돌), Status 금지(MealStatus와 충돌) |
| 기동 | Startup | 앱이 요청을 받을 준비가 되기 전(readiness가 ACCEPTING_TRAFFIC 전) 구간. startupJdbcObservationPredicate. Boot, Init 금지 |
| JDBC | Jdbc | 식별자 표기는 Jdbc(Jwt와 같은 규칙). JDBC 전체 대문자 금지 |

## 케이스 규칙

| 대상 | 규칙 | 예시 |
|---|---|---|
| 클래스 | PascalCase | `MealService` |
| 함수/프로퍼티 | camelCase | `getMeal`, `imageUrl` |
| DB 테이블/컬럼 | snake_case | `meals`, `image_url` |
| DB 테이블 이름 | 복수형 | `meals`, `refresh_tokens` (V0.31에서 통일). 엔티티 클래스는 단수형(`Meal`) + `@Table(name = "meals")` |
| DB 외래 키 | `fk_{테이블}_{참조 테이블 단수형}` | `fk_meals_user`, `fk_meals_icon`, `fk_mail_items_mail`. MySQL이 FK 컬럼에 자동으로 만드는 인덱스도 같은 이름을 그대로 쓴다(따로 `idx_`로 바꾸지 않는다) |
| DB 유니크 제약 | `uk_{테이블}_{컬럼들}` | `uk_meals_image_key`, `uk_cats_user_id`, `uk_users_provider_email`. 컬럼 정의에 `UNIQUE`를 바로 붙이면 MySQL이 컬럼명(`user_id`, `email`)으로 이름을 자동으로 정하므로, 항상 `CONSTRAINT uk_... UNIQUE (...)`로 이름을 붙인다 |
| DB 인덱스 | `idx_{테이블}_{컬럼들}` | `idx_meals_user_id_meal_at`, `idx_mails_user_id_id`. 컬럼은 인덱스 순서대로 적는다. 이름은 64자 이하(MySQL 식별자 제한) |
| 테이블 이름을 바꿀 때 | 제약·인덱스 이름도 같은 마이그레이션에서 바꾼다 | `RENAME TABLE`은 제약 이름을 바꾸지 않는다(V0.4·V0.31에서 남은 `fk_history_user`, `fk_meal_icon`). 인덱스는 `RENAME INDEX`, FK는 지우고 다시 만든다 |

## 기타

| 대상 | 규칙 | 비고 |
|---|---|---|
| 로거 변수명 | logger | log 금지. `companion object { private val logger = LoggerFactory.getLogger(Xxx::class.java) }` 단, 상위 클래스가 `logger` 필드를 가진 경우(`OncePerRequestFilter`, `ResponseEntityExceptionHandler` 상속)는 이름이 충돌해 컴파일되지 않으므로(KT-56386) `log`를 쓴다 |
