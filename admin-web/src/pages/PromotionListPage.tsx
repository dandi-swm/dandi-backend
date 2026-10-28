import { useState, type FormEvent } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  createPromotion,
  getProducts,
  getPromotions,
  PROMOTION_CATEGORY_LABELS,
  PROMOTION_NAME_MAX,
  PROMOTION_PRODUCTS_MAX,
  updatePromotion,
  updatePromotionProducts,
  type AdminProduct,
  type AdminPromotion,
  type PromotionCategory,
} from "../api/admin";
import { convertFromKstInputValue, convertToKstInputValue, formatDateTime } from "../format";

const CATEGORIES = Object.keys(PROMOTION_CATEGORY_LABELS) as PromotionCategory[];

type PromotionStatus = "IN_PROGRESS" | "SCHEDULED" | "ENDED";

const PROMOTION_STATUS_LABELS: Record<PromotionStatus, string> = {
  IN_PROGRESS: "진행 중",
  SCHEDULED: "예정",
  ENDED: "종료",
};
const PROMOTION_STATUSES = Object.keys(PROMOTION_STATUS_LABELS) as PromotionStatus[];

// 진열 상품의 판매 상태. 프로모션 기간과 같은 규칙으로 가른다.
const SALE_STATUS_LABELS: Record<PromotionStatus, string> = { IN_PROGRESS: "판매 중", SCHEDULED: "예약", ENDED: "판매 종료" };

// 기간은 [startsAt, endsAt)이다. 상품의 판매 기간도 같은 규칙이다.
const getStatus = (startsAt: string, endsAt: string | null, now: number): PromotionStatus => {
  if (new Date(startsAt).getTime() > now) return "SCHEDULED";
  if (endsAt !== null && new Date(endsAt).getTime() <= now) return "ENDED";
  return "IN_PROGRESS";
};

/** 빈칸은 할인 없음(null), 1~99 정수는 숫자, 그 외에는 undefined. */
const convertDiscountRate = (value: string): number | null | undefined => {
  const trimmed = value.trim();
  if (trimmed === "") return null;
  if (!/^\d+$/.test(trimmed)) return undefined;
  const rate = Number(trimmed);
  return rate >= 1 && rate <= 99 ? rate : undefined;
};

const formatDiscountRate = (discountRate: number | null) => (discountRate === null ? "할인 없음" : `${discountRate}% 할인`);

const createNowInputValue = () => convertToKstInputValue(new Date().toISOString());

/**
 * 상점 프로모션 관리. 기간과 대상을 정해 상품을 묶고, 할인율을 정하면 그 상품의 결제가가 내려간다.
 * 사용자에게는 진행 중이고 대상인 프로모션 중 할인율이 가장 큰 것 하나만 적용된다.
 */
export function PromotionListPage() {
  const [status, setStatus] = useState<PromotionStatus | undefined>(undefined);
  const promotions = useQuery({ queryKey: ["promotions"], queryFn: getPromotions });
  const products = useQuery({ queryKey: ["products", "all"], queryFn: () => getProducts() });

  const now = Date.now();
  const rows = (promotions.data?.promotions ?? []).filter(
    (promotion) => status === undefined || getStatus(promotion.startsAt, promotion.endsAt, now) === status,
  );

  return (
    <section className="stack">
      <div className="toolbar">
        <h2>프로모션</h2>
        <div className="segmented">
          <button type="button" className={status === undefined ? "active" : ""} onClick={() => setStatus(undefined)}>
            전체
          </button>
          {PROMOTION_STATUSES.map((value) => (
            <button key={value} type="button" className={value === status ? "active" : ""} onClick={() => setStatus(value)}>
              {PROMOTION_STATUS_LABELS[value]}
            </button>
          ))}
        </div>
      </div>

      <p className="notice">
        사용자에게는 진행 중이고 대상인 프로모션 중 할인율이 가장 큰 것 하나만 적용돼요. 할인율을 바꾸면 장바구니에 담긴 상품의
        결제가도 바로 바뀌어요. 대상은 만든 뒤 바꿀 수 없어요.
      </p>

      <CreatePromotionForm />

      {promotions.isPending && <p className="muted">불러오는 중…</p>}
      {promotions.error && <p className="error">{promotions.error.message}</p>}
      {promotions.isSuccess && rows.length === 0 && <p className="muted">프로모션이 없습니다.</p>}

      {rows.map((promotion) => (
        <PromotionCard
          key={promotion.promotionId}
          promotion={promotion}
          status={getStatus(promotion.startsAt, promotion.endsAt, now)}
          products={products.data?.products ?? []}
        />
      ))}
    </section>
  );
}

