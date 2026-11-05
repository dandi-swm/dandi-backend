import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { type FormEvent, useState } from "react";
import { useSearchParams } from "react-router-dom";
import {
  type AdminItemPurchaseProduct,
  createRefund,
  getItemPurchases,
  getRefundPreview,
  ITEM_PURCHASE_LINE_STATUS_LABELS,
  type ItemPurchaseLineStatus,
  REFUND_POLICY_EXCEPTION_LABELS,
  REFUND_REASON_MAX,
} from "../api/admin";
import { formatDateTime } from "../format";

const LINE_STATUS_BADGES: Record<ItemPurchaseLineStatus, string> = {
  NOT_CLAIMED: "badge waiting",
  CLAIMED: "badge done",
  REFUNDED: "badge refunded",
};

/** 주소의 값이 양의 정수일 때만 사용자 id로 본다. */
const convertToUserId = (value: string | null) => {
  const userId = Number(value);

  return Number.isSafeInteger(userId) && userId > 0 ? userId : null;
};

/**
 * 사용자의 구매 내역. 사용자 id는 주소(?userId=)에 둬서 새로고침·뒤로 가기에도 유지된다.
 */
export function ItemPurchaseListPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const userIdParam = searchParams.get("userId");
  const userId = convertToUserId(userIdParam);
  const [userIdInput, setUserIdInput] = useState(userIdParam ?? "");

  const itemPurchases = useInfiniteQuery({
    queryKey: ["itemPurchases", userId],
    queryFn: ({ pageParam }) => getItemPurchases(userId!, pageParam),
    initialPageParam: null as number | null,
    getNextPageParam: (last) => (last.hasNext ? last.nextCursor : undefined),
    enabled: userId !== null,
  });

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault();
    setSearchParams(userIdInput ? { userId: userIdInput } : {});
  };

  const rows = itemPurchases.data?.pages.flatMap((page) => page.itemPurchases) ?? [];

  return (
    <section>
      <div className="toolbar">
        <h2>구매 내역</h2>
        <form className="filters" onSubmit={handleSubmit}>
          <input
            type="number"
            min={1}
            placeholder="사용자 ID"
            aria-label="사용자 ID"
            value={userIdInput}
            onChange={(e) => setUserIdInput(e.target.value)}
          />
          <button type="submit" className="primary" disabled={!userIdInput}>
            조회
          </button>
        </form>
      </div>

      {userId === null && <p className="muted">사용자 ID로 조회합니다. 문의함에서 사용자 ID를 확인할 수 있습니다.</p>}
      {userId !== null && itemPurchases.isPending && <p className="muted">불러오는 중…</p>}
      {itemPurchases.error && <p className="error">{itemPurchases.error.message}</p>}
      {itemPurchases.isSuccess && rows.length === 0 && <p className="muted">구매 내역이 없습니다.</p>}

      <div className="stack">
        {rows.map((itemPurchase) => (
          <article key={itemPurchase.itemPurchaseId} className="card">
            <div className="meta">
              <span>구매 {itemPurchase.itemPurchaseId}</span>
              <span>{formatDateTime(itemPurchase.createdAt)}</span>
              <span>결제 {itemPurchase.purchasePrice} 코인</span>
            </div>
            {itemPurchase.products.map((product) => (
              <ItemPurchaseProductSection key={product.itemPurchaseProductId} product={product} />
            ))}
          </article>
        ))}
      </div>

      {itemPurchases.hasNextPage && (
        <button
          type="button"
          className="more"
          onClick={() => itemPurchases.fetchNextPage()}
          disabled={itemPurchases.isFetchingNextPage}
        >
          {itemPurchases.isFetchingNextPage ? "불러오는 중…" : "더 보기"}
        </button>
      )}
    </section>
  );
}

/**
 * 구매 상품 하나와 그 환불. 환불은 구매 상품 단위라 구성품 선택과 미리 보기도 여기서 관리한다.
 */
