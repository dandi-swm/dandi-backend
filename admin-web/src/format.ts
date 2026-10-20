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
