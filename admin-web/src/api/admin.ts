import { request } from "./client";

const PREFIX = "/admin/api";

export type AdminResponse = { adminId: number; email: string; name: string };

export type InquiryCategory = "BUG_REPORT" | "QUESTION" | "SUGGESTION";

export const INQUIRY_CATEGORY_LABELS: Record<InquiryCategory, string> = {
  BUG_REPORT: "버그 신고",
  QUESTION: "질문",
  SUGGESTION: "제안",
};

export type AdminInquirySummary = {
  inquiryId: number;
  userId: number;
  category: InquiryCategory;
  questionTitle: string;
  createdAt: string;
  isAnswered: boolean;
  answeredAt: string | null;
};

export type AdminInquiries = {
  inquiries: AdminInquirySummary[];
  nextCursor: number | null;
  hasNext: boolean;
};

export type AdminInquiry = {
  inquiryId: number;
  userId: number;
  category: InquiryCategory;
  questionTitle: string;
  questionContent: string;
  imageUrl: string | null;
  appVersion: string | null;
  createdAt: string;
  isAnswered: boolean;
  answerTitle: string | null;
  answerContent: string | null;
  answeredAt: string | null;
  answerReadAt: string | null;
};

export type InquiryFilter = {
  category?: InquiryCategory;
  isAnswered?: boolean;
};

/** 음식 아이콘. imageUrl은 앱과 같은 규칙(CDN 주소 + /icons/{iconId}.png)의 미리보기 주소다. */
export type AdminIcon = { iconId: number; name: string; imageUrl: string };

export type AdminIcons = { icons: AdminIcon[] };

/** 서버 검사와 같은 값. 화면은 먼저 안내만 하고, 기준은 서버다. */
export const ICON_NAME_MAX = 100;
export const ICON_IMAGE_MAX_BYTES = 512 * 1024;

export type RoomItemCategory = "BACKGROUND" | "BED" | "CAT_WHEEL" | "RUG" | "ETC";

export const ROOM_ITEM_CATEGORY_LABELS: Record<RoomItemCategory, string> = {
  BACKGROUND: "배경",
  BED: "침대",
  CAT_WHEEL: "캣휠",
  RUG: "러그",
  ETC: "소품",
};

/**
 * 마이룸 아이템(상품). price가 null이면 상점에서 팔지 않는 아이템(보상 전용)이다.
 * imageUrl은 앱과 같은 규칙(CDN 주소 + /items/{itemId}.png)의 미리보기 주소다.
 */
export type AdminRoomItem = {
  itemId: number;
  name: string;
  price: number | null;
  category: RoomItemCategory;
  imageUrl: string;
  createdAt: string;
};

export type AdminRoomItems = { roomItems: AdminRoomItem[] };

export type CreateRoomItemInput = { name: string; price: number | null; category: RoomItemCategory; image: File };

/** 서버 검사와 같은 값. 화면은 먼저 안내만 하고, 기준은 서버다. */
export const ROOM_ITEM_NAME_MAX = 100;
export const ROOM_ITEM_IMAGE_MAX_BYTES = 5 * 1024 * 1024;

export type GoogleClientResponse = { clientId: string };

/** 어드민 웹용 Google 클라이언트 ID. 공개 값이라 로그인 전에 받는다(빌드 시점에 박지 않고 백엔드 설정 하나만 쓴다). */
export const getGoogleClient = () => request<GoogleClientResponse>(`${PREFIX}/auth/google-client`);

export const login = (idToken: string) =>
  request<AdminResponse>(`${PREFIX}/auth/login`, { method: "POST", body: JSON.stringify({ idToken }) });

export const logout = () => request<void>(`${PREFIX}/auth/logout`, { method: "POST" });

export const getMe = () => request<AdminResponse>(`${PREFIX}/me`);

export function getInquiries(filter: InquiryFilter, cursor: number | null, size = 20) {
  const params = new URLSearchParams({ size: String(size) });
  if (filter.category) params.set("category", filter.category);
  if (filter.isAnswered !== undefined) params.set("isAnswered", String(filter.isAnswered));
  if (cursor !== null) params.set("cursor", String(cursor));

  return request<AdminInquiries>(`${PREFIX}/inquiries?${params}`);
}

export const getInquiry = (inquiryId: number) => request<AdminInquiry>(`${PREFIX}/inquiries/${inquiryId}`);

export const updateAnswer = (inquiryId: number, answerTitle: string, answerContent: string) =>
  request<AdminInquiry>(`${PREFIX}/inquiries/${inquiryId}/answer`, {
    method: "PUT",
    body: JSON.stringify({ answerTitle, answerContent }),
  });

