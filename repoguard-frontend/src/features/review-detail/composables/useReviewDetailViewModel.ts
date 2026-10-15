import { useReviewDetailSession } from "./useReviewDetailSession";
import { useReviewDetailActions } from "./useReviewDetailActions";

/** Public page facade. Session ownership and domain commands remain separately testable. */
export const useReviewDetailViewModel = (options: { getTaskId: () => number }) => {
  const session = useReviewDetailSession(options);
  const actions = useReviewDetailActions(session, options.getTaskId);
  return { ...session, ...actions };
};
