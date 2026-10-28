import { useState, type FormEvent } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useSearchParams } from "react-router-dom";
import {
  createProduct,
  getProducts,
  getRoomItems,
  PRODUCT_ITEMS_MAX,
  PRODUCT_NAME_MAX,
  ROOM_ITEM_CATEGORY_LABELS,
  updateProduct,
  type AdminProduct,
  type AdminRoomItem,
  type RoomItemCategory,
} from "../api/admin";
import { convertFromKstInputValue, convertToKstInputValue, formatDateTime } from "../format";

const CATEGORIES = Object.keys(ROOM_ITEM_CATEGORY_LABELS) as RoomItemCategory[];
// 방 배경은 보유 여부로 담기·구매를 막으므로 단품으로만 판다(서버도 거절한다).
const SET_CATEGORIES = CATEGORIES.filter((category) => category !== "BACKGROUND");

type SaleStatus = "ON_SALE" | "SCHEDULED" | "ENDED";

const SALE_STATUS_LABELS: Record<SaleStatus, string> = { ON_SALE: "판매 중", SCHEDULED: "예약", ENDED: "판매 종료" };
const SALE_STATUSES = Object.keys(SALE_STATUS_LABELS) as SaleStatus[];

// 판매 기간은 [saleStartsAt, saleEndsAt)이다.
const getSaleStatus = (product: AdminProduct, now: number): SaleStatus => {
  if (new Date(product.saleStartsAt).getTime() > now) return "SCHEDULED";
  if (product.saleEndsAt !== null && new Date(product.saleEndsAt).getTime() <= now) return "ENDED";
  return "ON_SALE";
};

/** 빈칸은 null, 0 이상의 정수는 숫자, 그 외에는 undefined. */
const convertCount = (value: string): number | null | undefined => {
  const trimmed = value.trim();
  if (trimmed === "") return null;
  return /^\d+$/.test(trimmed) ? Number(trimmed) : undefined;
};

const formatCoin = (coin: number) => `${coin.toLocaleString()} 코인`;

const createNowInputValue = () => convertToKstInputValue(new Date().toISOString());

/**
 * 상점 상품 관리. 마이룸 아이템을 골라 단품(1개)이나 세트(여러 개)로 만든다.
 * 정가·분류·구성품은 만든 뒤 바꿀 수 없고, 할인은 프로모션에서 정한다.
 */
export function ProductListPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const category = (searchParams.get("category") as RoomItemCategory | null) ?? undefined;
  const [saleStatus, setSaleStatus] = useState<SaleStatus | undefined>(undefined);

  const products = useQuery({
    queryKey: ["products", category ?? "all"],
    queryFn: () => getProducts(category),
  });
  const now = Date.now();
  const rows = (products.data?.products ?? []).filter(
    (product) => saleStatus === undefined || getSaleStatus(product, now) === saleStatus,
  );

  const updateCategory = (value: RoomItemCategory | undefined) => {
    const next = new URLSearchParams(searchParams);
    if (value) next.set("category", value);
    else next.delete("category");
    setSearchParams(next, { replace: true });
  };

  return (
    <section className="stack">
      <div className="toolbar">
        <h2>상품</h2>
        <div className="segmented">
          <button type="button" className={category === undefined ? "active" : ""} onClick={() => updateCategory(undefined)}>
            전체
          </button>
          {CATEGORIES.map((value) => (
            <button
              key={value}
              type="button"
              className={value === category ? "active" : ""}
              onClick={() => updateCategory(value)}
            >
              {ROOM_ITEM_CATEGORY_LABELS[value]}
            </button>
          ))}
        </div>
        <div className="segmented">
          <button type="button" className={saleStatus === undefined ? "active" : ""} onClick={() => setSaleStatus(undefined)}>
            전체
          </button>
          {SALE_STATUSES.map((value) => (
            <button
              key={value}
              type="button"
              className={value === saleStatus ? "active" : ""}
              onClick={() => setSaleStatus(value)}
            >
              {SALE_STATUS_LABELS[value]}
            </button>
          ))}
        </div>
      </div>

      <p className="notice">
        정가·분류·구성품은 만든 뒤 바꿀 수 없어요. 정가가 달라지면 판매를 중지하고 새 상품을 만들어 주세요. 할인은 프로모션에서
        정해요.
      </p>

      <CreateProductForm />

      {products.isPending && <p className="muted">불러오는 중…</p>}
      {products.error && <p className="error">{products.error.message}</p>}
      {products.isSuccess && rows.length === 0 && <p className="muted">상품이 없습니다.</p>}

      {rows.length > 0 && (
        <div className="grid">
          {rows.map((product) => (
            <ProductCard key={product.productId} product={product} saleStatus={getSaleStatus(product, now)} />
          ))}
        </div>
      )}
    </section>
  );
}

