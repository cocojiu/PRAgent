import { ref } from "vue";

export const createLatestOnlyLoader = <T>(apply: (value: T) => void) => {
  let requestSequence = 0;
  let controller: AbortController | undefined;
  let disposed = false;
  const loading = ref(false);

  const load = async (request: (signal: AbortSignal) => Promise<T>): Promise<boolean> => {
    if (disposed) return false;
    const sequence = ++requestSequence;
    controller?.abort();
    controller = new AbortController();
    loading.value = true;
    try {
      const value = await request(controller.signal);
      if (sequence !== requestSequence) {
        return false;
      }
      apply(value);
      return true;
    } catch (error) {
      if (sequence !== requestSequence) {
        return false;
      }
      throw error;
    } finally {
      if (sequence === requestSequence) {
        loading.value = false;
        controller = undefined;
      }
    }
  };

  const cancel = () => {
    requestSequence += 1;
    controller?.abort();
    controller = undefined;
    loading.value = false;
  };
  const dispose = () => { disposed = true; cancel(); };

  return { cancel, dispose, load, loading };
};
