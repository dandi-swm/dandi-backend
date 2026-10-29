import { useState, type FormEvent } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  createShopBanner,
  getPromotions,
  getShopBanners,
  PROMOTION_CATEGORY_LABELS,
  SDUI_COLOR_LABELS,
  SHOP_BANNER_DESCRIPTION_MAX,
  SHOP_BANNER_IMAGE_MAX_BYTES,
  SHOP_BANNER_TITLE_MAX,
  updateShopBanner,
  updateShopBannerImage,
  type AdminPromotion,
  type AdminShopBanner,
  type SduiColor,
  type UpdateShopBannerInput,
} from "../api/admin";
import { ImagePicker } from "../components/ImagePicker";
import { convertFromKstInputValue, convertToKstInputValue, formatDateTime } from "../format";

const COLORS = Object.keys(SDUI_COLOR_LABELS) as SduiColor[];

type BannerStatus = "SHOWING" | "SCHEDULED" | "ENDED";

const BANNER_STATUS_LABELS: Record<BannerStatus, string> = { SHOWING: "노출 중", SCHEDULED: "예정", ENDED: "내림" };
const BANNER_STATUSES = Object.keys(BANNER_STATUS_LABELS) as BannerStatus[];

// 기간은 [startsAt, endsAt)이다. 프로모션 기간도 같은 규칙이다.
const getStatus = (startsAt: string, endsAt: string | null, now: number): BannerStatus => {
  if (new Date(startsAt).getTime() > now) return "SCHEDULED";
  if (endsAt !== null && new Date(endsAt).getTime() <= now) return "ENDED";
  return "SHOWING";
};

const createNowInputValue = () => convertToKstInputValue(new Date().toISOString());

/** 만들기·수정이 같이 쓰는 입력값. 시각은 datetime-local(한국 시간) 문자열이다. */
type BannerFieldsValue = {
  title: string;
  description: string;
  titleColor: SduiColor;
  descriptionColor: SduiColor;
  displayOrderText: string;
  startsAt: string;
  endsAt: string;
};

const createEmptyFields = (): BannerFieldsValue => ({
  title: "",
  description: "",
  titleColor: "contentOnInverse",
  descriptionColor: "contentOnInverse",
  displayOrderText: "0",
  startsAt: createNowInputValue(),
  endsAt: "",
});

const convertToFields = (banner: AdminShopBanner): BannerFieldsValue => ({
  title: banner.title,
  description: banner.description ?? "",
  titleColor: banner.titleColor,
  descriptionColor: banner.descriptionColor ?? banner.titleColor,
  displayOrderText: String(banner.displayOrder),
  startsAt: convertToKstInputValue(banner.startsAt),
  endsAt: banner.endsAt ? convertToKstInputValue(banner.endsAt) : "",
});

/** 서버 검사와 같은 규칙으로 먼저 안내한다. 맞으면 보낼 값, 아니면 안내 문구 목록을 준다. */
function validateFields(fields: BannerFieldsValue): { input: UpdateShopBannerInput | null; problems: string[] } {
  const problems: string[] = [];
  const title = fields.title.trim();
  const description = fields.description.trim();
  const startsAt = convertFromKstInputValue(fields.startsAt);
  const endsAt = convertFromKstInputValue(fields.endsAt);
  const isDisplayOrderValid = /^\d+$/.test(fields.displayOrderText.trim());

  if (title.length === 0) problems.push("제목을 적어 주세요.");
  if (!isDisplayOrderValid) problems.push("노출 순서는 0 이상의 정수로 적어 주세요.");
  if (startsAt === null) problems.push("시작 시각을 정해 주세요.");
  if (startsAt !== null && endsAt !== null && endsAt <= startsAt) problems.push("종료 시각은 시작 시각보다 뒤여야 해요.");
  if (problems.length > 0) return { input: null, problems };

  return {
    input: {
      title,
      description: description || null,
      titleColor: fields.titleColor,
      descriptionColor: description ? fields.descriptionColor : null,
      displayOrder: Number(fields.displayOrderText.trim()),
      startsAt: startsAt!,
      endsAt,
    },
    problems,
  };
}

