type RequestFactory<T> = (signal: AbortSignal) => Promise<T>;

export const createLatestPolicyHistoryLoader = (setLoading: (loading: boolean) => void) => {
  let activeRequest: { controller: AbortController; sequence: number } | undefined;
  let requestSequence = 0;

  const load = async <T>(request: RequestFactory<T>, apply: (value: T) => void): Promise<boolean> => {
    activeRequest?.controller.abort();
    const current = {
      controller: new AbortController(),
      sequence: ++requestSequence
    };
    activeRequest = current;
    setLoading(true);
    try {
      const value = await request(current.controller.signal);
      if (current.sequence !== requestSequence) {
        return false;
      }
      apply(value);
      return true;
    } catch (error) {
      if (current.sequence !== requestSequence) {
        return false;
      }
      throw error;
    } finally {
      if (activeRequest?.sequence === current.sequence) {
        activeRequest = undefined;
        setLoading(false);
      }
    }
  };

  const cancel = () => {
    activeRequest?.controller.abort();
    activeRequest = undefined;
    requestSequence += 1;
    setLoading(false);
  };

  return { cancel, load };
};
