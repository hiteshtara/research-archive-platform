export interface SidebarNavigationItem {
  key: string;
  label: string;
  path: string;
}

export const sidebarNavigationItems: SidebarNavigationItem[];

export function isNavItemActive(navPath: string, pathname: string): boolean;
