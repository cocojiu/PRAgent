import { reactive, ref } from "vue";
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
  const visibleSecrets = reactive<Record<string, boolean>>({});

  const applyIntegrationPatch = (id: string, patch: Partial<IntegrationConfig>) => {
    const item = integrationItems.value.find((integration) => integration.id === id);
    if (!item) {
      return;
    }
    Object.assign(item, patch);
    if (patch.fields) {
      formState[id] = formValues(patch.fields);
    }
  };

  return {
    formState,
    integrationItems,
    visibleSecrets,
    applyIntegrationPatch
  };
};