function CreateProductForm() {
  const queryClient = useQueryClient();
  const roomItems = useQuery({ queryKey: ["roomItems", "all"], queryFn: () => getRoomItems() });
  const roomItemsById = new Map((roomItems.data?.roomItems ?? []).map((roomItem) => [roomItem.itemId, roomItem]));

  const [pickerCategory, setPickerCategory] = useState<RoomItemCategory | undefined>(undefined);
  const [selectedItemIds, setSelectedItemIds] = useState<number[]>([]);
  const [listPriceTexts, setListPriceTexts] = useState<Record<number, string>>({});
  const [representativeItemId, setRepresentativeItemId] = useState<number | null>(null);
  const [name, setName] = useState("");
  const [setCategory, setSetCategory] = useState<RoomItemCategory>("ETC");
  const [priceText, setPriceText] = useState("");
  const [isLimited, setIsLimited] = useState(false);
  const [saleStartsAt, setSaleStartsAt] = useState(createNowInputValue);
  const [saleEndsAt, setSaleEndsAt] = useState("");
  const [purchaseLimitText, setPurchaseLimitText] = useState("");
  const [createdMessage, setCreatedMessage] = useState<string | null>(null);

  const selectedItems = selectedItemIds
    .map((itemId) => roomItemsById.get(itemId))
    .filter((roomItem): roomItem is AdminRoomItem => roomItem !== undefined);
  const isSet = selectedItems.length > 1;
  const category = isSet ? setCategory : selectedItems[0]?.category;
  const representative = isSet && representativeItemId !== null && selectedItemIds.includes(representativeItemId)
    ? representativeItemId
    : selectedItemIds[0];

  const price = convertCount(priceText);
  const purchaseLimit = convertCount(purchaseLimitText);
  const listPrices = selectedItems.map((roomItem) => convertCount(listPriceTexts[roomItem.itemId] ?? ""));
  const listPriceSum = listPrices.reduce<number>((sum, listPrice) => sum + (listPrice ?? 0), 0);
  const saleStartsAtValue = convertFromKstInputValue(saleStartsAt);
  const saleEndsAtValue = convertFromKstInputValue(saleEndsAt);

  // 서버와 같은 규칙을 먼저 안내한다. 기준은 서버다.
  const problems: string[] = [];
  if (selectedItems.length === 0) problems.push("구성품으로 넣을 마이룸 아이템을 골라 주세요.");
  if (selectedItems.length > PRODUCT_ITEMS_MAX) problems.push(`구성품은 ${PRODUCT_ITEMS_MAX}개까지 넣을 수 있어요.`);
  if (name.trim().length === 0) problems.push("상품 이름을 적어 주세요.");
  if (price === null || price === undefined) problems.push("정가를 0 이상의 정수로 적어 주세요.");
  if (isSet && listPrices.some((listPrice) => listPrice === null || listPrice === undefined)) {
    problems.push("구성품 정가를 모두 0 이상의 정수로 적어 주세요.");
  }
  if (isSet && selectedItems.some((roomItem) => roomItem.category === "BACKGROUND")) {
    problems.push("방 배경은 단품으로만 팔 수 있어요.");
  }
  if (isSet && typeof price === "number" && price > listPriceSum) {
    problems.push("세트 정가는 구성품 정가 합보다 클 수 없어요.");
  }
  if (saleStartsAtValue === null) problems.push("판매 시작 시각을 정해 주세요.");
  if (saleStartsAtValue !== null && saleEndsAtValue !== null && saleEndsAtValue <= saleStartsAtValue) {
    problems.push("판매 종료 시각은 시작 시각보다 뒤여야 해요.");
  }
  if (purchaseLimit === undefined || purchaseLimit === 0) problems.push("1인 구매 한도는 1 이상의 정수로 적거나 비워 주세요.");

  const create = useMutation({
    mutationFn: () =>
      createProduct({
        name: name.trim(),
        category: category!,
        representativeItemId: representative,
        price: price!,
        isLimited,
        saleStartsAt: saleStartsAtValue!,
        saleEndsAt: saleEndsAtValue,
        purchaseLimit: purchaseLimit ?? null,
        // 단품은 구성품 정가가 상품 정가와 같다.
        items: selectedItems.map((roomItem, index) => ({
          itemId: roomItem.itemId,
          listPrice: isSet ? listPrices[index]! : price!,
        })),
      }),
    onSuccess: (created) => {
      setCreatedMessage(`#${created.productId} "${created.name}" 상품을 만들었어요.`);
      setSelectedItemIds([]);
      setListPriceTexts({});
      setRepresentativeItemId(null);
      setName("");
      setPriceText("");
      setIsLimited(false);
      setSaleStartsAt(createNowInputValue());
      setSaleEndsAt("");
      setPurchaseLimitText("");
      queryClient.invalidateQueries({ queryKey: ["products"] });
    },
  });

  const clearMessage = () => {
    setCreatedMessage(null);
    create.reset();
  };

  // 처음 고른 아이템의 이름을 상품 이름으로, 아이템에 적힌 가격을 구성품 정가로 미리 채운다.
  const toggleItem = (roomItem: AdminRoomItem) => {
    clearMessage();
    if (selectedItemIds.includes(roomItem.itemId)) {
      setSelectedItemIds(selectedItemIds.filter((itemId) => itemId !== roomItem.itemId));
      return;
    }
    if (selectedItemIds.length === 0 && name.trim() === "") setName(roomItem.name);
    if (selectedItemIds.length === 0 && priceText === "" && roomItem.price !== null) setPriceText(String(roomItem.price));
    setListPriceTexts({ ...listPriceTexts, [roomItem.itemId]: roomItem.price === null ? "" : String(roomItem.price) });
    setSelectedItemIds([...selectedItemIds, roomItem.itemId]);
  };

  const handleSubmit = (e: FormEvent) => {
    e.preventDefault();
    if (problems.length === 0 && !create.isPending) create.mutate();
  };

  const pickerItems = (roomItems.data?.roomItems ?? []).filter(
    (roomItem) => pickerCategory === undefined || roomItem.category === pickerCategory,
  );

  return (
    <form className="card form" onSubmit={handleSubmit}>
      <h3>상품 만들기</h3>

      <div className="toolbar">
        <span className="muted">
          구성품 고르기 ({selectedItems.length}개 선택 · {selectedItems.length === 0 ? "-" : isSet ? "세트" : "단품"})
        </span>
        <div className="segmented">
          <button
            type="button"
            className={pickerCategory === undefined ? "active" : ""}
            onClick={() => setPickerCategory(undefined)}
          >
            전체
          </button>
          {CATEGORIES.map((value) => (
            <button
              key={value}
              type="button"
              className={value === pickerCategory ? "active" : ""}
              onClick={() => setPickerCategory(value)}
            >
              {ROOM_ITEM_CATEGORY_LABELS[value]}
            </button>
          ))}
        </div>
      </div>

      {roomItems.isPending && <p className="muted">마이룸 아이템을 불러오는 중…</p>}
      {roomItems.error && <p className="error">{roomItems.error.message}</p>}
      <div className="grid picker">
        {pickerItems.map((roomItem) => (
          <button
            key={roomItem.itemId}
            type="button"
            className={`thumb-card pick${selectedItemIds.includes(roomItem.itemId) ? " selected" : ""}`}
            onClick={() => toggleItem(roomItem)}
          >
            <img src={roomItem.imageUrl} alt={roomItem.name} className="thumb" />
            <span className="meta">
              <span>#{roomItem.itemId}</span>
              <span className="badge">{ROOM_ITEM_CATEGORY_LABELS[roomItem.category]}</span>
            </span>
            <span className="name">{roomItem.name}</span>
          </button>
        ))}
      </div>

      {isSet && (
        <table className="table">
          <thead>
            <tr>
              <th>대표</th>
              <th>구성품</th>
              <th>구성품 정가(코인)</th>
            </tr>
          </thead>
          <tbody>
            {selectedItems.map((roomItem) => (
              <tr key={roomItem.itemId}>
                <td>
                  <input
                    type="radio"
                    name="representativeItemId"
                    aria-label={`${roomItem.name}을 대표 구성품으로`}
                    checked={representative === roomItem.itemId}
                    onChange={() => setRepresentativeItemId(roomItem.itemId)}
                  />
                </td>
                <td className="title">
                  #{roomItem.itemId} {roomItem.name}
                </td>
                <td>
                  <input
                    type="text"
                    inputMode="numeric"
                    aria-label={`${roomItem.name} 정가`}
                    value={listPriceTexts[roomItem.itemId] ?? ""}
                    onChange={(e) => {
                      setListPriceTexts({ ...listPriceTexts, [roomItem.itemId]: e.target.value });
                      clearMessage();
                    }}
                  />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <label>
        상품 이름
        <input
          type="text"
          value={name}
          onChange={(e) => {
            setName(e.target.value);
            clearMessage();
          }}
          maxLength={PRODUCT_NAME_MAX}
        />
        <span className="counter">
          {name.length}/{PRODUCT_NAME_MAX}
        </span>
      </label>

      <div className="form-row">
        <label>
          {isSet ? "세트 정가(코인)" : "정가(코인)"}
          <input
            type="text"
            inputMode="numeric"
            value={priceText}
            onChange={(e) => {
              setPriceText(e.target.value);
              clearMessage();
            }}
          />
        </label>
        <label>
          분류
          {isSet ? (
            <select
              value={setCategory}
              onChange={(e) => {
                setSetCategory(e.target.value as RoomItemCategory);
                clearMessage();
              }}
            >
              {SET_CATEGORIES.map((value) => (
                <option key={value} value={value}>
                  {ROOM_ITEM_CATEGORY_LABELS[value]}
                </option>
              ))}
            </select>
          ) : (
            <input type="text" value={category ? ROOM_ITEM_CATEGORY_LABELS[category] : "-"} disabled />
          )}
        </label>
        <label>
          1인 구매 한도
          <input
            type="text"
            inputMode="numeric"
            placeholder="비우면 무제한"
            value={purchaseLimitText}
            onChange={(e) => {
              setPurchaseLimitText(e.target.value);
              clearMessage();
            }}
          />
        </label>
      </div>
      {isSet && typeof price === "number" && listPriceSum > 0 && (
        <p className="muted">
          구성품 정가 합 {formatCoin(listPriceSum)} · 세트로 사면 {Math.floor(((listPriceSum - price) * 100) / listPriceSum)}%
          저렴해요
        </p>
      )}

      <div className="form-row">
        <label>
          판매 시작(한국 시간)
          <input
            type="datetime-local"
            value={saleStartsAt}
            onChange={(e) => {
              setSaleStartsAt(e.target.value);
              clearMessage();
            }}
          />
        </label>
        <label>
          판매 종료(비우면 종료일 없음)
          <input
            type="datetime-local"
            value={saleEndsAt}
            onChange={(e) => {
              setSaleEndsAt(e.target.value);
              clearMessage();
            }}
          />
        </label>
      </div>

      <label className="checkbox">
        <input
          type="checkbox"
          checked={isLimited}
          onChange={(e) => {
            setIsLimited(e.target.checked);
            clearMessage();
          }}
        />
        기간 한정(한정 태그와 기간 한정 칩에 나와요)
      </label>

      {problems.length > 0 && selectedItems.length > 0 && (
        <ul className="error">
          {problems.map((problem) => (
            <li key={problem}>{problem}</li>
          ))}
        </ul>
      )}
      {create.error && <p className="error">{create.error.message}</p>}
      {createdMessage && <p className="success">{createdMessage}</p>}

      <button type="submit" className="primary" disabled={problems.length > 0 || create.isPending}>
        {create.isPending ? "만드는 중…" : "만들기"}
      </button>
    </form>
  );
}

function ProductCard({ product, saleStatus }: { product: AdminProduct; saleStatus: SaleStatus }) {
  const queryClient = useQueryClient();
  const [isEditing, setIsEditing] = useState(false);
  const [name, setName] = useState(product.name);
  const [isLimited, setIsLimited] = useState(product.isLimited);
  const [saleStartsAt, setSaleStartsAt] = useState(convertToKstInputValue(product.saleStartsAt));
  const [saleEndsAt, setSaleEndsAt] = useState(product.saleEndsAt ? convertToKstInputValue(product.saleEndsAt) : "");
  const [purchaseLimitText, setPurchaseLimitText] = useState(product.purchaseLimit?.toString() ?? "");
  const [message, setMessage] = useState<string | null>(null);

  const purchaseLimit = convertCount(purchaseLimitText);
  const saleStartsAtValue = convertFromKstInputValue(saleStartsAt);
  const saleEndsAtValue = convertFromKstInputValue(saleEndsAt);

  const save = useMutation({
    mutationFn: () =>
      updateProduct(product.productId, {
        name: name.trim(),
        isLimited,
        saleStartsAt: saleStartsAtValue!,
        saleEndsAt: saleEndsAtValue,
        purchaseLimit: purchaseLimit ?? null,
      }),
    onSuccess: () => {
      setIsEditing(false);
      setMessage("상품을 바꿨어요.");
      queryClient.invalidateQueries({ queryKey: ["products"] });
    },
  });

  // 판매 중인 상품만 지금 시각으로 끝낸다. 예약 상품은 판매 기간을 고친다(종료가 시작보다 앞일 수 없다).
  const stopSale = useMutation({
    mutationFn: () =>
      updateProduct(product.productId, {
        name: product.name,
        isLimited: product.isLimited,
        saleStartsAt: product.saleStartsAt,
        saleEndsAt: new Date().toISOString(),
        purchaseLimit: product.purchaseLimit,
      }),
    onSuccess: () => {
      setMessage("판매를 중지했어요. 장바구니에 담긴 상품은 '판매가 끝났어요'로 보여요.");
      queryClient.invalidateQueries({ queryKey: ["products"] });
    },
  });

  const openEdit = () => {
    setIsEditing(true);
    setName(product.name);
    setIsLimited(product.isLimited);
    setSaleStartsAt(convertToKstInputValue(product.saleStartsAt));
    setSaleEndsAt(product.saleEndsAt ? convertToKstInputValue(product.saleEndsAt) : "");
    setPurchaseLimitText(product.purchaseLimit?.toString() ?? "");
    setMessage(null);
    save.reset();
  };

  const canSave =
    name.trim().length > 0 &&
    name.length <= PRODUCT_NAME_MAX &&
    saleStartsAtValue !== null &&
    (saleEndsAtValue === null || saleEndsAtValue > saleStartsAtValue) &&
    purchaseLimit !== undefined &&
    purchaseLimit !== 0 &&
    !save.isPending;
  const error = save.error ?? stopSale.error;

  return (
    <article className="thumb-card">
      <img src={product.imageUrl} alt={product.name} className="thumb" />
      <div className="meta">
        <span>#{product.productId}</span>
        <span className="badge">{ROOM_ITEM_CATEGORY_LABELS[product.category]}</span>
        <span className={`badge${saleStatus === "ON_SALE" ? " done" : saleStatus === "SCHEDULED" ? " waiting" : ""}`}>
          {SALE_STATUS_LABELS[saleStatus]}
        </span>
        {product.items.length > 1 && <span className="badge">세트</span>}
        {product.isLimited && <span className="badge">한정</span>}
      </div>
      {/* 운영자가 입력한 값도 텍스트로만 렌더링한다. */}
      <strong className="name">{product.name}</strong>
      <span>{formatCoin(product.price)}</span>
      {product.items.length > 1 && (
        <ul className="muted">
          {product.items.map((item) => (
            <li key={item.itemId}>
              {item.name} · {formatCoin(item.listPrice)}
            </li>
          ))}
        </ul>
      )}
      <span className="muted">
        {formatDateTime(product.saleStartsAt)} ~ {product.saleEndsAt ? formatDateTime(product.saleEndsAt) : ""}
      </span>
      <span className="muted">
        1인 한도 {product.purchaseLimit ?? "무제한"} · 판매량 {product.salesCount}
      </span>

      {!isEditing && (
        <div className="actions">
          <button type="button" className="secondary" onClick={openEdit}>
            수정
          </button>
          {saleStatus === "ON_SALE" && (
            <button
              type="button"
              className="secondary"
              disabled={stopSale.isPending}
              onClick={() => {
                if (window.confirm(`"${product.name}" 판매를 지금 중지할까요?`)) stopSale.mutate();
              }}
            >
              {stopSale.isPending ? "중지 중…" : "판매 중지"}
            </button>
          )}
        </div>
      )}

      {isEditing && (
        <form
          className="form"
          onSubmit={(e) => {
            e.preventDefault();
            if (canSave) save.mutate();
          }}
        >
          <input
            type="text"
            aria-label="상품 이름"
            value={name}
            onChange={(e) => setName(e.target.value)}
            maxLength={PRODUCT_NAME_MAX}
          />
          <label>
            판매 시작(한국 시간)
            <input type="datetime-local" value={saleStartsAt} onChange={(e) => setSaleStartsAt(e.target.value)} />
          </label>
          <label>
            판매 종료(비우면 종료일 없음)
            <input type="datetime-local" value={saleEndsAt} onChange={(e) => setSaleEndsAt(e.target.value)} />
          </label>
          <input
            type="text"
            aria-label="1인 구매 한도"
            inputMode="numeric"
            placeholder="1인 구매 한도(비우면 무제한)"
            value={purchaseLimitText}
            onChange={(e) => setPurchaseLimitText(e.target.value)}
          />
          <label className="checkbox">
            <input type="checkbox" checked={isLimited} onChange={(e) => setIsLimited(e.target.checked)} />
            기간 한정
          </label>
          <div className="actions">
            <button type="submit" className="secondary" disabled={!canSave}>
              {save.isPending ? "저장 중…" : "저장"}
            </button>
            <button type="button" className="link" onClick={() => setIsEditing(false)}>
              취소
            </button>
          </div>
        </form>
      )}

      {error && <p className="error">{error.message}</p>}
      {message && <p className="success">{message}</p>}
    </article>
  );
}
