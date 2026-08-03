import {
  Box,
  CircularProgress,
  Divider,
  List,
  ListItemButton,
  ListItemText,
  Stack,
  Typography,
} from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";

import { getAwardHierarchyV1, getAwardSummaryV1 } from "../../api/client";
import { AwardAmountsSection } from "../../components/award/AwardAmountsSection";
import { AwardAttachmentsSection } from "../../components/award/AwardAttachmentsSection";
import { AwardBreadcrumb } from "../../components/award/AwardBreadcrumb";
import { AwardCommentsSection } from "../../components/award/AwardCommentsSection";
import { AwardContactsSection } from "../../components/award/AwardContactsSection";
import { AwardSapTransmissionsSection } from "../../components/award/AwardSapTransmissionsSection";
import { AwardStatusPill } from "../../components/award/AwardStatusPill";
import { AwardSummarySection } from "../../components/award/AwardSummarySection";
import { AwardTermsSection } from "../../components/award/AwardTermsSection";
import { AwardTimeAndMoneySection } from "../../components/award/AwardTimeAndMoneySection";
import { AwardVersionsSection } from "../../components/award/AwardVersionsSection";
import { ComingSoonSection } from "../../components/award/ComingSoonSection";
import { flattenHierarchyNodes } from "../../components/award/hierarchyUtils";
import type { AwardHierarchyNode } from "../../types/api";

const SECTIONS = [
  { key: "summary", label: "Summary" },
  { key: "versions", label: "Versions" },
  { key: "people", label: "People and Units" },
  { key: "amounts", label: "Amounts" },
  { key: "budget", label: "Budget" },
  { key: "timeMoney", label: "Time & Money" },
  { key: "terms", label: "Terms" },
  { key: "comments", label: "Comments and Notepad" },
  { key: "sap", label: "SAP Transmission History" },
  { key: "attachments", label: "Attachments" },
] as const;

type SectionKey = (typeof SECTIONS)[number]["key"];

const IMPLEMENTED_SECTIONS = new Set<SectionKey>([
  "summary",
  "versions",
  "people",
  "amounts",
  "timeMoney",
  "terms",
  "comments",
  "sap",
  "attachments",
]);

// Award Dashboard - the final stop in Search -> Search Results ->
// Award Hierarchy -> Award Dashboard. Tab switches are local state (no
// route change), matching the approved mockup's in-page section
// switcher - "no page reloads" between sections of the same Award.
export function AwardDashboardPage() {
  const { awardId: awardIdParameter } = useParams<{ awardId: string }>();
  const awardId = Number(awardIdParameter);
  const navigate = useNavigate();

  const [activeSection, setActiveSection] = useState<SectionKey>("summary");

  const summaryQuery = useQuery({
    queryKey: ["award-summary-v1", awardId],
    queryFn: ({ signal }) => getAwardSummaryV1(awardId, signal),
    enabled: Number.isFinite(awardId),
  });

  const awardNumber = summaryQuery.data?.awardNumber;

  const hierarchyQuery = useQuery({
    queryKey: ["award-hierarchy-v1", awardNumber],
    queryFn: ({ signal }) => getAwardHierarchyV1(awardNumber ?? "", signal),
    enabled: Boolean(awardNumber),
  });

  const nodesByAwardNumber = useMemo(() => {
    if (!hierarchyQuery.data) {
      return new Map<string, AwardHierarchyNode>();
    }

    return flattenHierarchyNodes(hierarchyQuery.data.root);
  }, [hierarchyQuery.data]);

  function openAward(node: AwardHierarchyNode) {
    if (node.awardId === null) {
      return;
    }

    setActiveSection("summary");
    navigate(`/awards/${node.awardId}`);
  }

  if (!Number.isFinite(awardId)) {
    return <Typography color="error">Invalid Award.</Typography>;
  }

  if (summaryQuery.isLoading) {
    return (
      <Box sx={{ display: "grid", placeItems: "center", py: 8 }}>
        <CircularProgress />
      </Box>
    );
  }

  if (summaryQuery.isError || !summaryQuery.data) {
    return <Typography color="error">Award not found.</Typography>;
  }

  const summary = summaryQuery.data;

  return (
    <Stack spacing={3}>
      {hierarchyQuery.data && (
        <AwardBreadcrumb
          path={hierarchyQuery.data.selectedAwardPath}
          nodesByAwardNumber={nodesByAwardNumber}
          onNavigate={openAward}
        />
      )}

      <Box
        sx={{
          display: "flex",
          justifyContent: "space-between",
          alignItems: "flex-start",
          pb: 2.25,
          borderBottom: "1px solid",
          borderColor: "divider",
        }}
      >
        <Box>
          <Typography sx={{ fontSize: 20, fontWeight: 700 }}>
            {summary.awardNumber}
          </Typography>

          <Typography
            variant="body2"
            color="text.secondary"
            sx={{ mt: 0.5, maxWidth: 640 }}
          >
            {summary.title ?? "Untitled award"}
          </Typography>

          <Typography
            variant="caption"
            color="text.disabled"
            sx={{ display: "block", mt: 0.75, fontFamily: "monospace" }}
          >
            award_id {summary.awardId} &middot; sequence{" "}
            {summary.sequenceNumber}
          </Typography>
        </Box>

        <AwardStatusPill status={summary.status} />
      </Box>

      <Box sx={{ display: "flex", gap: 3, alignItems: "flex-start" }}>
        <List
          sx={{
            width: 220,
            flexShrink: 0,
            border: "1px solid",
            borderColor: "divider",
            borderRadius: 2,
            p: 1,
          }}
        >
          {SECTIONS.map((section) => (
            <ListItemButton
              key={section.key}
              selected={activeSection === section.key}
              onClick={() => setActiveSection(section.key)}
              sx={{
                borderRadius: 1.5,
                mb: 0.25,
                "&.Mui-selected": {
                  backgroundColor: "rgba(139, 24, 50, 0.10)",
                  color: "primary.main",
                },
              }}
            >
              <ListItemText
                primary={section.label}
                slotProps={{
                  primary: { sx: { fontSize: 13, fontWeight: 600 } },
                }}
              />
            </ListItemButton>
          ))}
        </List>

        <Box
          sx={{
            flex: 1,
            minWidth: 0,
            border: "1px solid",
            borderColor: "divider",
            borderRadius: 2,
            p: 3.25,
            minHeight: 360,
          }}
        >
          <Divider sx={{ display: "none" }} />

          {activeSection === "summary" && (
            <AwardSummarySection awardId={awardId} />
          )}

          {activeSection === "versions" && (
            <AwardVersionsSection awardId={awardId} />
          )}

          {activeSection === "people" && (
            <AwardContactsSection awardId={awardId} />
          )}

          {activeSection === "amounts" && (
            <AwardAmountsSection awardId={awardId} />
          )}

          {activeSection === "timeMoney" && (
            <AwardTimeAndMoneySection awardId={awardId} />
          )}

          {activeSection === "terms" && (
            <AwardTermsSection awardId={awardId} />
          )}

          {activeSection === "comments" && (
            <AwardCommentsSection awardId={awardId} />
          )}

          {activeSection === "sap" && (
            <AwardSapTransmissionsSection awardId={awardId} />
          )}

          {activeSection === "attachments" && (
            <AwardAttachmentsSection awardId={awardId} />
          )}

          {!IMPLEMENTED_SECTIONS.has(activeSection) && (
            <ComingSoonSection
              label={
                SECTIONS.find((section) => section.key === activeSection)
                  ?.label ?? ""
              }
            />
          )}
        </Box>
      </Box>
    </Stack>
  );
}
