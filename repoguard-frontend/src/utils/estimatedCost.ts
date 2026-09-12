/** Zero in legacy aggregates also means missing pricing; never describe it as free. */
export const estimatedCostText = (value: number | string | null | undefined): string => {
  const amount = Number(value);
  return Number.isFinite(amount) && amount > 0
    ? `¥${amount.toFixed(6)}（估算）`
    : "未计价或无可确认费用";
};