/**
 * 상점 추천 탭 배너 관리. 배너는 프로모션 하나를 알리고, 누르면 그 프로모션 화면을 연다.
 * 지우지 않고, 내릴 때는 종료 시각을 지금으로 바꾼다.
 */
export function ShopBannerListPage() {
  const [status, setStatus] = useState<BannerStatus | undefined>(undefined);
  const shopBanners = useQuery({ queryKey: ["shopBanners"], queryFn: getShopBanners });
  const promotions = useQuery({ queryKey: ["promotions"], queryFn: getPromotions });

  const now = Date.now();
  const rows = (shopBanners.data?.shopBanners ?? []).filter(
    (banner) => status === undefined || getStatus(banner.startsAt, banner.endsAt, now) === status,
  );

  return (
    <section className="stack">
      <div className="toolbar">
        <h2>배너</h2>
        <div className="segmented">
          <button type="button" className={status === undefined ? "active" : ""} onClick={() => setStatus(undefined)}>
            전체
          </button>
          {BANNER_STATUSES.map((value) => (
            <button key={value} type="button" className={value === status ? "active" : ""} onClick={() => setStatus(value)}>
              {BANNER_STATUS_LABELS[value]}
            </button>
          ))}
        </div>
      </div>

      <p className="notice">
        노출 중인 배너가 추천 탭 맨 위에 노출 순서(작은 값이 앞)대로 나와요. 알리는 프로모션은 만든 뒤 바꿀 수 없어요. 이미지를
        바꾸면 새 주소로 올라가서 다음 조회부터 바로 바뀌어요.
      </p>

      <CreateShopBannerForm promotions={promotions.data?.promotions ?? []} />

      {shopBanners.isPending && <p className="muted">불러오는 중…</p>}
      {shopBanners.error && <p className="error">{shopBanners.error.message}</p>}
      {shopBanners.isSuccess && rows.length === 0 && <p className="muted">배너가 없습니다.</p>}

      {rows.map((banner) => (
        <ShopBannerCard key={banner.bannerId} banner={banner} status={getStatus(banner.startsAt, banner.endsAt, now)} />
      ))}
    </section>
  );
}

function BannerFields({ value, onChange }: { value: BannerFieldsValue; onChange: (next: BannerFieldsValue) => void }) {
  const update = (patch: Partial<BannerFieldsValue>) => onChange({ ...value, ...patch });

  return (
    <>
      <label>
        제목
        <input
          type="text"
          value={value.title}
          onChange={(e) => update({ title: e.target.value })}
          maxLength={SHOP_BANNER_TITLE_MAX}
        />
        <span className="counter">
          {value.title.length}/{SHOP_BANNER_TITLE_MAX}
        </span>
      </label>
      <label>
        설명(비우면 없음)
        <input
          type="text"
          value={value.description}
          onChange={(e) => update({ description: e.target.value })}
          maxLength={SHOP_BANNER_DESCRIPTION_MAX}
        />
      </label>
      <div className="form-row">
        <label>
          제목 색
          <select value={value.titleColor} onChange={(e) => update({ titleColor: e.target.value as SduiColor })}>
            {COLORS.map((color) => (
              <option key={color} value={color}>
                {SDUI_COLOR_LABELS[color]}
              </option>
            ))}
          </select>
        </label>
        <label>
          설명 색
          <select
            value={value.descriptionColor}
            disabled={value.description.trim() === ""}
            onChange={(e) => update({ descriptionColor: e.target.value as SduiColor })}
          >
            {COLORS.map((color) => (
              <option key={color} value={color}>
                {SDUI_COLOR_LABELS[color]}
              </option>
            ))}
          </select>
        </label>
        <label>
          노출 순서
          <input
            type="text"
            inputMode="numeric"
            value={value.displayOrderText}
            onChange={(e) => update({ displayOrderText: e.target.value })}
          />
        </label>
      </div>
      <div className="form-row">
        <label>
          시작(한국 시간)
          <input type="datetime-local" value={value.startsAt} onChange={(e) => update({ startsAt: e.target.value })} />
        </label>
        <label>
          종료(비우면 내릴 때까지)
          <input type="datetime-local" value={value.endsAt} onChange={(e) => update({ endsAt: e.target.value })} />
        </label>
      </div>
    </>
  );
}

