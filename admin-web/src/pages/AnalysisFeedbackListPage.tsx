import { useInfiniteQuery } from "@tanstack/react-query";
import { useSearchParams } from "react-router-dom";
import { getAnalysisFeedbacks, LANGUAGE_LABELS, type AnalysisFeedbackFilter, type Language } from "../api/admin";
import { formatDateTime } from "../format";

type ReasonFilter = "written" | "all";

const REASON_FILTER_LABELS: Record<ReasonFilter, string> = {
  written: "이유 있는 평가",
  all: "전체",
};

const MAX_RATING_OPTIONS = [1, 2, 3, 4];

const LANGUAGES = Object.keys(LANGUAGE_LABELS) as Language[];

/** 주소의 language 값. 손으로 고친 주소에 모르는 값이 있으면 언어로 거르지 않는다. */
const toLanguage = (value: string | null): Language | undefined =>
  LANGUAGES.find((language) => language === value);

/**
 * 분석 평가 목록. 필터는 주소(?reason=&maxRating=&language=)에 둬서 새로고침·뒤로 가기에도 유지된다.
 * 이유를 읽으려고 만든 화면이라 기본은 이유 있는 평가만 보여 준다.
 */
export function AnalysisFeedbackListPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const reason = (searchParams.get("reason") as ReasonFilter | null) ?? "written";
  const maxRatingParam = searchParams.get("maxRating");
  const language = toLanguage(searchParams.get("language"));
  const filter: AnalysisFeedbackFilter = {
    maxRating: maxRatingParam ? Number(maxRatingParam) : undefined,
    hasReason: reason === "written" ? true : undefined,
    language,
  };

  const analysisFeedbacks = useInfiniteQuery({
    queryKey: ["analysisFeedbacks", filter],
    queryFn: ({ pageParam }) => getAnalysisFeedbacks(filter, pageParam),
    initialPageParam: null as number | null,
    getNextPageParam: (last) => (last.hasNext ? last.nextCursor : undefined),
  });

  const updateFilter = (key: "reason" | "maxRating" | "language", value: string) => {
    const next = new URLSearchParams(searchParams);
    if (value) next.set(key, value);
    else next.delete(key);
    setSearchParams(next, { replace: true });
  };

  const rows = analysisFeedbacks.data?.pages.flatMap((page) => page.analysisFeedbacks) ?? [];

  return (
    <section>
      <div className="toolbar">
        <h2>분석 평가</h2>
        <div className="filters">
          <div className="segmented">
            {(Object.keys(REASON_FILTER_LABELS) as ReasonFilter[]).map((value) => (
              <button
                key={value}
                type="button"
                className={value === reason ? "active" : ""}
                onClick={() => updateFilter("reason", value)}
              >
                {REASON_FILTER_LABELS[value]}
              </button>
            ))}
          </div>
          <select value={maxRatingParam ?? ""} onChange={(e) => updateFilter("maxRating", e.target.value)}>
            <option value="">모든 별점</option>
            {MAX_RATING_OPTIONS.map((value) => (
              <option key={value} value={value}>
                {value}점 이하
              </option>
            ))}
          </select>
          <select value={language ?? ""} onChange={(e) => updateFilter("language", e.target.value)}>
            <option value="">모든 언어</option>
            {LANGUAGES.map((value) => (
              <option key={value} value={value}>
                {LANGUAGE_LABELS[value]}
              </option>
            ))}
          </select>
        </div>
      </div>
      <p className="muted">식사의 메뉴명·영양은 평가 당시가 아니라 지금 값입니다. 사용자가 평가 뒤에 고쳤을 수 있습니다.</p>
      <p className="muted">
        언어는 평가를 등록한 앱 언어입니다. 예전 평가는 사용자의 지금 기기 언어로 채웠고, 알 수 없으면 한국어로 봅니다.
      </p>

      {analysisFeedbacks.isPending && <p className="muted">불러오는 중…</p>}
      {analysisFeedbacks.error && <p className="error">{analysisFeedbacks.error.message}</p>}
      {analysisFeedbacks.isSuccess && rows.length === 0 && <p className="muted">평가가 없습니다.</p>}

      {rows.length > 0 && (
        <table className="table">
          <thead>
            <tr>
              <th>사진</th>
              <th>별점</th>
              <th>언어</th>
              <th>이유</th>
              <th>식사</th>
              <th>영양</th>
              <th>평가</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((analysisFeedback) => {
              const { meal } = analysisFeedback;
              const { nutrition } = meal;

              return (
                <tr key={analysisFeedback.analysisFeedbackId}>
                  <td>
                    <a href={meal.imageUrl} target="_blank" rel="noreferrer">
                      <img className="meal-photo" src={meal.imageUrl} alt={meal.name} loading="lazy" />
                    </a>
                  </td>
                  <td>★ {analysisFeedback.rating.toFixed(1)}</td>
                  <td>{LANGUAGE_LABELS[analysisFeedback.language]}</td>
                  {/* 이유·메뉴명·고양이 코멘트는 사용자·모델이 만든 값이라 텍스트로만 렌더링한다(React가 이스케이프). */}
                  <td className="title">{analysisFeedback.reason?.trim() || <span className="muted">-</span>}</td>
                  <td className="wrap">
                    <div>
                      {meal.name} {meal.deletedAt && <span className="badge">삭제됨</span>}
                    </div>
                    {meal.catComment && <div className="muted">{meal.catComment}</div>}
                    <div className="muted">식사 #{meal.mealId} · {formatDateTime(meal.mealAt)}</div>
                  </td>
                  <td>
                    {nutrition.calory}kcal
                    <div className="muted">
                      탄 {nutrition.carbs} · 단 {nutrition.protein} · 지 {nutrition.fat}
                    </div>
                  </td>
                  <td>{formatDateTime(analysisFeedback.createdAt)}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}

      {analysisFeedbacks.hasNextPage && (
        <button
          type="button"
          className="more"
          onClick={() => analysisFeedbacks.fetchNextPage()}
          disabled={analysisFeedbacks.isFetchingNextPage}
        >
          {analysisFeedbacks.isFetchingNextPage ? "불러오는 중…" : "더 보기"}
        </button>
      )}
    </section>
  );
}
