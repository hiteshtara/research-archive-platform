// Dashboard card navigation config - kept as plain data (not JSX) so it
// can be unit-tested the same way every other presentation-helper module
// in this project is, since there is no component-render test setup.
// DashboardPage.tsx imports these arrays directly rather than declaring
// its own inline versions.

// IRB is outside current implementation scope (see
// docs/DECISIONS.md) - no card here routes anywhere near /irb. Award
// and Proposal are the only domains with both a current and a
// historical card; DashboardPage.tsx renders these two arrays together
// under a single "Awards and Proposals" heading (not two separate
// sections) so removing IRB's three historical-activity cards doesn't
// leave a visibly sparse section.
export const primaryBusinessCards = [
  {
    key: "awards",
    title: "Awards",
    description: "One current record per institutional Award number",
    path: "/awards/search",
  },
  {
    key: "proposals",
    title: "Proposals",
    description: "Distinct institutional proposal numbers",
    path: "/proposals",
  },
];

export const historicalActivityCards = [
  {
    key: "awardHistoryRecords",
    title: "Historical Award Records",
    description: "All archived Award versions",
    path: "/awards/versions/search",
  },
  {
    key: "proposalHistoryRecords",
    title: "Historical Proposal Records",
    description: "All preserved Kuali Proposal history rows",
    path: "/proposals",
  },
];

export const futureModuleCards = [
  {
    key: "negotiations",
    title: "Negotiations",
    description: "Agreement and negotiation records",
    path: "/negotiations",
  },
  {
    key: "subawards",
    title: "Subawards",
    description: "Distinct institutional subaward codes",
    path: "/subawards",
  },
  {
    key: "documents",
    title: "Kuali Documents",
    description:
      "Archived workflow and business documents across all modules",
    path: "/documents",
  },
];
