const DATE_TIME_FORMAT = new Intl.DateTimeFormat("ko-KR", {
  timeZone: "Asia/Seoul",
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
  hour: "2-digit",
  minute: "2-digit",
});

/** 서버의 UTC 시각을 한국 시간으로 보여 준다. */
export const formatDateTime = (value: string | null) => (value ? DATE_TIME_FORMAT.format(new Date(value)) : "-");

const KST_OFFSET_MS = 9 * 60 * 60 * 1000;

/** 서버의 UTC 시각을 datetime-local 입력값(한국 시간, 분 단위)으로 바꾼다. 한국은 일광 절약 시간이 없어 9시간을 더한다. */
export const convertToKstInputValue = (value: string) =>
  new Date(new Date(value).getTime() + KST_OFFSET_MS).toISOString().slice(0, 16);

/** datetime-local 입력값(한국 시간)을 서버로 보낼 UTC ISO 시각으로 바꾼다. 비었으면 null이다. */
export const convertFromKstInputValue = (value: string) => (value ? new Date(`${value}:00+09:00`).toISOString() : null);
