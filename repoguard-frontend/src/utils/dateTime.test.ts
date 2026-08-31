import { afterEach, describe, expect, it } from "vitest";
import {
  DEFAULT_TIMEZONE,
  formatDateTime,
  getTimezoneOptions,
  setDateTimePreferences
} from "@/utils/dateTime";

describe("date-time preferences", () => {
  afterEach(() => {
    setDateTimePreferences(DEFAULT_TIMEZONE);
  });

  it("formats one UTC instant using the configured timezone", () => {
    setDateTimePreferences("Asia/Shanghai");
    expect(formatDateTime("2024-03-10T07:30:00Z")).toContain("15:30:00");

    setDateTimePreferences("America/New_York");
    expect(formatDateTime("2024-03-10T07:30:00Z")).toContain("03:30:00");
  });

  it("handles a DST spring-forward boundary without browser-local assumptions", () => {
    setDateTimePreferences("America/New_York");
    expect(formatDateTime("2024-03-10T06:30:00Z")).toContain("01:30:00");
    expect(formatDateTime("2024-03-10T07:30:00Z")).toContain("03:30:00");
  });

  it("interprets legacy local timestamps with the documented Asia/Shanghai source zone", () => {
    setDateTimePreferences("America/New_York");
    expect(formatDateTime("2024-03-10 15:30:00")).toContain("03:30:00");
  });

  it("rejects invalid IANA zones and renders empty values safely", () => {
    expect(() => setDateTimePreferences("Mars/Olympus")).toThrow(RangeError);
    expect(formatDateTime()).toBe("-");
    expect(formatDateTime(null)).toBe("-");
  });

  it("exposes a selectable IANA timezone list including UTC", () => {
    expect(getTimezoneOptions()).toContain("Asia/Shanghai");
    expect(getTimezoneOptions()).toContain("UTC");
  });
});
