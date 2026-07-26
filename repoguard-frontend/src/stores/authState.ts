import { computed, ref } from "vue";
import { getCurrentUser } from "@/api/auth";
import type { CurrentUser } from "@/api/auth";

export const currentUser = ref<CurrentUser>();
export const canManage = computed(() => currentUser.value?.role === "ADMIN");

let pendingLoad: Promise<CurrentUser> | undefined;

export const loadCurrentUser = () => {
  if (!pendingLoad) {
    pendingLoad = getCurrentUser()
      .then((user) => {
        currentUser.value = user;
        return user;
      })
      .finally(() => {
        pendingLoad = undefined;
      });
  }
  return pendingLoad;
};

export const resetCurrentUser = () => {
  currentUser.value = undefined;
};
