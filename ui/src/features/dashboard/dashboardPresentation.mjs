// Dashboard card navigation config - kept as plain data (not JSX) so it
// can be unit-tested the same way every other presentation-helper module
// in this project is, since there is no component-render test setup.
// DashboardPage.tsx imports these arrays directly rather than declaring
// its own inline versions.

export const primaryBusinessCards = [
  {
    key: "irb",
    title: "Current IRB Records",
    description: "Current curated IRB records available for search",
    path: "/irb",
  },
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
    path: "/awards/search",
  },
  {
    key: "proposalHistoryRecords",
    title: "Historical Proposal Records",
    description: "All preserved Kuali Proposal history rows",
    path: "/proposals",
  },
  {
    key: "submissions",
    title: "Submissions",
    description: "Initial applications, amendments, renewals and other submissions",
    path: "/irb",
  },
  {
    key: "fundingRecords",
    title: "Funding Relationships",
    description: "Archived IRB funding source relationships",
    path: "/irb",
  },
  {
    key: "timelineEvents",
    title: "Timeline Events",
    description: "Historical workflow and review events",
    path: "/irb",
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
    title: "Documents",
    description: "Legacy files and attachments",
    path: "/documents",
  },
];
