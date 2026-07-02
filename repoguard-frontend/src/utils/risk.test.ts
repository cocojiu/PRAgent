import { describe, expect, it } from "vitest";
import { riskText } from "./risk";

describe("riskText", () => {
  it("maps known risk levels to display labels", () => {
    expect(riskText("critical")).toBe("严重");
    expect(riskText("high")).toBe("高风险");
    expect(riskText("medium")).toBe("中风险");
    expect(riskText("low")).toBe("低风险");
    expect(riskText("info")).toBe("提示");
  });
});
