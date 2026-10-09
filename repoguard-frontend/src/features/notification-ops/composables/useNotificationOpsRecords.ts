import { getCurrentScope, onScopeDispose, reactive, ref, shallowRef, watch, type Ref } from "vue";
import { ElMessage } from "element-plus/es/components/message/index.mjs";
import { fetchNotificationDeliveries, fetchNotificationEvents, retryNotificationEvent } from "@/api/config";
import type { ApiRequestOptions } from "@/api/contracts";
import type { NotificationDelivery, NotificationEvent } from "@/types";
import { getErrorMessage } from "@/utils/errors";
import { activeTenant } from "@/stores/tenantContext";
import { currentUser } from "@/stores/authState";
import { canRetryNotificationEvent } from "../notificationOpsDisplayMappers";

type NotificationRecordFilter = { page: number; pageSize: number; status?: string; taskId?: number };
type UseNotificationOpsRecordsOptions = { canManage: Ref<boolean>; loadNotificationBindings: () => Promise<void> };

export const useNotificationOpsRecords = ({ canManage, loadNotificationBindings }: UseNotificationOpsRecordsOptions) => {
  const retryingEventId = ref<number>();
  let disposed = false;
  let revision = 0;
  const createList = <T>(request: (filter: NotificationRecordFilter, options?: ApiRequestOptions) => Promise<{ items: T[]; total: number }>, fallback: string) => {
    const filter = reactive<NotificationRecordFilter>({ page: 1, pageSize: 10 });
    const rows = shallowRef<T[]>([]);
    const total = ref(0); const loading = ref(false); const error = ref(""); const needsRefresh = ref(false);
    let controller: AbortController | undefined;
    const invalidate = (clear = true) => {
      controller?.abort(); controller = undefined; loading.value = false; needsRefresh.value = true;
      if (clear) { rows.value = []; total.value = 0; error.value = ""; }
    };
    watch([() => filter.page, () => filter.pageSize, () => filter.status, () => filter.taskId], () => invalidate(), { flush: "sync" });
    const load = async () => {
      if (disposed || !canManage.value) return;
      invalidate(false); error.value = "";
      const currentController = new AbortController(); controller = currentController;
      const version = revision;
      const current = () => !disposed && canManage.value && version === revision
        && controller === currentController && !currentController.signal.aborted;
      loading.value = true;
      try {
        const result = await request({ ...filter }, { signal: currentController.signal });
        if (current()) { rows.value = result.items; total.value = result.total; needsRefresh.value = false; }
      } catch (reason) {
        if (current()) error.value = getErrorMessage(reason, fallback);
      } finally {
        if (current()) loading.value = false;
      }
    };
    return { filter, rows, total, loading, error, needsRefresh, load, invalidate };
  };
  const events = createList<NotificationEvent>(fetchNotificationEvents, "通知事件加载失败");
  const deliveries = createList<NotificationDelivery>(fetchNotificationDeliveries, "投递记录加载失败");
  const invalidateContext = () => { revision += 1; events.invalidate(); deliveries.invalidate(); };
  watch([() => canManage.value, () => activeTenant.value, () => currentUser.value?.id], invalidateContext, { flush: "sync" });
  if (getCurrentScope()) onScopeDispose(() => { disposed = true; invalidateContext(); });

  const refreshNotificationData = async () => {
    if (disposed || !canManage.value) return;
    const version = revision;
    const outcomes = await Promise.allSettled([events.load(), deliveries.load(), Promise.resolve().then(loadNotificationBindings)]);
    if (!disposed && version === revision && canManage.value && outcomes[2]?.status === "rejected") {
      ElMessage.warning(`渠道绑定刷新失败：${getErrorMessage(outcomes[2].reason)}`);
    }
  };
  const canRetryEvent = (id: number) => !disposed && canManage.value && retryingEventId.value === undefined
    && !events.loading.value && !events.needsRefresh.value && !events.error.value
    && events.rows.value.some(event => event.id === id && canRetryNotificationEvent(event.status));
  const retryEvent = async (id: number) => {
    if (!canRetryEvent(id)) return;
    retryingEventId.value = id; events.invalidate(false);
    const version = revision;
    const current = () => !disposed && version === revision && canManage.value;
    try {
      await retryNotificationEvent(id);
      if (!current()) return;
      ElMessage.success("通知事件已重新入队");
      await refreshNotificationData();
    } catch (error) {
      if (current()) { events.invalidate(false); ElMessage.error(getErrorMessage(error, "通知事件重试失败")); }
    } finally {
      retryingEventId.value = undefined;
    }
  };
  return {
    deliveriesLoading: deliveries.loading, deliveryFilter: deliveries.filter,
    eventFilter: events.filter, eventsLoading: events.loading,
    notificationDeliveries: deliveries.rows, notificationDeliveryTotal: deliveries.total,
    notificationEvents: events.rows, notificationEventTotal: events.total,
    eventsError: events.error, deliveriesError: deliveries.error,
    eventsNeedsRefresh: events.needsRefresh, deliveriesNeedsRefresh: deliveries.needsRefresh,
    retryingEventId, canRetryEvent, loadNotificationDeliveries: deliveries.load,
    loadNotificationEvents: events.load, refreshNotificationData, retryEvent
  };
};
