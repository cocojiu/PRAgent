import { reactive, ref } from "vue";
import { ElMessage } from "element-plus/es/components/message/index.mjs";
import {
  fetchNotificationDeliveries,
  fetchNotificationEvents,
  retryNotificationEvent
} from "@/api/config";
import type { NotificationDelivery, NotificationEvent } from "@/types";
import { getErrorMessage } from "@/utils/errors";

type NotificationRecordFilter = {
  page: number;
  pageSize: number;
  status?: string;
  taskId?: number;
};

type UseNotificationOpsRecordsOptions = {
  loadNotificationBindings: () => Promise<void>;
};

export const useNotificationOpsRecords = ({ loadNotificationBindings }: UseNotificationOpsRecordsOptions) => {
  const eventsLoading = ref(false);
  const deliveriesLoading = ref(false);
  const notificationEvents = ref<NotificationEvent[]>([]);
  const notificationDeliveries = ref<NotificationDelivery[]>([]);
  const notificationEventTotal = ref(0);
  const notificationDeliveryTotal = ref(0);
  const retryingEventId = ref<number>();
  const eventFilter = reactive<NotificationRecordFilter>({
    page: 1,
    pageSize: 10
  });
  const deliveryFilter = reactive<NotificationRecordFilter>({
    page: 1,
    pageSize: 10
  });

  const loadNotificationEvents = async () => {
    eventsLoading.value = true;
    try {
      const result = await fetchNotificationEvents({
        page: eventFilter.page,
        pageSize: eventFilter.pageSize,
        status: eventFilter.status,
        taskId: eventFilter.taskId
      });
      notificationEvents.value = result.items;
      notificationEventTotal.value = result.total;
    } catch (error) {
      ElMessage.error(getErrorMessage(error, "通知事件加载失败"));
    } finally {
      eventsLoading.value = false;
    }
  };

  const loadNotificationDeliveries = async () => {
    deliveriesLoading.value = true;
    try {
      const result = await fetchNotificationDeliveries({
        page: deliveryFilter.page,
        pageSize: deliveryFilter.pageSize,
        status: deliveryFilter.status,
        taskId: deliveryFilter.taskId
      });
      notificationDeliveries.value = result.items;
      notificationDeliveryTotal.value = result.total;
    } catch (error) {
      ElMessage.error(getErrorMessage(error, "投递记录加载失败"));
    } finally {
      deliveriesLoading.value = false;
    }
  };

  const refreshNotificationData = async () => {
    await Promise.all([loadNotificationEvents(), loadNotificationDeliveries(), loadNotificationBindings()]);
  };

  const retryEvent = async (id: number) => {
    if (retryingEventId.value) {
      return;
    }
    retryingEventId.value = id;
    try {
      await retryNotificationEvent(id);
      ElMessage.success("通知事件已重新入队");
      await refreshNotificationData();
    } catch (error) {
      ElMessage.error(getErrorMessage(error, "通知事件重试失败"));
    } finally {
      retryingEventId.value = undefined;
    }
  };

  return {
    deliveriesLoading,
    deliveryFilter,
    eventFilter,
    eventsLoading,
    notificationDeliveries,
    notificationDeliveryTotal,
    notificationEvents,
    notificationEventTotal,
    retryingEventId,
    loadNotificationDeliveries,
    loadNotificationEvents,
    refreshNotificationData,
    retryEvent
  };
};