function CreateShopBannerForm({ promotions }: { promotions: AdminPromotion[] }) {
  const queryClient = useQueryClient();
  const [promotionIdText, setPromotionIdText] = useState("");
  const [fields, setFields] = useState(createEmptyFields);
  const [image, setImage] = useState<File | null>(null);
  const [createdMessage, setCreatedMessage] = useState<string | null>(null);

  // 끝난 프로모션은 알릴 수 없으니 후보에서 뺀다.
  const now = Date.now();
  const candidates = promotions.filter((promotion) => getStatus(promotion.startsAt, promotion.endsAt, now) !== "ENDED");
  const { input, problems } = validateFields(fields);

  const create = useMutation({
    mutationFn: () => createShopBanner({ ...input!, promotionId: Number(promotionIdText), image: image! }),
    onSuccess: (created) => {
      setCreatedMessage(`#${created.bannerId} "${created.title}" 배너를 만들었어요.`);
      setPromotionIdText("");
      setFields(createEmptyFields());
      setImage(null);
      queryClient.invalidateQueries({ queryKey: ["shopBanners"] });
    },
  });

  const canSubmit = promotionIdText !== "" && input !== null && image !== null && !create.isPending;

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
      <h3>배너 만들기</h3>

      <label>
        알릴 프로모션
        <select
          value={promotionIdText}
          onChange={(e) => {
            setPromotionIdText(e.target.value);
            clearMessage();
          }}
        >
          <option value="">프로모션 고르기</option>
          {candidates.map((promotion) => (
            <option key={promotion.promotionId} value={promotion.promotionId}>
              #{promotion.promotionId} {promotion.name} ({PROMOTION_CATEGORY_LABELS[promotion.category]})
            </option>
          ))}
        </select>
      </label>

      <BannerFields
        value={fields}
        onChange={(next) => {
          setFields(next);
          clearMessage();
        }}
      />

      <ImagePicker
        file={image}
        maxBytes={SHOP_BANNER_IMAGE_MAX_BYTES}
        onChange={(file) => {
          setImage(file);
          clearMessage();
        }}
      />

      {fields.title !== "" && problems.map((problem) => <p key={problem} className="error">{problem}</p>)}
      {create.error && <p className="error">{create.error.message}</p>}
      {createdMessage && <p className="success">{createdMessage}</p>}

      <button type="submit" className="primary" disabled={!canSubmit}>
        {create.isPending ? "만드는 중…" : "만들기"}
      </button>
    </form>
  );
}

type CardMode = "view" | "edit" | "image";

