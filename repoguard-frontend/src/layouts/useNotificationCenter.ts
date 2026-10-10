import { computed, getCurrentScope, onScopeDispose, ref, watch } from "vue";
import { fetchNotificationReadKeys, fetchNotifications, markNotificationRead as markReadOnServer } from "@/api/notifications";
import { hasAuthToken } from "@/api/client";
import { enterpriseEditionEnabled } from "@/config/edition";
import { currentUser } from "@/stores/authState";
import { activeTenant } from "@/stores/tenantContext";
import { createLatestPolicyHistoryLoader } from "@/features/rule-config/latestPolicyHistoryLoader";
import { pruneReadNotificationIds } from "@/layouts/notificationReadState";
import type { NotificationCenter } from "@/types";
import { getErrorMessage } from "@/utils/errors";

const CACHE_PREFIX = "repoguard-read-notifications:";
const MAX_CACHE_IDS = 1000;
const validKey = (id: unknown): id is string => typeof id === "string" && id.length > 0 && id.length <= 200;

export const useNotificationCenter = ({ enabled = enterpriseEditionEnabled }: { enabled?: boolean } = {}) => {
  const notificationCenter = ref<NotificationCenter>(); const loadingNotifications = ref(false); const loadingReadKeys = ref(false);
  const notificationCurrent = ref(false); const notificationError = ref(""); const readKeysError = ref(""); const storageError = ref("");
  const readNotificationIds = ref(new Set<string>()); const confirmedReadIds = ref(new Set<string>());
  const readSyncErrors = ref<Record<string, string>>({}); const bulkNotice = ref("");
  const pendingKeys = ref(new Set<string>()); const bulkOwners = ref(new Set<string>());
  const notifications = computed(() => notificationCenter.value?.items ?? []);
  const canRead = () => enabled && !disposed && hasAuthToken() && !!currentUser.value && Number.isSafeInteger(currentUser.value.id) && currentUser.value.id > 0 && currentUser.value.status === "ACTIVE";
  const identity = () => JSON.stringify([currentUser.value?.id, activeTenant.value, currentUser.value?.role]);
  const cacheKey = () => CACHE_PREFIX + JSON.stringify([currentUser.value?.id, activeTenant.value || "personal"]);
  const writeKey = (id: string) => JSON.stringify([identity(), id]);
  const pendingReadIds = computed(() => new Set(notifications.value.filter(item => pendingKeys.value.has(writeKey(item.id))).map(item => item.id)));
  const bulkSaving = computed(() => bulkOwners.value.has(identity()));
  const unreadCount = computed(() => notifications.value.filter(item => !readNotificationIds.value.has(item.id)).length);
  const unsyncedCount = computed(() => notifications.value.filter(item => readNotificationIds.value.has(item.id) && !confirmedReadIds.value.has(item.id)).length);
  const isNotificationRead = (id: string) => readNotificationIds.value.has(id);
  const isReadConfirmed = (id: string) => confirmedReadIds.value.has(id);
  const currentNotification = (id: string) => canRead() && notificationCurrent.value ? notifications.value.find(item => item.id === id) : undefined;
  const listLoader = createLatestPolicyHistoryLoader(value => { loadingNotifications.value = value; });
  const keysLoader = createLatestPolicyHistoryLoader(value => { loadingReadKeys.value = value; });
  let disposed = false; let revision = 0;
  const current = (version: number) => canRead() && version === revision;
  const persist = () => {
    if (!canRead()) return;
    try { window.localStorage.setItem(cacheKey(), JSON.stringify([...readNotificationIds.value].slice(-MAX_CACHE_IDS))); storageError.value = ""; }
    catch { storageError.value = "本地已读缓存不可用，服务器同步仍会继续。"; }
  };
  const loadCache = () => {
    try {
      const raw = window.localStorage.getItem(cacheKey());
      if (!raw) return;
      if (raw.length > 210000) throw new Error("cache too large");
      const values: unknown = JSON.parse(raw);
      if (!Array.isArray(values) || values.length > MAX_CACHE_IDS || !values.every(validKey)) throw new Error("invalid cache");
      readNotificationIds.value = new Set(values);
    } catch { storageError.value = "本地已读缓存无法读取，将以服务端记录为准。"; }
  };
  const prune = () => {
    const ids = notifications.value.map(item => item.id);
    readNotificationIds.value = pruneReadNotificationIds(readNotificationIds.value, ids);
    confirmedReadIds.value = pruneReadNotificationIds(confirmedReadIds.value, ids);
    readSyncErrors.value = Object.fromEntries(Object.entries(readSyncErrors.value).filter(([id]) => ids.includes(id)));
    persist();
  };
  const loadNotifications = async (options: { force?: boolean } = {}) => {
    if (!canRead() || (!options.force && (notificationCurrent.value || loadingNotifications.value))) return false;
    const version = revision; notificationCurrent.value = false; notificationError.value = "";
    try {
      const accepted = await listLoader.load(signal => fetchNotifications({ signal }), center => {
        if (!current(version)) return;
        notificationCenter.value = center; notificationCurrent.value = true; prune();
      });
      return accepted && current(version);
    } catch (error) {
      if (current(version)) notificationError.value = getErrorMessage(error, "通知加载失败，请重试。");
      return false;
    }
  };
  const loadServerReadNotificationIds = async () => {
    if (!canRead()) return false;
    const version = revision; readKeysError.value = "";
    try {
      const accepted = await keysLoader.load(signal => fetchNotificationReadKeys({ signal }), ids => {
        if (!current(version)) return;
        // Merge so an older read cannot erase a write confirmed while that read was in flight.
        ids.filter(validKey).forEach(id => { confirmedReadIds.value.add(id); readNotificationIds.value.add(id); delete readSyncErrors.value[id]; });
        if (notificationCurrent.value) prune(); else persist();
      });
      return accepted && current(version);
    } catch (error) {
      if (current(version)) readKeysError.value = getErrorMessage(error, "服务端已读记录读取失败，当前仅保留本地状态。");
      return false;
    }
  };
  const markNotificationRead = async (id: string) => {
    if (!validKey(id) || !currentNotification(id) || confirmedReadIds.value.has(id)) return false;
    const key = writeKey(id); if (pendingKeys.value.has(key)) return false;
    const version = revision; const owner = identity(); pendingKeys.value.add(key);
    readNotificationIds.value.add(id); delete readSyncErrors.value[id]; bulkNotice.value = ""; persist();
    try {
      await markReadOnServer({ notificationKey: id });
      if (!current(version)) return false;
      confirmedReadIds.value.add(id);
      if (notifications.value.some(item => item.id === id)) { readNotificationIds.value.add(id); delete readSyncErrors.value[id]; persist(); }
      return true;
    } catch (error) {
      if (current(version) && notifications.value.some(item => item.id === id)) readSyncErrors.value[id] = getErrorMessage(error, "已在本地标为已读，服务端同步失败，请手动重试。");
      return false;
    } finally {
      pendingKeys.value.delete(key);
      if (!disposed && version !== revision && owner === identity()) {
        // Returning to the original account needs a fresh authoritative read after the old write settles.
        notificationCurrent.value = false; readKeysError.value = "之前的已读请求已结束，请刷新通知和已读记录确认当前状态。";
      }
    }
  };
  const markAllRead = async () => {
    if (!canRead() || !notificationCurrent.value || bulkSaving.value) return;
    const owner = identity(); const version = revision; bulkOwners.value.add(owner); bulkNotice.value = "";
    const ids = notifications.value.filter(item => !confirmedReadIds.value.has(item.id)).map(item => item.id);
    try {
      // Sequential writes bound concurrency and never automatically retry a failed POST.
      for (const id of ids) {
        if (!current(version) || !notificationCurrent.value) return;
        await markNotificationRead(id);
      }
      if (current(version)) {
        const remaining = notifications.value.filter(item => !confirmedReadIds.value.has(item.id)).length;
        bulkNotice.value = remaining ? `${remaining} 条通知的已读状态尚未同步到服务端，请重试同步。` : "当前通知的已读状态已同步到服务端。";
      }
    } finally { bulkOwners.value.delete(owner); }
  };
  const refreshNotifications = async () => { await Promise.all([loadNotifications({ force: true }), loadServerReadNotificationIds()]); };
  const clearContext = () => {
    revision += 1; listLoader.cancel(); keysLoader.cancel(); notificationCenter.value = undefined; notificationCurrent.value = false;
    notificationError.value = ""; readKeysError.value = ""; storageError.value = ""; readSyncErrors.value = {}; bulkNotice.value = "";
    readNotificationIds.value = new Set(); confirmedReadIds.value = new Set();
    if (canRead()) { loadCache(); void loadServerReadNotificationIds(); }
  };
  watch([() => currentUser.value?.id, () => currentUser.value?.role, () => currentUser.value?.status, activeTenant], clearContext, { immediate: true, flush: "sync" });
  if (getCurrentScope()) onScopeDispose(() => { disposed = true; clearContext(); });
  return { notificationCenter, notifications, loadingNotifications, loadingReadKeys, notificationCurrent, notificationError, readKeysError, storageError,
    readSyncErrors, bulkNotice, pendingReadIds, bulkSaving, unreadCount, unsyncedCount, isNotificationRead, isReadConfirmed, currentNotification,
    loadNotifications, loadServerReadNotificationIds, refreshNotifications, markNotificationRead, markAllRead };
};
