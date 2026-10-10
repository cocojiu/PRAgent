import { computed, reactive, ref } from "vue";
import type { IntegrationConfig, IntegrationField } from "@/types";
import { cloneIntegrationItems, defaultIntegrationItems } from "../integrationDefaults";
import type { IntegrationFormState } from "../integrationPayloadBuilders";

const formValues = (fields: Pick<IntegrationField, "label" | "value">[]) =>
  Object.fromEntries(fields.map((field) => [field.label, field.value]));

const createDefaultFormState = (): IntegrationFormState =>
  Object.fromEntries(defaultIntegrationItems.map((item) => [item.id, formValues(item.fields)]));

export const useIntegrationFormState = () => {
  const integrationItems = ref(cloneIntegrationItems());
  const formState = reactive<IntegrationFormState>(createDefaultFormState());
  const savedFormState = reactive<IntegrationFormState>(createDefaultFormState());
  const visibleSecrets = reactive<Record<string, boolean>>({});

  const captureForm = (id: string) => ({ ...formState[id] });
  const captureSavedForm = (id: string) => ({ ...savedFormState[id] });
  const unsavedChanges = computed(() => Object.fromEntries(integrationItems.value.map(item => [item.id,
    Object.entries(formState[item.id] ?? {}).some(([label, value]) => value !== savedFormState[item.id]?.[label])
  ])));

  const applyIntegrationPatch = (id: string, patch: Partial<IntegrationConfig>, baseline?: Readonly<Record<string, string>>) => {
    const item = integrationItems.value.find((integration) => integration.id === id);
    if (!item) {
      return;
    }
    Object.assign(item, patch);
    if (patch.fields) {
      const received = formValues(patch.fields);
      const merged = { ...received };
      if (baseline) {
        for (const [label, value] of Object.entries(formState[id] ?? {})) {
          if (value !== baseline[label]) merged[label] = value;
        }
      }
      savedFormState[id] = received;
      formState[id] = merged;
    }
  };

  return {
    formState,
    integrationItems,
    visibleSecrets,
    captureForm,
    captureSavedForm,
    unsavedChanges,
    applyIntegrationPatch
  };
};
