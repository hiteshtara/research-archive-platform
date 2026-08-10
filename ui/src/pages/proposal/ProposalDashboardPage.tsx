import {
  Box,
  Chip,
  List,
  ListItemButton,
  ListItemText,
  Stack,
  Typography,
} from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { useParams, useSearchParams } from "react-router-dom";

import { getProposalSummaryV1 } from "../../api/client";
import { StatusPill } from "../../components/common/StatusPill";
import { ComingSoonSection } from "../../components/award/ComingSoonSection";
import { ErrorState } from "../../components/common/ErrorState";
import { LoadingState } from "../../components/common/LoadingState";
import { ProposalAttachmentsSection } from "../../components/proposal/ProposalAttachmentsSection";
import { ProposalCommentsSection } from "../../components/proposal/ProposalCommentsSection";
import { ProposalCustomDataSection } from "../../components/proposal/ProposalCustomDataSection";
import { ProposalFundedAwardsSection } from "../../components/proposal/ProposalFundedAwardsSection";
import { ProposalPeopleUnitsSection } from "../../components/proposal/ProposalPeopleUnitsSection";
import { ProposalSponsorProgramSection } from "../../components/proposal/ProposalSponsorProgramSection";
import { ProposalSummarySection } from "../../components/proposal/ProposalSummarySection";
import { ProposalVersionsSection } from "../../components/proposal/ProposalVersionsSection";

const SECTIONS = [
  { key: "summary", label: "Summary" },
  { key: "versions", label: "Versions" },
  { key: "fundedAwards", label: "Funded Awards" },
  { key: "peopleUnits", label: "People and Units" },
  { key: "attachments", label: "Attachments" },
  { key: "comments", label: "Comments" },
  { key: "sponsorProgram", label: "Sponsor and Program" },
  { key: "customData", label: "Custom Data" },
] as const;

type SectionKey = (typeof SECTIONS)[number]["key"];

const SECTION_KEYS = new Set<string>(SECTIONS.map((section) => section.key));

function isSectionKey(value: string | null): value is SectionKey {
  return value !== null && SECTION_KEYS.has(value);
}

const IMPLEMENTED_SECTIONS = new Set<SectionKey>([
  "summary",
  "versions",
  "fundedAwards",
  "peopleUnits",
  "attachments",
  "comments",
  "sponsorProgram",
  "customData",
]);

function InfoRow({ label, value }: { label: string; value: string | null }) {
  return (
    <Stack direction="row" spacing={1} sx={{ alignItems: "baseline" }}>
      <Typography
        variant="caption"
        color="text.secondary"
        sx={{ width: 64, flexShrink: 0, textTransform: "uppercase", letterSpacing: "0.04em" }}
      >
        {label}
      </Typography>
      <Typography variant="body2" sx={{ fontWeight: 600 }}>
        {value ?? "—"}
      </Typography>
    </Stack>
  );
}

// Institutional Proposal Dashboard - keyed by the exact surrogate
// proposalId (one specific version), following the same visual
// language as AwardDashboardPage: a left-nav section switcher, a
// header that surfaces the identifiers/people that matter most at a
// glance, and card-based summary stats rather than a long key-value
// table. proposalNumber, sequenceNumber, and workflowDocumentNumber
// are shown as distinct identifiers - never inferred one from another.
export function ProposalDashboardPage() {
  const { proposalId: proposalIdParameter } = useParams<{
    proposalId: string;
  }>();
  const proposalId = Number(proposalIdParameter);

  // Supports deep-linking directly into a section (e.g. Proposal
  // Explorer's attachment-count click-through: /proposals/dashboard/
  // {id}?section=attachments) - read once on mount, not kept in sync
  // with later section clicks, matching how a normal in-page nav works.
  const [searchParams] = useSearchParams();
  const requestedSection = searchParams.get("section");
  const [activeSection, setActiveSection] = useState<SectionKey>(
    isSectionKey(requestedSection) ? requestedSection : "summary",
  );

  const summaryQuery = useQuery({
    queryKey: ["proposal-summary-v1", proposalId],
    queryFn: ({ signal }) => getProposalSummaryV1(proposalId, signal),
    enabled: Number.isFinite(proposalId),
  });

  if (!Number.isFinite(proposalId)) {
    return <ErrorState message="Invalid Proposal." />;
  }

  if (summaryQuery.isLoading) {
    return <LoadingState />;
  }

  if (summaryQuery.isError || !summaryQuery.data) {
    return <ErrorState message="Proposal not found." />;
  }

  const summary = summaryQuery.data;

  return (
    <Stack spacing={3}>
      <Box
        sx={{
          pb: 2.25,
          borderBottom: "1px solid",
          borderColor: "divider",
        }}
      >
        <Box
          sx={{
            display: "flex",
            justifyContent: "space-between",
            alignItems: "flex-start",
            gap: 2,
          }}
        >
          <Box sx={{ minWidth: 0 }}>
            <Typography sx={{ fontSize: 20, fontWeight: 700 }}>
              Institutional Proposal {summary.proposalNumber}
            </Typography>

            <Typography
              variant="body2"
              color="text.secondary"
              sx={{ mt: 0.5, maxWidth: 640 }}
            >
              {summary.title ?? "Untitled proposal"}
            </Typography>

            <Stack
              direction="row"
              spacing={1}
              sx={{ mt: 1.25, flexWrap: "wrap", alignItems: "center" }}
            >
              <StatusPill
                status={summary.proposalSequenceStatus}
                domain="proposal"
              />
              <Chip
                size="small"
                variant="outlined"
                label={`Version ${summary.sequenceNumber}`}
              />
              {summary.workflowDocumentNumber && (
                <Chip
                  size="small"
                  variant="outlined"
                  label={`Workflow ${summary.workflowDocumentNumber}`}
                />
              )}
            </Stack>
          </Box>
        </Box>

        <Stack spacing={0.5} sx={{ mt: 2 }}>
          <InfoRow label="PI" value={summary.principalInvestigatorName} />
          <InfoRow label="Sponsor" value={summary.sponsorName} />
          <InfoRow label="Lead Unit" value={summary.leadUnitName} />
        </Stack>
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

          {activeSection === "peopleUnits" && (
            <ProposalPeopleUnitsSection proposalId={proposalId} />
          )}

          {activeSection === "attachments" && (
            <ProposalAttachmentsSection proposalId={proposalId} />
          )}

          {activeSection === "comments" && (
            <ProposalCommentsSection proposalId={proposalId} />
          )}

          {activeSection === "sponsorProgram" && (
            <ProposalSponsorProgramSection proposalId={proposalId} />
          )}

          {activeSection === "customData" && (
            <ProposalCustomDataSection proposalId={proposalId} />
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
