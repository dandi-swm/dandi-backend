import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";

// prod에서는 어드민 Spring 서비스가 빌드 결과를 /admin/** 로 내려주므로 화면도 /admin/ 아래에 둔다.
// 개발할 때는 이 개발 서버가 화면을 내려주고, API(/admin/v1)만 백엔드로 넘긴다.
// 브라우저는 한 출처로만 요청하므로 쿠키(admin_token, Path=/admin/v1)와 CORS를 따로 신경 쓰지 않아도 된다.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");
  // 로컬 백엔드는 8080. prod 어드민 서비스는 SSM 터널로 띄운 뒤 그 주소에서 화면까지 바로 쓰면 된다.
  const target = env.ADMIN_API_TARGET || "http://localhost:8080";

  return {
    base: "/admin/",
    plugins: [react()],
    server: {
      // 백엔드 허용 출처·Google 승인 출처가 http://localhost:3000이라 포트를 고정한다.
      port: 3000,
      strictPort: true,
      proxy: {
        "/admin/v1": { target, changeOrigin: false },
      },
    },
  };
});
