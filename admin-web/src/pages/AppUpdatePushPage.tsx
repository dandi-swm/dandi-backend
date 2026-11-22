import { useMutation } from "@tanstack/react-query";
import { type FormEvent, useState } from "react";
import {
  APP_VERSION_PATTERN,
  DEVICE_PLATFORM_LABELS,
  type DevicePlatform,
  sendAppUpdatePushes,
} from "../api/admin";

/**
 * 새 버전 출시 뒤 그 플랫폼 사용자 전체에게 업데이트 알림을 보낸다. 되돌릴 수 없어 보내기 전에 한 번 더 묻는다.
 */
export function AppUpdatePushPage() {
  const [platform, setPlatform] = useState<DevicePlatform>("ANDROID");
  const [appVersion, setAppVersion] = useState("");

  const send = useMutation({ mutationFn: () => sendAppUpdatePushes(platform, appVersion.trim()) });

  const isValidVersion = APP_VERSION_PATTERN.test(appVersion.trim());
  const canSubmit = isValidVersion && !send.isPending;

  const handleSubmit = (e: FormEvent) => {
    e.preventDefault();
    if (!canSubmit) return;

    const label = DEVICE_PLATFORM_LABELS[platform];
    const confirmed = window.confirm(
      `${label} ${appVersion.trim()} 업데이트 알림을 서비스 알림을 켠 ${label} 사용자 전체에게 보냅니다.\n` +
        "스토어에 100% 출시됐는지 확인했나요? 보낸 알림은 되돌릴 수 없어요.",
    );
    if (confirmed) send.mutate();
  };

  return (
    <section className="stack">
      <h2>앱 업데이트 알림</h2>

      <form className="card form" onSubmit={handleSubmit}>
        <p className="notice">
          서비스 알림을 켠 그 플랫폼 사용자 전체에게 "새 버전이 나왔다냥" 알림이 가고, 누르면 스토어가 열려요. 이미 업데이트한
          사용자도 받아요. 단계적 출시 중이면 아직 업데이트 버튼이 안 보이는 사용자가 있으니 100% 출시 뒤에 보내 주세요.
        </p>
        <p className="notice">
          같은 플랫폼·버전으로 다시 보내면 이미 받은 사용자는 건너뛰어요. 중간에 끊겼거나 일시 장애가 있었으면 다시 보내면 돼요.
          8시~21시에만 보낼 수 있어요.
        </p>

        <div className="form-row">
          <label>
            플랫폼
            <select value={platform} onChange={(e) => setPlatform(e.target.value as DevicePlatform)}>
              {(Object.keys(DEVICE_PLATFORM_LABELS) as DevicePlatform[]).map((value) => (
                <option key={value} value={value}>
                  {DEVICE_PLATFORM_LABELS[value]}
                </option>
              ))}
            </select>
          </label>

          <label>
            출시한 버전
            <input type="text" value={appVersion} onChange={(e) => setAppVersion(e.target.value)} placeholder="1.4.0" />
          </label>
        </div>

        {appVersion.trim() !== "" && !isValidVersion && <p className="error">1.4.0처럼 숫자와 점으로 적어 주세요.</p>}
        {send.error && <p className="error">{send.error.message}</p>}
        {send.data && (
          <p className="success">
            대상 {send.data.targetCount}명 중 {send.data.sentCount}명에게 보냈어요. 접수 {send.data.successCount} · 무효 토큰{" "}
            {send.data.invalidCount} · 일시 장애 {send.data.retryableCount}
            {send.data.retryableCount > 0 && " — 같은 버전으로 다시 보내면 일시 장애였던 사용자에게만 가요."}
          </p>
        )}

        <button type="submit" className="primary" disabled={!canSubmit}>
          {send.isPending ? "보내는 중…" : "알림 보내기"}
        </button>
      </form>
    </section>
  );
}
