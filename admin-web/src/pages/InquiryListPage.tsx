import { useInfiniteQuery } from "@tanstack/react-query";
import { Link, useSearchParams } from "react-router-dom";
import { getInquiries, INQUIRY_CATEGORY_LABELS, type InquiryCategory, type InquiryFilter } from "../api/admin";
import { formatDateTime } from "../format";

type AnswerStatus = "waiting" | "answered" | "all";

const ANSWER_STATUS_LABELS: Record<AnswerStatus, string> = {
  waiting: "답변 대기",
  answered: "답변 완료",
  all: "전체",
};

const toIsAnswered = (status: AnswerStatus): boolean | undefined =>
  status === "all" ? undefined : status === "answered";

/**
 * 문의함. 필터는 주소(?status=&category=)에 둬서 새로고침·뒤로 가기에도 유지된다. 기본은 답변 대기다.
 */
export function InquiryListPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const status = (searchParams.get("status") as AnswerStatus | null) ?? "waiting";
  const category = (searchParams.get("category") as InquiryCategory | null) ?? undefined;
  const filter: InquiryFilter = { category, isAnswered: toIsAnswered(status) };

  const inquiries = useInfiniteQuery({
    queryKey: ["inquiries", filter],
    queryFn: ({ pageParam }) => getInquiries(filter, pageParam),
    initialPageParam: null as number | null,
    getNextPageParam: (last) => (last.hasNext ? last.nextCursor : undefined),
  });

  const updateFilter = (key: "status" | "category", value: string) => {
    const next = new URLSearchParams(searchParams);
    if (value) next.set(key, value);
    else next.delete(key);
    setSearchParams(next, { replace: true });
  };

  const rows = inquiries.data?.pages.flatMap((page) => page.inquiries) ?? [];

  return (
    <section>
      <div className="toolbar">
        <h2>문의함</h2>
        <div className="filters">
          <div className="segmented">
            {(Object.keys(ANSWER_STATUS_LABELS) as AnswerStatus[]).map((value) => (
              <button
                key={value}
                type="button"
                className={value === status ? "active" : ""}
                onClick={() => updateFilter("status", value)}
              >
                {ANSWER_STATUS_LABELS[value]}
              </button>
            ))}
          </div>
          <select value={category ?? ""} onChange={(e) => updateFilter("category", e.target.value)}>
            <option value="">모든 유형</option>
            {(Object.keys(INQUIRY_CATEGORY_LABELS) as InquiryCategory[]).map((value) => (
              <option key={value} value={value}>
                {INQUIRY_CATEGORY_LABELS[value]}
              </option>
            ))}
          </select>
        </div>
      </div>

      {inquiries.isPending && <p className="muted">불러오는 중…</p>}
      {inquiries.error && <p className="error">{inquiries.error.message}</p>}
      {inquiries.isSuccess && rows.length === 0 && <p className="muted">문의가 없습니다.</p>}

      {rows.length > 0 && (
        <table className="table">
          <thead>
            <tr>
              <th>번호</th>
              <th>유형</th>
              <th>제목</th>
              <th>사용자</th>
              <th>접수</th>
              <th>상태</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((inquiry) => (
              <tr key={inquiry.inquiryId}>
                <td>{inquiry.inquiryId}</td>
                <td>{INQUIRY_CATEGORY_LABELS[inquiry.category]}</td>
                <td className="title">
                  {/* 사용자가 입력한 값이라 텍스트로만 렌더링한다(React가 이스케이프). */}
                  <Link to={`/inquiries/${inquiry.inquiryId}`}>{inquiry.questionTitle}</Link>
                </td>
                <td>{inquiry.userId}</td>
                <td>{formatDateTime(inquiry.createdAt)}</td>
                <td>
                  <span className={inquiry.isAnswered ? "badge done" : "badge waiting"}>
                    {inquiry.isAnswered ? "답변 완료" : "답변 대기"}
                  </span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {inquiries.hasNextPage && (
        <button
          type="button"
          className="more"
          onClick={() => inquiries.fetchNextPage()}
          disabled={inquiries.isFetchingNextPage}
        >
          {inquiries.isFetchingNextPage ? "불러오는 중…" : "더 보기"}
        </button>
      )}
    </section>
  );
}
