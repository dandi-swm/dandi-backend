# nyummy 어드민 웹

운영 어드민 화면입니다(Vite + React + TypeScript). 화면은 `/admin/` 아래, API는 `/admin/api/` 아래에 있습니다.
어드민 API는 인터넷에 열려 있지 않습니다(ALB 화이트리스트 밖).

## prod에서 쓰기

prod에서는 **어드민 Spring 서비스가 이 화면을 직접 내려줍니다.** Docker 이미지를 만들 때 이 폴더를 빌드해
`/app/admin-web`에 넣고, 어드민 서비스(`ADMIN_ENABLED=true`)가 `/admin/**`로 내려줍니다.

1. SSM 터널을 엽니다(로컬 포트 **18080 고정** — 허용 출처와 Google 승인 출처가 이 주소입니다).
2. Chrome으로 http://localhost:18080/admin 을 엽니다.

Node나 `npm install`이 필요 없습니다.

## 개발하기

1. 백엔드를 어드민 켜진 상태로 띄웁니다. dandi-backend의 `.env.local`에 아래가 있어야 합니다.
   ```
   ADMIN_ENABLED=true
   ADMIN_GOOGLE_CLIENT_ID=<어드민 웹용 Google OAuth 클라이언트 ID>
   ```
   그리고 `admins` 테이블에 내 이메일(소문자)이 있어야 로그인됩니다.
2. 이 폴더에서:
   ```bash
   npm install
   cp .env.example .env.local
   npm run dev
   ```
3. Chrome으로 http://localhost:3000/admin/ 을 엽니다.
   - 개발 서버는 화면을 직접 내려주고 `/admin/api` 요청만 백엔드(`ADMIN_API_TARGET`, 기본 localhost:8080)로 넘깁니다.
   - 포트는 3000으로 고정입니다. `127.0.0.1`이 아니라 `localhost`로 여세요.
   - Safari는 localhost의 Secure 쿠키를 저장하지 않을 수 있어 Chrome을 권장합니다.

Google 클라이언트 ID는 화면이 백엔드(`GET /admin/api/auth/google-client`)에서 받아 옵니다. 웹 쪽에 따로 두지 않습니다.

## 구조

- 로그인: Google 버튼 → `POST /admin/api/auth/login` → 서버가 httpOnly 쿠키(`admin_token`)를 굽습니다. JS는 토큰을 다루지 않습니다.
- 모든 요청은 `credentials: "include"`. 401이 오면 로그인 화면으로 갑니다.
- 화면: 문의함(기본 "답변 대기") → 문의 상세 → 답변 등록·수정
  - 답변 등록은 사용자에게 알림이 갑니다. 수정은 오타 정정용이라 알림이 가지 않습니다.
- 화면 경로를 새로고침해도 되도록 서버는 없는 화면 경로에 `index.html`을 내려줍니다(`AdminWebFallbackResourceResolver`).

## 지킬 것

- 문의 제목·내용은 사용자가 입력한 값입니다. **텍스트로만 렌더링**하고 `dangerouslySetInnerHTML`이나 HTML 마크다운 렌더링을 쓰지 않습니다.

`tools/id-token.html`은 백엔드 로그인 API를 curl로 시험할 때 ID 토큰을 받는 테스트 페이지입니다.
