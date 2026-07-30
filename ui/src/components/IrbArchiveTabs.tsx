import { Tab, Tabs } from "@mui/material";

export function IrbArchiveTabs() {
  return (
    <Tabs value="/irb" variant="scrollable" scrollButtons="auto">
      <Tab
        value="/irb"
        label="Current IRB Workspaces"
      />
    </Tabs>
  );
}
