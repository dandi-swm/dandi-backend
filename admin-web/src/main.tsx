import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { MutationCache, QueryCache, QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createBrowserRouter, RouterProvider } from "react-router-dom";
import { isUnauthorized } from "./api/client";
import { AdminLayout } from "./components/AdminLayout";
import { IconListPage } from "./pages/IconListPage";
import { LinksPage } from "./pages/LinksPage";
import { InquiryDetailPage } from "./pages/InquiryDetailPage";
import { InquiryListPage } from "./pages/InquiryListPage";
import { LoginPage } from "./pages/LoginPage";
import { ProductListPage } from "./pages/ProductListPage";
import { PromotionListPage } from "./pages/PromotionListPage";
import { RoomItemListPage } from "./pages/RoomItemListPage";
import { ShopBannerListPage } from "./pages/ShopBannerListPage";
import "./styles.css";

// 화면은 /admin/ 아래에 있다(vite base, 라우터 basename과 같은 값).
const BASE_PATH = import.meta.env.BASE_URL.replace(/\/$/, "");
const LOGIN_PATH = `${BASE_PATH}/login`;

// 토큰(쿠키)이 만료되거나 어드민이 비활성화되면 어느 요청이든 401이 온다. 그때는 로그인 화면으로 보낸다.
const redirectToLoginOnUnauthorized = (error: unknown) => {
  if (isUnauthorized(error) && window.location.pathname !== LOGIN_PATH) {
    window.location.assign(LOGIN_PATH);
  }
};

const queryClient = new QueryClient({
  queryCache: new QueryCache({ onError: redirectToLoginOnUnauthorized }),
  mutationCache: new MutationCache({ onError: redirectToLoginOnUnauthorized }),
  defaultOptions: {
    queries: { retry: (count, error) => !isUnauthorized(error) && count < 1, refetchOnWindowFocus: false },
  },
});

const router = createBrowserRouter(
  [
  { path: "/login", element: <LoginPage /> },
  {
    element: <AdminLayout />,
    children: [
      { path: "/", element: <LinksPage /> },
      { path: "/inquiries", element: <InquiryListPage /> },
      { path: "/inquiries/:inquiryId", element: <InquiryDetailPage /> },
      { path: "/icons", element: <IconListPage /> },
      { path: "/room-items", element: <RoomItemListPage /> },
      { path: "/products", element: <ProductListPage /> },
      { path: "/promotions", element: <PromotionListPage /> },
      { path: "/banners", element: <ShopBannerListPage /> },
    ],
  },
  ],
  { basename: BASE_PATH },
);

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  </StrictMode>,
);
