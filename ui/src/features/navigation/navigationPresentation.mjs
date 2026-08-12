// Sidebar navigation config - kept as plain data (not JSX) so it can be
// unit-tested the same way every other presentation-helper module in
// this project is (see ../dashboard/dashboardPresentation.mjs), since
// there is no component-render test setup. AppLayout.tsx imports this
// array directly and maps icons on by `key` (icons are JSX, so they
// can't live in a plain-data module - same pattern DashboardPage.tsx
// uses for its dashboard cards).
//
// "Historical Awards" sits immediately below "Awards" on purpose: users
// searching for an internal Award ID land on the family/current-record
// Awards page first (it's above it in the list) and need the
// version-level explorer to be the very next, obvious thing to try.
export const sidebarNavigationItems = [
  { key: "dashboard", label: "Dashboard", path: "/" },
  { key: "awards", label: "Awards", path: "/awards/search" },
  {
    key: "historicalAwards",
    label: "Historical Awards",
    path: "/awards/versions/search",
  },
  { key: "proposals", label: "Proposals", path: "/proposals" },
  { key: "negotiations", label: "Negotiations", path: "/negotiations" },
  { key: "subawards", label: "Subawards", path: "/subawards" },
  { key: "globalSearch", label: "Global Search", path: "/search" },
];

// Mirrors react-router NavLink's own default (non-"end") active-match
// semantics: exact match, or a path-*segment* prefix - never a raw
// string prefix. This is what guarantees "/awards/search" is never
// considered active for "/awards/versions/search" (and vice versa)
// just because one string happens to start with the other's characters.
export function isNavItemActive(navPath, pathname) {
  if (pathname === navPath) {
    return true;
  }
  const withTrailingSlash = navPath.endsWith("/") ? navPath : `${navPath}/`;
  return pathname.startsWith(withTrailingSlash);
}
