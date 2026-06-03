import { computed, type ComputedRef, type Ref, watch } from "vue";

interface FilterPaginationOptions<T> {
  source: ComputedRef<T[]> | Ref<T[]>;
  filters: Ref<unknown>[];
  currentPage: Ref<number>;
  pageSize: Ref<number>;
}

export const useFilterPagination = <T>({
  source,
  filters,
  currentPage,
  pageSize
}: FilterPaginationOptions<T>) => {
  const pagedItems = computed(() => {
    const start = (currentPage.value - 1) * pageSize.value;
    return source.value.slice(start, start + pageSize.value);
  });

  watch(filters, () => {
    currentPage.value = 1;
  });

  watch([source, pageSize], () => {
    const maxPage = Math.max(1, Math.ceil(source.value.length / pageSize.value));
    if (currentPage.value > maxPage) {
      currentPage.value = maxPage;
    }
  });

  const resetPage = () => {
    currentPage.value = 1;
  };

  return {
    pagedItems,
    resetPage
  };
};
