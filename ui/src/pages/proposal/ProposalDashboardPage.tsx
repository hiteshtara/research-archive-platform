import { Box, CircularProgress, List, ListItemButton, ListItemText, Stack, Typography } from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { useParams } from "react-router-dom";

import { getProposalSummaryV1 } from "../../api/client";
import { AwardStatusPill } from "../../components/award/AwardStatusPill";
import { ProposalAttachmentsSection } from "../../components/proposal/ProposalAttachmentsSection";
import { ProposalCommentsSection } from "../../components/proposal/ProposalCommentsSection";
import { ProposalFundedAwardsSection } from "../../components/proposal/ProposalFundedAwardsSection";
import { ProposalPeopleUnitsSection } from "../../components/proposal/ProposalPeopleUnitsSection";
import { ProposalSummarySection } from "../../components/proposal/ProposalSummarySection";
import { ProposalVersionsSection } from "../../components/proposal/ProposalVersionsSection";

const SECTIONS = [
  { key: "summary", label: "Summary" },
  { key: "versions", label: "Versions" },
  { key: "fundedAwards", label: "Funded Awards" },
  { key: "attachments", label: "Attachments" },
  { key: "peopleUnits", label: "People and Units" },
  { key: "comments", label: "Comments" },
] as const;

type SectionKey = (typeof SECTIONS)[number]["key"];

// Institutional Proposal Dashboard - keyed by the exact surrogate
// proposalId (one specific version), mirroring AwardDashboardPage's
// own structure. proposalNumber, sequenceNumber, and
// workflowDocumentNumber are shown as distinct identifiers - never
// inferred one from another.
export function ProposalDashboardPage() {
  const { proposalId: proposalIdParameter } = useParams<{
    proposalId: string;
  }>();
  const proposalId = Number(proposalIdParameter);

  const [activeSection, setActiveSection] = useState<SectionKey>("summary");

  const summaryQuery = useQuery({
    queryKey: ["proposal-summary-v1", proposalId],
    queryFn: ({ signal }) => getProposalSummaryV1(proposalId, signal),
    enabled: Number.isFinite(proposalId),
  });

  if (!Number.isFinite(proposalId)) {
    return <Typography color="error">Invalid Proposal.</Typography>;
  }

  if (summaryQuery.isLoading) {
    return (
      <Box sx={{ display: "grid", placeItems: "center", py: 8 }}>
        <CircularProgress />
      </Box>
    );
  }

  if (summaryQuery.isError || !summaryQuery.data) {
    return <Typography color="error">Proposal not found.</Typography>;
  }

  const summary = summaryQuery.data;

  return (
    <Stack spacing={3}>
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
            {summary.proposalNumber}
          </Typography>

          <Typography
            variant="body2"
            color="text.secondary"
            sx={{ mt: 0.5, maxWidth: 640 }}
          >
            {summary.title ?? "Untitled proposal"}
          </Typography>

          <Typography
            variant="caption"
            color="text.disabled"
            sx={{ display: "block", mt: 0.75, fontFamily: "monospace" }}
          >
            proposal_id {summary.proposalId} &middot; sequence{" "}
            {summary.sequenceNumber}
            {summary.workflowDocumentNumber
              ? ` · workflow document ${summary.workflowDocumentNumber}`
              : ""}
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
          {activeSection === "summary" && (
            <ProposalSummarySection proposalId={proposalId} />
          )}

          {activeSection === "versions" && (
            <ProposalVersionsSection proposalId={proposalId} />
          )}

          {activeSection === "fundedAwards" && (
            <ProposalFundedAwardsSection proposalId={proposalId} />
          )}

          {activeSection === "attachments" && (
            <ProposalAttachmentsSection proposalId={proposalId} />
          )}

          {activeSection === "peopleUnits" && (
            <ProposalPeopleUnitsSection proposalId={proposalId} />
          )}

          {activeSection === "comments" && (
            <ProposalCommentsSection proposalId={proposalId} />
          )}
        </Box>
      </Box>
    </Stack>
  );
}