function CreatePromotionForm() {
  const queryClient = useQueryClient();
  const [name, setName] = useState("");
  const [category, setCategory] = useState<PromotionCategory>("GENERAL");
  const [discountRateText, setDiscountRateText] = useState("");
  const [startsAt, setStartsAt] = useState(createNowInputValue);
  const [endsAt, setEndsAt] = useState("");
  const [createdMessage, setCreatedMessage] = useState<string | null>(null);

  const discountRate = convertDiscountRate(discountRateText);
  const startsAtValue = convertFromKstInputValue(startsAt);
  const endsAtValue = convertFromKstInputValue(endsAt);
  const isPeriodValid = startsAtValue !== null && (endsAtValue === null || endsAtValue > startsAtValue);

  const create = useMutation({
    mutationFn: () =>
      createPromotion({
        name: name.trim(),
        category,
        discountRate: discountRate ?? null,
        startsAt: startsAtValue!,
        endsAt: endsAtValue,
        productIds: [],
      }),
    onSuccess: (created) => {
      setCreatedMessage(`#${created.promotionId} "${created.name}" 프로모션을 만들었어요. 아래에서 상품을 진열해 주세요.`);
      setName("");
      setDiscountRateText("");
      setStartsAt(createNowInputValue());
      setEndsAt("");
      queryClient.invalidateQueries({ queryKey: ["promotions"] });
    },
  });

  const canSubmit =
    name.trim().length > 0 && name.length <= PROMOTION_NAME_MAX && discountRate !== undefined && isPeriodValid && !create.isPending;

  const clearMessage = () => {
    setCreatedMessage(null);
    create.reset();
  };

  const handleSubmit = (e: FormEvent) => {
    e.preventDefault();
    if (canSubmit) create.mutate();
  };

  return (
    <form className="card form" onSubmit={handleSubmit}>
      <h3>프로모션 만들기</h3>

      <label>
        이름
        <input
          type="text"
          value={name}
          onChange={(e) => {
            setName(e.target.value);
            clearMessage();
          }}
          maxLength={PROMOTION_NAME_MAX}
        />
        <span className="counter">
          {name.length}/{PROMOTION_NAME_MAX}
        </span>
      </label>

      <div className="form-row">
        <label>
          대상
          <select
            value={category}
            onChange={(e) => {
              setCategory(e.target.value as PromotionCategory);
              clearMessage();
            }}
          >
            {CATEGORIES.map((value) => (
              <option key={value} value={value}>
                {PROMOTION_CATEGORY_LABELS[value]}
              </option>
            ))}
          </select>
        </label>
        <label>
          할인율(%)
          <input
            type="text"
            inputMode="numeric"
            placeholder="비우면 할인 없음"
            value={discountRateText}
            onChange={(e) => {
              setDiscountRateText(e.target.value);
              clearMessage();
            }}
          />
        </label>
      </div>
      {discountRate === undefined && <p className="error">할인율은 1~99 정수로 적거나, 할인이 없으면 비워 주세요.</p>}

      <div className="form-row">
        <label>
          시작(한국 시간)
          <input
            type="datetime-local"
            value={startsAt}
            onChange={(e) => {
              setStartsAt(e.target.value);
              clearMessage();
            }}
          />
        </label>
        <label>
          종료(비우면 종료일 없음)
          <input
            type="datetime-local"
            value={endsAt}
            onChange={(e) => {
              setEndsAt(e.target.value);
              clearMessage();
            }}
          />
        </label>
      </div>
      {!isPeriodValid && <p className="error">종료 시각은 시작 시각보다 뒤여야 해요.</p>}

      {create.error && <p className="error">{create.error.message}</p>}
      {createdMessage && <p className="success">{createdMessage}</p>}

      <button type="submit" className="primary" disabled={!canSubmit}>
        {create.isPending ? "만드는 중…" : "만들기"}
      </button>
    </form>
  );
}

