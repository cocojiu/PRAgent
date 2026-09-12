import { describe, expect, it } from "vitest";
import { estimatedCostText } from "./estimatedCost";

describe("estimated CNY cost", () => {
  it("does not present unknown or zero legacy amounts as free", () => {
    for (const value of [null, undefined, 0, "0.000000", -1, NaN, Infinity, "invalid"]) {
      expect(estimatedCostText(value)).toBe("未计价或无可确认费用");
    }
  });
  it("retains small costs and identifies them as estimates", () => {
    expect(estimatedCostText(0.000001)).toBe("¥0.000001（估算）");
    expect(estimatedCostText("0.0036")).toBe("¥0.003600（估算）");
  });
});
