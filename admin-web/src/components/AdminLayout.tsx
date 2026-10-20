import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, Navigate, Outlet, useNavigate } from "react-router-dom";
import { getMe, logout } from "../api/admin";
import { isUnauthorized } from "../api/client";

/**
 * 로그인이 필요한 화면의 틀. /me로 로그인 상태를 확인하고, 401이면 로그인 화면으로 보낸다.
 */
export function AdminLayout() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const me = useQuery({ queryKey: ["me"], queryFn: getMe });

  if (me.isPending) return <p className="center muted">불러오는 중…</p>;
  if (me.error && isUnauthorized(me.error)) return <Navigate to="/login" replace />;
  if (me.error) return <p className="center error">{me.error.message}</p>;

  const handleLogout = async () => {
    await logout();
    queryClient.clear();
    navigate("/login", { replace: true });
  };

  return (
    <div className="layout">
      <header className="header">
        <Link to="/inquiries" className="brand">
          nyummy 어드민
        </Link>
        <nav>
          <Link to="/inquiries">문의함</Link>
        </nav>
        <div className="me">
          <span>{me.data.name}</span>
          <button type="button" className="link" onClick={handleLogout}>
            로그아웃
          </button>
        </div>
      </header>
      <main className="content">
        <Outlet />
      </main>
    </div>
  );
}
