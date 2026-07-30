import { computed, ref } from "vue";
import { getCurrentUser } from "@/api/auth";
import type { CurrentUser } from "@/api/auth";

export const currentUser = ref<CurrentUser>();
export const canManage = computed(() => currentUser.value?.role === "ADMIN");

type PendingCurrentUserLoad = {
  controller: AbortController;
  epoch: number;
  promise: Promise<CurrentUser>;
};

let authEpoch = 0;
let pendingLoad: PendingCurrentUserLoad | undefined;

export const loadCurrentUser = () => {
  if (!pendingLoad || pendingLoad.epoch !== authEpoch) {
    const epoch = authEpoch;
    const controller = new AbortController();
    const promise = getCurrentUser({ signal: controller.signal })
      .then((user) => {
        if (authEpoch === epoch && !controller.signal.aborted) {
          currentUser.value = user;
        }
        return user;
      })
      .finally(() => {
        if (pendingLoad?.promise === promise) {
          pendingLoad = undefined;
        }
      });
    pendingLoad = { controller, epoch, promise };
  }
  return pendingLoad.promise;
};

export const resetCurrentUser = () => {
  authEpoch++;
  pendingLoad?.controller.abort();
  pendingLoad = undefined;
  currentUser.value = undefined;
};
