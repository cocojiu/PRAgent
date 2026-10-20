type StorageKind = "local" | "session";
export type StorageResult<T> = { available: true; value: T } | { available: false };

// Accessing the storage property itself can throw, before a Storage method runs.
const access = <T>(kind: StorageKind, operation: (storage: Storage) => T): StorageResult<T> => {
  try {
    if (typeof window === "undefined") return { available: false };
    const storage = kind === "local" ? window.localStorage : window.sessionStorage;
    return { available: true, value: operation(storage) };
  } catch {
    return { available: false };
  }
};

// No fallback or credential cache here: each caller owns its non-secret fallback.
export const browserStorage = {
  get: (kind: StorageKind, key: string) => access(kind, storage => storage.getItem(key)),
  set: (kind: StorageKind, key: string, value: string) => access(kind, storage => storage.setItem(key, value)),
  remove: (kind: StorageKind, key: string) => access(kind, storage => storage.removeItem(key))
};
