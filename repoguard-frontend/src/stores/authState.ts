import { computed, ref } from "vue";
import { getCurrentUser } from "@/api/auth";
import type { CurrentUser } from "@/api/auth";

export const currentUser = ref<CurrentUser>();
export const canManage = computed(() => currentUser.value?.role === "ADMIN");

export const loadCurrentUser = async () => {
  currentUser.value = await getCurrentUser();
  return currentUser.value;
};

export const resetCurrentUser = () => {
  currentUser.value = undefined;
};
