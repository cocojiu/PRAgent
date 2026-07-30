export const pruneReadNotificationIds = (
  readIds: ReadonlySet<string>,
  latestIds: Iterable<string>
): Set<string> => {
  const latestIdSet = new Set(latestIds);
  return new Set([...readIds].filter((id) => latestIdSet.has(id)));
};
