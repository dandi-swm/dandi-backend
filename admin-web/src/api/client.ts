// 백엔드 공통 에러 응답: { code, message }
export class ApiError extends Error {
  readonly status: number;
  readonly code: string | null;

  constructor(status: number, code: string | null, message: string) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

export const isUnauthorized = (error: unknown) => error instanceof ApiError && error.status === 401;

/**
 * 어드민 API 호출. 토큰은 httpOnly 쿠키라 JS가 다루지 않고, credentials: "include"로 브라우저가 붙이게 한다.
 * 같은 출처(개발 서버 프록시)라 "same-origin"으로도 붙지만, 프록시 없이 백엔드를 직접 부를 때를 위해 include로 둔다.
 */
export async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(path, {
    ...init,
    credentials: "include",
    headers: {
      ...(init.body ? { "Content-Type": "application/json" } : {}),
      ...init.headers,
    },
  });

  if (!response.ok) {
    const body = await response.json().catch(() => null);
    throw new ApiError(response.status, body?.code ?? null, body?.message ?? `요청이 실패했습니다 (${response.status})`);
  }

  if (response.status === 204) {
    return undefined as T;
  }
  return response.json() as Promise<T>;
}
