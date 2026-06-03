import { toRaw } from "vue";

type FormRecord = Record<string, object>;

const cloneForms = <TForms extends FormRecord>(forms: TForms) =>
  JSON.parse(JSON.stringify(toRaw(forms))) as TForms;

export const useFormSnapshot = <TForms extends FormRecord>(forms: TForms) => {
  let snapshot = cloneForms(forms);

  const captureSnapshot = () => {
    snapshot = cloneForms(forms);
  };

  const restoreSnapshot = () => {
    Object.entries(snapshot).forEach(([key, value]) => {
      Object.assign(forms[key], value);
    });
  };

  return {
    captureSnapshot,
    restoreSnapshot
  };
};
