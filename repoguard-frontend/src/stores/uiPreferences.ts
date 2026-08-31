import { setDateTimePreferences } from "@/utils/dateTime";

export const applyUiPreferences = (timezone: string) => {
  setDateTimePreferences(timezone);
};
