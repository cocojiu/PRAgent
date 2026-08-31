export const SUPPORTED_LOCALE = "zh-CN";
export const DEFAULT_TIMEZONE = "Asia/Shanghai";

const STORAGE_KEY = "repoguard.date-time-preferences";

type DateTimePreferences = {
  locale: string;
  timezone: string;
};

type DateTimeInput = Date | string | number;

const LEGACY_LOCAL_DATE_TIME = /^(\d{4}-\d{2}-\d{2})[ T](\d{2}:\d{2}:\d{2})(?:\.\d+)?$/;

const isValidTimezone = (timezone: string) => {
  try {
    new Intl.DateTimeFormat(SUPPORTED_LOCALE, { timeZone: timezone }).format();
    return true;
  } catch {
    return false;
  }
};

const loadPreferences = (): DateTimePreferences => {
  if (typeof window === "undefined") {
    return { locale: SUPPORTED_LOCALE, timezone: DEFAULT_TIMEZONE };
  }
  try {
    const stored = JSON.parse(window.localStorage.getItem(STORAGE_KEY) ?? "{}") as Partial<DateTimePreferences>;
    return {
      locale: SUPPORTED_LOCALE,
      timezone: stored.timezone && isValidTimezone(stored.timezone) ? stored.timezone : DEFAULT_TIMEZONE
    };
  } catch {
    return { locale: SUPPORTED_LOCALE, timezone: DEFAULT_TIMEZONE };
  }
};

let preferences = loadPreferences();

export const setDateTimePreferences = (timezone: string) => {
  if (!isValidTimezone(timezone)) {
    throw new RangeError(`Invalid IANA timezone: ${timezone}`);
  }
  preferences = { locale: SUPPORTED_LOCALE, timezone };
  if (typeof window !== "undefined") {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(preferences));
  }
};

export const formatDateTime = (
  value?: DateTimeInput | null,
  options: Intl.DateTimeFormatOptions = { dateStyle: "medium", timeStyle: "medium" }
) => {
  if (value === undefined || value === null || value === "") {
    return "-";
  }
  const legacyMatch = typeof value === "string" ? LEGACY_LOCAL_DATE_TIME.exec(value) : null;
  const instant = value instanceof Date
    ? value
    : new Date(legacyMatch ? `${legacyMatch[1]}T${legacyMatch[2]}+08:00` : value);
  if (Number.isNaN(instant.getTime())) {
    return typeof value === "string" ? value : "-";
  }
  return new Intl.DateTimeFormat(preferences.locale, {
    hour12: false,
    ...options,
    timeZone: preferences.timezone
  }).format(instant);
};

export const getTimezoneOptions = () => {
  const intlWithSupportedValues = Intl as typeof Intl & {
    supportedValuesOf?: (key: "timeZone") => string[];
  };
  const supported = intlWithSupportedValues.supportedValuesOf?.("timeZone");
  return supported?.length
    ? Array.from(new Set([...supported, "UTC"]))
    : ["Asia/Shanghai", "Asia/Tokyo", "Europe/London", "America/New_York", "America/Los_Angeles", "UTC"];
};
