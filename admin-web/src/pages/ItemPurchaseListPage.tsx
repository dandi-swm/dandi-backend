import { useInfiniteQuery } from "@tanstack/react-query";
import { type FormEvent, useState } from "react";
import { useSearchParams } from "react-router-dom";
import {
  type AdminItemPurchaseProduct,
  getItemPurchases,
  ITEM_PURCHASE_LINE_STATUS_LABELS,
  type ItemPurchaseLineStatus,
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

function ItemPurchaseProductSection({ product }: { product: AdminItemPurchaseProduct }) {
  return (
    <div className="purchase-product">
      <h3>
        {product.productName}
        <span className="price">
          결제 {product.purchasePrice} 코인 · 정가 {product.listPrice} 코인
        </span>
      </h3>
      <table className="table">
        <thead>
          <tr>
            <th>구성품</th>
            <th>정가</th>
            <th>상태</th>
            <th>장착</th>
          </tr>
        </thead>
        <tbody>
          {product.lines.map((line) => (
            <tr key={line.itemPurchaseLineId}>
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
    </div>
  );
}