function ShopBannerCard({ banner, status }: { banner: AdminShopBanner; status: BannerStatus }) {
  const queryClient = useQueryClient();
  const [mode, setMode] = useState<CardMode>("view");
  const [fields, setFields] = useState(() => convertToFields(banner));
  const [image, setImage] = useState<File | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  const { input, problems } = validateFields(fields);

  const onSaved = (text: string) => {
    setMode("view");
    setMessage(text);
    queryClient.invalidateQueries({ queryKey: ["shopBanners"] });
  };

  const save = useMutation({
    mutationFn: () => updateShopBanner(banner.bannerId, input!),
    onSuccess: () => onSaved("배너를 바꿨어요."),
  });

  // 노출 중인 배너만 지금 시각으로 내린다. 예정인 배너는 기간을 고친다(종료가 시작보다 앞일 수 없다).
  const end = useMutation({
    mutationFn: () =>
      updateShopBanner(banner.bannerId, {
        title: banner.title,
        description: banner.description,
        titleColor: banner.titleColor,
        descriptionColor: banner.descriptionColor,
        displayOrder: banner.displayOrder,
        startsAt: banner.startsAt,
        endsAt: new Date().toISOString(),
      }),
    onSuccess: () => onSaved("배너를 내렸어요."),
  });

  const replaceImage = useMutation({
    mutationFn: (file: File) => updateShopBannerImage(banner.bannerId, file),
    onSuccess: () => {
      setImage(null);
      onSaved("이미지를 바꿨어요.");
    },
  });

  const openMode = (next: CardMode) => {
    setMode(next);
    setFields(convertToFields(banner));
    setImage(null);
    setMessage(null);
    save.reset();
    end.reset();
    replaceImage.reset();
  };

  const error = save.error ?? end.error ?? replaceImage.error;

  return (
    <article className="card stack">
      <img src={banner.imageUrl} alt={banner.title} className="banner-image" />
      <div className="meta">
        <span>#{banner.bannerId}</span>
        <span className={`badge${status === "SHOWING" ? " done" : status === "SCHEDULED" ? " waiting" : ""}`}>
          {BANNER_STATUS_LABELS[status]}
        </span>
        <span>노출 순서 {banner.displayOrder}</span>
        <span>
          프로모션 #{banner.promotionId} {banner.promotionName}
        </span>
      </div>
      {/* 운영자가 입력한 값도 텍스트로만 렌더링한다. */}
      <h3 className="name">{banner.title}</h3>
      {banner.description && <p>{banner.description}</p>}
      <span className="muted">
        제목 색 {SDUI_COLOR_LABELS[banner.titleColor]}
        {banner.descriptionColor && ` · 설명 색 ${SDUI_COLOR_LABELS[banner.descriptionColor]}`}
      </span>
      <span className="muted">
        {formatDateTime(banner.startsAt)} ~ {banner.endsAt ? formatDateTime(banner.endsAt) : ""} · 마지막 수정{" "}
        {formatDateTime(banner.updatedAt)} (운영자 #{banner.adminId})
      </span>

      {mode === "view" && (
        <div className="actions">
          <button type="button" className="secondary" onClick={() => openMode("edit")}>
            정보 수정
          </button>
          <button type="button" className="secondary" onClick={() => openMode("image")}>
            이미지 교체
          </button>
          {status === "SHOWING" && (
            <button
              type="button"
              className="secondary"
              disabled={end.isPending}
              onClick={() => {
                if (window.confirm(`"${banner.title}" 배너를 지금 내릴까요?`)) end.mutate();
              }}
            >
              {end.isPending ? "내리는 중…" : "지금 내리기"}
            </button>
          )}
        </div>
      )}

      {mode === "edit" && (
        <form
          className="form"
          onSubmit={(e) => {
            e.preventDefault();
            if (input !== null && !save.isPending) save.mutate();
          }}
        >
          <BannerFields value={fields} onChange={setFields} />
          {problems.map((problem) => (
            <p key={problem} className="error">
              {problem}
            </p>
          ))}
          <div className="actions">
            <button type="submit" className="secondary" disabled={input === null || save.isPending}>
              {save.isPending ? "저장 중…" : "저장"}
            </button>
            <button type="button" className="link" onClick={() => openMode("view")}>
              취소
            </button>
          </div>
        </form>
      )}

      {mode === "image" && (
        <div className="form">
          <ImagePicker file={image} maxBytes={SHOP_BANNER_IMAGE_MAX_BYTES} onChange={setImage} />
          <div className="actions">
            <button
              type="button"
              className="secondary"
              disabled={image === null || replaceImage.isPending}
              onClick={() => image && replaceImage.mutate(image)}
            >
              {replaceImage.isPending ? "올리는 중…" : "교체"}
            </button>
            <button type="button" className="link" onClick={() => openMode("view")}>
              취소
            </button>
          </div>
        </div>
      )}

      {error && <p className="error">{error.message}</p>}
      {message && <p className="success">{message}</p>}
    </article>
  );
}
