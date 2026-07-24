import { Tab, Tabs } from "@mui/material";
import { useLocation, useNavigate } from "react-router-dom";

export function IrbArchiveTabs() {
  const location = useLocation();
  const navigate = useNavigate();

  const value = location.pathname.startsWith("/irb/history")
    ? "/protocols"
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
        label="Current IRB Workspaces"
      />
      <Tab
        value="/protocols"
        label="Protocol Archive"
      />
    </Tabs>
  );
}
