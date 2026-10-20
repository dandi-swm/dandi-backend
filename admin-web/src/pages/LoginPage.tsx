import { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { getGoogleClient, login } from "../api/admin";
import { ApiError } from "../api/client";

/**
 * Google 버튼으로 ID 토큰을 받아 백엔드 로그인에 넘긴다. 어드민 토큰은 서버가 httpOnly 쿠키로 구워 주므로
 * 여기서는 저장하지 않는다. Google 클라이언트 ID는 백엔드 설정(ADMIN_GOOGLE_CLIENT_ID)에서 받아 온다.
 */
export function LoginPage() {
  const buttonRef = useRef<HTMLDivElement>(null);
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [error, setError] = useState<string | null>(null);
  const googleClient = useQuery({ queryKey: ["google-client"], queryFn: getGoogleClient, staleTime: Infinity });
  const clientId = googleClient.data?.clientId;

  useEffect(() => {
    if (!clientId) return;

    // GIS 스크립트는 async로 불러오므로 준비될 때까지 기다린다.
    const timer = window.setInterval(() => {
      const google = window.google;
      if (!google || !buttonRef.current) return;
      window.clearInterval(timer);

      google.accounts.id.initialize({
        client_id: clientId,
        callback: async ({ credential }) => {
          setError(null);
          try {
            const admin = await login(credential);
            queryClient.setQueryData(["me"], admin);
            navigate("/inquiries", { replace: true });
          } catch (e) {
            setError(e instanceof ApiError ? e.message : "로그인에 실패했습니다.");
          }
        },
      });
      google.accounts.id.renderButton(buttonRef.current, { type: "standard", theme: "outline", size: "large" });
    }, 100);

    return () => window.clearInterval(timer);
  }, [clientId, navigate, queryClient]);

  return (
    <main className="login">
      <h1>nyummy 어드민</h1>
      <p className="muted">등록된 팀원 Google 계정으로 로그인하세요.</p>
      <div ref={buttonRef} />
      {googleClient.error && <p className="error">로그인 설정을 불러오지 못했습니다: {googleClient.error.message}</p>}
      {error && <p className="error">{error}</p>}
    </main>
  );
}