export const getIcons = () => request<AdminIcons>(`${PREFIX}/icons`);

export function createIcon(name: string, image: File) {
  const body = new FormData();
  body.append("name", name);
  body.append("image", image);

  return request<AdminIcon>(`${PREFIX}/icons`, { method: "POST", body });
}

export const updateIconName = (iconId: number, name: string) =>
  request<AdminIcon>(`${PREFIX}/icons/${iconId}`, { method: "PATCH", body: JSON.stringify({ name }) });

export function updateIconImage(iconId: number, image: File) {
  const body = new FormData();
  body.append("image", image);

  return request<AdminIcon>(`${PREFIX}/icons/${iconId}/image`, { method: "PUT", body });
}

export function getRoomItems(category?: RoomItemCategory) {
  const params = new URLSearchParams();
  if (category) params.set("category", category);

  return request<AdminRoomItems>(`${PREFIX}/room-items?${params}`);
}

export function createRoomItem({ name, price, category, image }: CreateRoomItemInput) {
  const body = new FormData();
  body.append("name", name);
  // 가격을 보내지 않으면 서버가 비매품(null)으로 만든다.
  if (price !== null) body.append("price", String(price));
  body.append("category", category);
  body.append("image", image);

  return request<AdminRoomItem>(`${PREFIX}/room-items`, { method: "POST", body });
}

/** 이름과 가격을 보낸 값으로 통째로 바꾼다(PUT). 둘 다 보내고, price가 null이면 비매품으로 바뀐다. */
export const updateRoomItem = (itemId: number, name: string, price: number | null) =>
  request<AdminRoomItem>(`${PREFIX}/room-items/${itemId}`, { method: "PUT", body: JSON.stringify({ name, price }) });

export function updateRoomItemImage(itemId: number, image: File) {
  const body = new FormData();
  body.append("image", image);

  return request<AdminRoomItem>(`${PREFIX}/room-items/${itemId}/image`, { method: "PUT", body });
}

/** 상품의 구성품. imageUrl은 앱과 같은 규칙(CDN 주소 + /items/{itemId}.png)의 미리보기 주소다. */
export type AdminProductItem = { itemId: number; name: string; listPrice: number; imageUrl: string };

/**
 * 상품(판매 단위). 구성품이 하나면 단품, 여럿이면 세트다. price는 1개 정가이고, 할인가는 프로모션이 정한다.
 * 정가·분류·구성품·대표 구성품은 만든 뒤 바꿀 수 없다.
 */
export type AdminProduct = {
  productId: number;
  name: string;
  category: RoomItemCategory;
  representativeItemId: number;
  imageUrl: string;
  price: number;
  isLimited: boolean;
  saleStartsAt: string;
  saleEndsAt: string | null;
  purchaseLimit: number | null;
  salesCount: number;
  items: AdminProductItem[];
  createdAt: string;
};

export type AdminProducts = { products: AdminProduct[] };

export type CreateProductInput = {
  name: string;
  category: RoomItemCategory;
  representativeItemId: number;
  price: number;
  isLimited: boolean;
  saleStartsAt: string;
  saleEndsAt: string | null;
  purchaseLimit: number | null;
  items: { itemId: number; listPrice: number }[];
};

export type UpdateProductInput = {
  name: string;
  isLimited: boolean;
  saleStartsAt: string;
  saleEndsAt: string | null;
  purchaseLimit: number | null;
};

/** 서버 검사와 같은 값. 화면은 먼저 안내만 하고, 기준은 서버다. */
export const PRODUCT_NAME_MAX = 100;
export const PRODUCT_ITEMS_MAX = 20;

export function getProducts(category?: RoomItemCategory) {
  const params = new URLSearchParams();
  if (category) params.set("category", category);

  return request<AdminProducts>(`${PREFIX}/products?${params}`);
}

export const createProduct = (input: CreateProductInput) =>
  request<AdminProduct>(`${PREFIX}/products`, { method: "POST", body: JSON.stringify(input) });

/** 이름·기간 한정·판매 기간·1인 구매 한도를 보낸 값으로 통째로 바꾼다(PUT). 판매 중지는 saleEndsAt에 지금 시각을 보낸다. */
export const updateProduct = (productId: number, input: UpdateProductInput) =>
  request<AdminProduct>(`${PREFIX}/products/${productId}`, { method: "PUT", body: JSON.stringify(input) });
