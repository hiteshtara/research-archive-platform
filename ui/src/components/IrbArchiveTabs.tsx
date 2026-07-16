import { Tab, Tabs } from "@mui/material";
import { useLocation, useNavigate } from "react-router-dom";

export function IrbArchiveTabs() {
  const location = useLocation();
  const navigate = useNavigate();

  const value = location.pathname.startsWith("/irb/families")
    ? "/irb/families"
    : location.pathname.startsWith("/irb/history")
      ? "/irb/history"
      : "/irb";

  return (
    <Tabs
      value={value}
      onChange={(_, nextValue: string) => navigate(nextValue)}
      variant="scrollable"
      scrollButtons="auto"
    >
      <Tab
        value="/irb"
        label="Current Workspaces · 1,852"
      />
      <Tab
        value="/irb/families"
        label="Protocol Families · 6,038"
      />
      <Tab
        value="/irb/history"
        label="Historical Versions · 23,891"
      />
    </Tabs>
  );
}