function PromotionCard({
  promotion,
  status,
  products,
}: {
  promotion: AdminPromotion;
  status: PromotionStatus;
  products: AdminProduct[];
}) {
  const queryClient = useQueryClient();
  const [isEditing, setIsEditing] = useState(false);
  const [name, setName] = useState(promotion.name);
  const [discountRateText, setDiscountRateText] = useState(promotion.discountRate?.toString() ?? "");
  const [startsAt, setStartsAt] = useState(convertToKstInputValue(promotion.startsAt));
  const [endsAt, setEndsAt] = useState(promotion.endsAt ? convertToKstInputValue(promotion.endsAt) : "");
  const [message, setMessage] = useState<string | null>(null);

  const discountRate = convertDiscountRate(discountRateText);
  const startsAtValue = convertFromKstInputValue(startsAt);
  const endsAtValue = convertFromKstInputValue(endsAt);

  const onSaved = (text: string) => {
    setMessage(text);
    queryClient.invalidateQueries({ queryKey: ["promotions"] });
  };

  const save = useMutation({
    mutationFn: () =>
      updatePromotion(promotion.promotionId, {
        name: name.trim(),
        discountRate: discountRate ?? null,
        startsAt: startsAtValue!,
        endsAt: endsAtValue,
      }),
    onSuccess: () => {
      setIsEditing(false);
      onSaved("프로모션을 바꿨어요.");
    },
  });

  // 진행 중인 프로모션만 지금 시각으로 끝낸다. 예정인 프로모션은 기간을 고친다(종료가 시작보다 앞일 수 없다).
  const end = useMutation({
    mutationFn: () =>
      updatePromotion(promotion.promotionId, {
        name: promotion.name,
        discountRate: promotion.discountRate,
        startsAt: promotion.startsAt,
        endsAt: new Date().toISOString(),
      }),
    onSuccess: () => onSaved("프로모션을 끝냈어요."),
  });

  const openEdit = () => {
    setIsEditing(true);
    setName(promotion.name);
    setDiscountRateText(promotion.discountRate?.toString() ?? "");
    setStartsAt(convertToKstInputValue(promotion.startsAt));
    setEndsAt(promotion.endsAt ? convertToKstInputValue(promotion.endsAt) : "");
    setMessage(null);
    save.reset();
  };

  const canSave =
    name.trim().length > 0 &&
    name.length <= PROMOTION_NAME_MAX &&
    discountRate !== undefined &&
    startsAtValue !== null &&
    (endsAtValue === null || endsAtValue > startsAtValue) &&
    !save.isPending;
  const error = save.error ?? end.error;

  return (
    <article className="card stack">
      <div className="meta">
        <span>#{promotion.promotionId}</span>
        <span className="badge">{PROMOTION_CATEGORY_LABELS[promotion.category]}</span>
        <span className={`badge${status === "IN_PROGRESS" ? " done" : status === "SCHEDULED" ? " waiting" : ""}`}>
          {PROMOTION_STATUS_LABELS[status]}
        </span>
        <span className="badge">{formatDiscountRate(promotion.discountRate)}</span>
      </div>
      {/* 운영자가 입력한 값도 텍스트로만 렌더링한다. */}
      <h3 className="name">{promotion.name}</h3>
      <span className="muted">
        {formatDateTime(promotion.startsAt)} ~ {promotion.endsAt ? formatDateTime(promotion.endsAt) : ""} · 앱 링크
        /shop/promotions/{promotion.promotionId}
      </span>

      {!isEditing && (
        <div className="actions">
          <button type="button" className="secondary" onClick={openEdit}>
            정보 수정
          </button>
          {status === "IN_PROGRESS" && (
            <button
              type="button"
              className="secondary"
              disabled={end.isPending}
              onClick={() => {
                if (window.confirm(`"${promotion.name}" 프로모션을 지금 끝낼까요?`)) end.mutate();
              }}
            >
              {end.isPending ? "끝내는 중…" : "지금 끝내기"}
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
          <div className="form-row">
            <label>
              이름
              <input type="text" value={name} onChange={(e) => setName(e.target.value)} maxLength={PROMOTION_NAME_MAX} />
            </label>
            <label>
              할인율(%)
              <input
                type="text"
                inputMode="numeric"
                placeholder="비우면 할인 없음"
                value={discountRateText}
                onChange={(e) => setDiscountRateText(e.target.value)}
              />
            </label>
          </div>
          <div className="form-row">
            <label>
              시작(한국 시간)
              <input type="datetime-local" value={startsAt} onChange={(e) => setStartsAt(e.target.value)} />
            </label>
            <label>
              종료(비우면 종료일 없음)
              <input type="datetime-local" value={endsAt} onChange={(e) => setEndsAt(e.target.value)} />
            </label>
          </div>
          {discountRate === undefined && <p className="error">할인율은 1~99 정수로 적거나 비워 주세요.</p>}
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

      <PromotionProductsEditor promotion={promotion} products={products} />
    </article>
  );
}

/**
 * 진열 상품 편집. 순서를 바꾸고 넣고 빼다가 저장하면 목록을 통째로 보낸다.
 * 저장 전 결제가 미리보기는 서버가 준 값만 보여 주고, 새로 넣은 상품은 저장한 뒤에 계산된다.
 */
function PromotionProductsEditor({ promotion, products }: { promotion: AdminPromotion; products: AdminProduct[] }) {
  const queryClient = useQueryClient();
  const savedProductIds = promotion.products.map((product) => product.productId);
  const [productIds, setProductIds] = useState<number[]>(savedProductIds);
  const [productIdToAdd, setProductIdToAdd] = useState("");
  const [message, setMessage] = useState<string | null>(null);

  const productsById = new Map(products.map((product) => [product.productId, product]));
  const promotionProductsById = new Map(promotion.products.map((product) => [product.productId, product]));
  const now = Date.now();
  // 판매가 끝난 상품은 넣을 후보에서 뺀다. 이미 진열한 상품은 그대로 둔다.
  const candidates = products.filter(
    (product) => !productIds.includes(product.productId) && getStatus(product.saleStartsAt, product.saleEndsAt, now) !== "ENDED",
  );
  const isChanged = productIds.join(",") !== savedProductIds.join(",");

  const save = useMutation({
    mutationFn: () => updatePromotionProducts(promotion.promotionId, productIds),
    onSuccess: (saved) => {
      setProductIds(saved.products.map((product) => product.productId));
      setMessage("진열 상품을 저장했어요.");
      queryClient.invalidateQueries({ queryKey: ["promotions"] });
    },
  });

  const move = (index: number, offset: number) => {
    const next = [...productIds];
    [next[index], next[index + offset]] = [next[index + offset], next[index]];
    setProductIds(next);
    setMessage(null);
  };

  return (
    <div className="stack">
      <h4>진열 상품 ({productIds.length}개)</h4>
      {productIds.length === 0 && <p className="muted">진열한 상품이 없어요.</p>}
      {productIds.length > 0 && (
        <table className="table">
          <thead>
            <tr>
              <th>순서</th>
              <th>상품</th>
              <th>정가 → 결제가</th>
              <th>판매</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {productIds.map((productId, index) => {
              const saved = promotionProductsById.get(productId);
              const product = productsById.get(productId);
              const productName = saved?.name ?? product?.name ?? `#${productId}`;
              const saleStartsAt = saved?.saleStartsAt ?? product?.saleStartsAt;
              const saleEndsAt = saved?.saleEndsAt ?? product?.saleEndsAt ?? null;
              return (
                <tr key={productId}>
                  <td>{index + 1}</td>
                  <td className="title">
                    #{productId} {productName}
                  </td>
                  <td>
                    {saved
                      ? `${saved.listPrice.toLocaleString()} → ${saved.purchasePrice.toLocaleString()}`
                      : `${product?.price.toLocaleString() ?? "-"} → 저장 후 계산`}
                  </td>
                  <td>{saleStartsAt ? SALE_STATUS_LABELS[getStatus(saleStartsAt, saleEndsAt, now)] : "-"}</td>
                  <td>
                    <div className="actions">
                      <button type="button" className="secondary" disabled={index === 0} onClick={() => move(index, -1)}>
                        ↑
                      </button>
                      <button
                        type="button"
                        className="secondary"
                        disabled={index === productIds.length - 1}
                        onClick={() => move(index, 1)}
                      >
                        ↓
                      </button>
                      <button
                        type="button"
                        className="link"
                        onClick={() => {
                          setProductIds(productIds.filter((id) => id !== productId));
                          setMessage(null);
                        }}
                      >
                        빼기
                      </button>
                    </div>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}

      <div className="actions">
        <select aria-label="넣을 상품" value={productIdToAdd} onChange={(e) => setProductIdToAdd(e.target.value)}>
          <option value="">넣을 상품 고르기</option>
          {candidates.map((product) => (
            <option key={product.productId} value={product.productId}>
              #{product.productId} {product.name} ({product.price.toLocaleString()} 코인)
            </option>
          ))}
        </select>
        <button
          type="button"
          className="secondary"
          disabled={productIdToAdd === "" || productIds.length >= PROMOTION_PRODUCTS_MAX}
          onClick={() => {
            setProductIds([...productIds, Number(productIdToAdd)]);
            setProductIdToAdd("");
            setMessage(null);
          }}
        >
          넣기
        </button>
        <button type="button" className="primary" disabled={!isChanged || save.isPending} onClick={() => save.mutate()}>
          {save.isPending ? "저장 중…" : "진열 저장"}
        </button>
        {isChanged && (
          <button
            type="button"
            className="link"
            onClick={() => {
              setProductIds(savedProductIds);
              setMessage(null);
            }}
          >
            되돌리기
          </button>
        )}
      </div>

      {save.error && <p className="error">{save.error.message}</p>}
      {message && <p className="success">{message}</p>}
    </div>
  );
}
