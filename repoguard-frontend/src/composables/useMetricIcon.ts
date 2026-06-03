import type { Component } from "vue";

export const useMetricIcon = <Color extends string>(
  iconMap: Record<Color, Component>,
  defaultIcon: Component
) => {
  return (color: string) => (color in iconMap ? iconMap[color as Color] : defaultIcon);
};