function ItemPurchaseProductSection({ product }: { product: AdminItemPurchaseProduct }) {
  const queryClient = useQueryClient();
  const productId = product.itemPurchaseProductId;
  const [checkedLineIds, setCheckedLineIds] = useState<number[]>([]);
  const [reason, setReason] = useState("");
  const [isPolicyException, setIsPolicyException] = useState(false);
  const [refundedMessage, setRefundedMessage] = useState<string | null>(null);

  // 다른 운영자가 먼저 환불하면 목록을 다시 읽었을 때 그 구성품을 선택에서 뺀다. 환불한 구성품은 체크를 풀 수 없어 그대로 두면 막힌다.
  const selectedLineIds = checkedLineIds.filter((id) =>
    product.lines.some((line) => line.itemPurchaseLineId === id && line.status !== "REFUNDED"),
  );

  // 환불 금액을 보여 주는 값이라, 예전 결과를 잠깐이라도 보이지 않도록 캐시에 남기지 않는다.
  const preview = useQuery({
    queryKey: ["refundPreview", productId, selectedLineIds],
    queryFn: () => getRefundPreview(productId, selectedLineIds),
    enabled: selectedLineIds.length > 0,
    gcTime: 0,
  });
  const hasPolicyExceptions = (preview.data?.policyExceptions.length ?? 0) > 0;

  const refund = useMutation({
    mutationFn: (expectedCoinQuantity: number) =>
      createRefund(productId, {
        itemPurchaseLineIds: selectedLineIds,
        reason: reason.trim(),
        expectedCoinQuantity,
        isPolicyException: hasPolicyExceptions && isPolicyException,
      }),
    onSuccess: (result) => {
      setRefundedMessage(`${result.coinQuantity} 코인을 돌려줬습니다. 사용자 잔액은 ${result.balance} 코인입니다.`);
      setCheckedLineIds([]);
      setReason("");
      setIsPolicyException(false);
      queryClient.invalidateQueries({ queryKey: ["itemPurchases"] });
    },
    // 다른 운영자가 먼저 환불했거나 사용자가 그사이 장착했을 수 있어 화면을 최신 상태로 다시 읽는다.
    // 금액이나 경고가 바뀌었을 수 있으니 예외 확인도 다시 받는다.
    onError: () => {
      setIsPolicyException(false);
      queryClient.invalidateQueries({ queryKey: ["itemPurchases"] });
      queryClient.invalidateQueries({ queryKey: ["refundPreview", productId] });
    },
  });

  // 구성품이 바뀌면 경고도 바뀌므로, 앞서 확인한 예외 표시를 이어 쓰지 않는다.
  const toggleLine = (itemPurchaseLineId: number) => {
    setCheckedLineIds((current) =>
      current.includes(itemPurchaseLineId)
        ? current.filter((id) => id !== itemPurchaseLineId)
        : [...current, itemPurchaseLineId].sort((a, b) => a - b),
    );
    setIsPolicyException(false);
    setRefundedMessage(null);
    refund.reset();
  };

  const handleRefund = () => {
    if (!preview.data) return;
    const message = `"${product.productName}"의 구성품 ${selectedLineIds.length}개를 환불하고 ${preview.data.coinQuantity} 코인을 돌려줍니다. 되돌릴 수 없습니다.`;
    if (window.confirm(message)) refund.mutate(preview.data.coinQuantity);
  };

  const canRefund =
    preview.isSuccess &&
    !preview.isFetching &&
    reason.trim().length > 0 &&
    (!hasPolicyExceptions || isPolicyException) &&
    !refund.isPending;

  const lineExceptions = (preview.data?.lines ?? []).filter((line) => line.policyExceptions.length > 0);
  // 구성품에 붙지 않는 예외(돌려줄 코인 0)는 환불 전체의 경고로 따로 보여 준다.
  const overallExceptions = (preview.data?.policyExceptions ?? []).filter(
    (category) => !lineExceptions.some((line) => line.policyExceptions.includes(category)),
  );

  return (
    <div className="purchase-product">
      <h3>
        {product.productName}
        <span className="price">결제 {product.purchasePrice} 코인</span>
      </h3>
      <table className="table">
        <thead>
          <tr>
            <th>환불</th>
            <th>구성품</th>
            <th>정가</th>
            <th>상태</th>
            <th>장착</th>
          </tr>
        </thead>
        <tbody>
          {product.lines.map((line) => (
            <tr key={line.itemPurchaseLineId}>
              <td>
                <input
                  type="checkbox"
                  aria-label={`${line.itemName} 환불 선택`}
                  checked={selectedLineIds.includes(line.itemPurchaseLineId)}
                  disabled={line.status === "REFUNDED" || refund.isPending}
                  onChange={() => toggleLine(line.itemPurchaseLineId)}
                />
              </td>
              <td className="title">{line.itemName}</td>
              <td>{line.listPrice} 코인</td>
              <td>
                <span className={LINE_STATUS_BADGES[line.status]}>{ITEM_PURCHASE_LINE_STATUS_LABELS[line.status]}</span>
              </td>
              <td>{line.isEquipped ? "장착 중" : "-"}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {product.refunds.length > 0 && (
        <ul className="refund-list">
          {product.refunds.map((refund) => (
            <li key={refund.itemPurchaseRefundId}>
              {/* 운영자가 입력한 값이라 텍스트로만 렌더링한다(React가 이스케이프). */}
              {formatDateTime(refund.createdAt)} 환불 {refund.coinQuantity} 코인: {refund.reason}
            </li>
          ))}
        </ul>
      )}
      {refundedMessage && <p className="success">{refundedMessage}</p>}
      {refund.error && <p className="error">{refund.error.message}</p>}
      {selectedLineIds.length > 0 && (
        <div className="refund-panel form">
          {preview.isPending && <p className="muted">계산하는 중…</p>}
          {preview.error && <p className="error">{preview.error.message}</p>}
          {preview.isSuccess && (
            <>
              <p>
                돌려줄 코인: <strong>{preview.data.coinQuantity} 코인</strong>
              </p>
              {hasPolicyExceptions && (
                <ul>
                  {lineExceptions.map((linePreview) => (
                    <li key={linePreview.itemPurchaseLineId}>
                      {product.lines.find((line) => line.itemPurchaseLineId === linePreview.itemPurchaseLineId)?.itemName}:{" "}
                      {linePreview.policyExceptions.map((category) => REFUND_POLICY_EXCEPTION_LABELS[category]).join(", ")}
                    </li>
                  ))}
                  {overallExceptions.map((category) => (
                    <li key={category}>{REFUND_POLICY_EXCEPTION_LABELS[category]}</li>
                  ))}
                </ul>
              )}
              <label>
                환불 이유
                <input
                  type="text"
                  maxLength={REFUND_REASON_MAX}
                  value={reason}
                  onChange={(e) => setReason(e.target.value)}
                />
              </label>
              {hasPolicyExceptions && (
                <label className="checkbox">
                  <input
                    type="checkbox"
                    checked={isPolicyException}
                    onChange={(e) => setIsPolicyException(e.target.checked)}
                  />
                  위 내용을 확인했고 정책 예외로 환불합니다
                </label>
              )}
              <div className="actions">
                <button type="button" className="primary" disabled={!canRefund} onClick={handleRefund}>
                  {refund.isPending ? "환불하는 중…" : "환불"}
                </button>
              </div>
            </>
          )}
        </div>
      )}
    </div>
  );
}
