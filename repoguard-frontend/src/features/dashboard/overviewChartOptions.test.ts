import { describe, expect, it } from "vitest";
import { buildLlmQualityTrendOption } from "./overviewChartOptions";
import type { LlmQualityTrendPoint } from "@/types";

const point = (taskCount: number, rate: string): LlmQualityTrendPoint => ({
  date: "10-24", taskCount, parseSuccessRate: rate, fallbackRate: rate, partialFallbackRate: rate
});
const rateSeries = (points: LlmQualityTrendPoint[]) =>
  (buildLlmQualityTrendOption(points).series as Array<{ data: unknown[] }>).slice(1);

describe("LLM quality trend", () => {
  it("preserves measured zero and leaves unknown rates as gaps", () => {
    const points = [point(4, "75.0%"), point(0, "—"), point(2, "0.0%"), point(1, "100%")];
    for (const series of rateSeries(points)) expect(series.data).toEqual([75, null, 0, 100]);
  });

  it("does not turn zero-task rates from older servers into measured zeros", () => {
    for (const series of rateSeries([point(0, "0.0%")])) expect(series.data).toEqual([null]);
  });

  it.each(["—", "", " ", "N/A", "NaN", "Infinity", "-1%", "100.1%", "50%bad"])(
    "keeps unavailable or invalid rate %j out of the chart",
    rate => { for (const series of rateSeries([point(1, rate)])) expect(series.data).toEqual([null]); }
  );
});
