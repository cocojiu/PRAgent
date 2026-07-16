export const createLatestOnlyLoader = <T>(apply: (value: T) => void) => {
  let requestSequence = 0;

  const load = async (request: () => Promise<T>): Promise<boolean> => {
    const sequence = ++requestSequence;
    try {
      const value = await request();
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
    }
  };

  const cancel = () => {
    requestSequence += 1;
  };

  return { cancel, load };
};
