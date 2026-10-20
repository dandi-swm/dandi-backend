import { useEffect, useState, type FormEvent } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useParams } from "react-router-dom";
import { getInquiry, INQUIRY_CATEGORY_LABELS, updateAnswer, type AdminInquiry } from "../api/admin";
import { formatDateTime } from "../format";

const ANSWER_TITLE_MAX = 100;
const ANSWER_CONTENT_MAX = 2000;

/**
 * 문의 상세와 답변 폼. 어드민이 열어 봐도 사용자의 읽음 상태는 바뀌지 않는다(서버가 기록하지 않음).
 */
export function InquiryDetailPage() {
  const inquiryId = Number(useParams().inquiryId);
  const inquiry = useQuery({ queryKey: ["inquiry", inquiryId], queryFn: () => getInquiry(inquiryId) });

  if (inquiry.isPending) return <p className="muted">불러오는 중…</p>;
  if (inquiry.error) return <p className="error">{inquiry.error.message}</p>;

  const data = inquiry.data;

  return (
    <section className="detail">
      <Link to="/inquiries" className="back">
        ← 문의함
      </Link>

      <article className="card">
        <div className="meta">
          <span className="badge">{INQUIRY_CATEGORY_LABELS[data.category]}</span>
          <span>#{data.inquiryId}</span>
          <span>사용자 {data.userId}</span>
          <span>앱 {data.appVersion ?? "-"}</span>
          <span>{formatDateTime(data.createdAt)}</span>
        </div>
        {/* 사용자가 입력한 값이라 텍스트로만 렌더링한다. dangerouslySetInnerHTML 금지. */}
        <h2>{data.questionTitle}</h2>
        <p className="body">{data.questionContent}</p>
        {data.imageUrl && (
          <a href={data.imageUrl} target="_blank" rel="noreferrer">
            <img src={data.imageUrl} alt="첨부 이미지" className="attachment" />
          </a>
        )}
      </article>

      <AnswerForm inquiry={data} />
    </section>
  );
}

function AnswerForm({ inquiry }: { inquiry: AdminInquiry }) {
  const queryClient = useQueryClient();
  const [answerTitle, setAnswerTitle] = useState(inquiry.answerTitle ?? "");
  const [answerContent, setAnswerContent] = useState(inquiry.answerContent ?? "");
  const [savedMessage, setSavedMessage] = useState<string | null>(null);

  // 다른 문의로 이동했을 때만 폼을 그 문의의 답변으로 다시 채운다. 저장 직후 값이 바뀌어도 입력은 그대로 둔다.
  useEffect(() => {
    setAnswerTitle(inquiry.answerTitle ?? "");
    setAnswerContent(inquiry.answerContent ?? "");
    setSavedMessage(null);
  }, [inquiry.inquiryId]);

  const save = useMutation({
    mutationFn: () => updateAnswer(inquiry.inquiryId, answerTitle.trim(), answerContent.trim()),
    onSuccess: (updated) => {
      setSavedMessage(inquiry.isAnswered ? "답변을 저장했어요." : "답변을 등록하고 사용자에게 알렸어요.");
      queryClient.setQueryData(["inquiry", inquiry.inquiryId], updated);
      queryClient.invalidateQueries({ queryKey: ["inquiries"] });
    },
  });

  const isEditing = inquiry.isAnswered;
  const canSubmit =
    answerTitle.trim().length > 0 &&
    answerContent.trim().length > 0 &&
    answerTitle.length <= ANSWER_TITLE_MAX &&
    answerContent.length <= ANSWER_CONTENT_MAX &&
    !save.isPending;

  const handleSubmit = (e: FormEvent) => {
    e.preventDefault();
    if (canSubmit) save.mutate();
  };

  return (
    <form className="card answer" onSubmit={handleSubmit}>
      <div className="answer-header">
        <h3>{isEditing ? "답변 수정" : "답변 작성"}</h3>
        {isEditing && (
          <span className="muted">
            답변 {formatDateTime(inquiry.answeredAt)} · 사용자 {inquiry.answerReadAt ? `읽음 ${formatDateTime(inquiry.answerReadAt)}` : "안 읽음"}
          </span>
        )}
      </div>

      {isEditing ? (
        <p className="notice">수정은 오타 정정용이에요. 사용자에게 알림이 가지 않고, 이미 읽었다면 읽음 상태도 그대로예요.</p>
      ) : (
        <p className="notice">등록하면 사용자에게 "문의에 답변이 왔다냥" 알림이 가요.</p>
      )}

      <label>
        제목
        <input
          value={answerTitle}
          onChange={(e) => {
            setAnswerTitle(e.target.value);
            setSavedMessage(null);
          }}
          maxLength={ANSWER_TITLE_MAX}
        />
        <span className="counter">
          {answerTitle.length}/{ANSWER_TITLE_MAX}
        </span>
      </label>

      <label>
        내용
        <textarea
          value={answerContent}
          onChange={(e) => {
            setAnswerContent(e.target.value);
            setSavedMessage(null);
          }}
          maxLength={ANSWER_CONTENT_MAX}
          rows={10}
        />
        <span className="counter">
          {answerContent.length}/{ANSWER_CONTENT_MAX}
        </span>
      </label>

      {save.error && <p className="error">{save.error.message}</p>}
      {savedMessage && <p className="success">{savedMessage}</p>}

      <button type="submit" className="primary" disabled={!canSubmit}>
        {save.isPending ? "저장 중…" : isEditing ? "수정 저장" : "답변 등록"}
      </button>
    </form>
  );
}
