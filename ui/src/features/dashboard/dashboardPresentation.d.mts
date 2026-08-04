export interface DashboardCardConfig {
  key: string;
  title: string;
  description: string;
  path: string;
}

export const primaryBusinessCards: DashboardCardConfig[];
export const historicalActivityCards: DashboardCardConfig[];
export const futureModuleCards: DashboardCardConfig[];
