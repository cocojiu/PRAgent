import { describe, expect, it } from "vitest";
import type { ManagedUser } from "@/api/users";
import { buildUserManagementMetrics } from "./userManagementMetrics";

describe("user management metrics", () => {
  it("uses the server total and labels page-only breakdowns honestly", () => {
    const metrics = buildUserManagementMetrics([
      user(1, "ADMIN", "ACTIVE"),
      user(2, "VIEWER", "DISABLED")
    ], 42, false);

    expect(metrics.map(({ label, value }) => [label, value])).toEqual([
      ["账号总数", "42"],
      ["本页启用", "1"],
      ["本页管理员", "1"],
      ["本页禁用", "1"]
    ]);
    expect(metrics[1]?.note).toBe("当前页 2 个账号");
  });

  it("identifies totals returned for filtered queries", () => {
    const metrics = buildUserManagementMetrics([user(1, "ADMIN", "ACTIVE")], 3, true);

    expect(metrics[0]).toMatchObject({
      label: "匹配账号数",
      value: "3",
      note: "当前筛选结果"
    });
  });
});

const user = (id: number, role: ManagedUser["role"], status: ManagedUser["status"]): ManagedUser => ({
  id,
  username: `user-${id}`,
  email: `user-${id}@example.com`,
  role,
  status,
  failedLoginCount: 0,
  createdAt: "2026-07-15T09:00:00",
  updatedAt: "2026-07-15T09:00:00"
});
