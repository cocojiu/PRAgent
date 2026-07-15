import type { ManagedUser } from "@/api/users";
import type { MetricGridItem } from "@/components/MetricGrid.vue";

export const buildUserManagementMetrics = (
  pageUsers: ManagedUser[],
  matchingTotal: number,
  filtered: boolean
): MetricGridItem[] => {
  const active = pageUsers.filter((user) => user.status === "ACTIVE").length;
  const admins = pageUsers.filter((user) => user.role === "ADMIN").length;
  const disabled = pageUsers.filter((user) => user.status === "DISABLED").length;
  const pageNote = `当前页 ${pageUsers.length} 个账号`;
  return [
    {
      label: filtered ? "匹配账号数" : "账号总数",
      value: String(matchingTotal),
      note: filtered ? "当前筛选结果" : "服务端账号总量",
      color: "blue"
    },
    { label: "本页启用", value: String(active), note: pageNote, color: "green" },
    { label: "本页管理员", value: String(admins), note: pageNote, color: "orange" },
    { label: "本页禁用", value: String(disabled), note: pageNote, color: "red" }
  ];
};
