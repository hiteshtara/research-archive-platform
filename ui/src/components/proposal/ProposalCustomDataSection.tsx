import { ExpandMoreOutlined } from "@mui/icons-material";
import {
  Accordion,
  AccordionDetails,
  AccordionSummary,
  Box,
  Chip,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";

import { getProposalCustomDataV1 } from "../../api/client";
import {
  groupCustomData,
  matchesCustomDataQuery,
  resolveCustomDataLabel,
} from "../../features/proposal/proposalCustomDataPresentation.mjs";
import type { ProposalCustomDataV1 } from "../../types/api";
import { EmptyState } from "../common/EmptyState";
import { ErrorState } from "../common/ErrorState";
import { LoadingState } from "../common/LoadingState";

// Custom Data - archive.proposal_custom_data for this exact
// proposalId, version-scoped (never combined with a sibling version's
// rows - see ProposalV1Repository.findCustomDataRows). Grouped by the
// shared archive.custom_attribute lookup's groupName when proven;
// ungrouped rows collapse into a trailing "Other" section rather than
// one group per row. A real fixture (Proposal 01157400) carries 161
// rows, so groups render as collapsible accordions with a search box
// rather than one long dump - only the first group opens by default.
export function ProposalCustomDataSection({
  proposalId,
}: {
  proposalId: number;
}) {
  const [query, setQuery] = useState("");

  const customDataQuery = useQuery({
    queryKey: ["proposal-custom-data-v1", proposalId],
    queryFn: ({ signal }) => getProposalCustomDataV1(proposalId, signal),
  });

  if (customDataQuery.isLoading) {
    return <LoadingState mode="skeleton" heights={[48, 160]} />;
  }

  if (customDataQuery.isError) {
    return <ErrorState message="Unable to load Custom Data." />;
  }

  const rows = customDataQuery.data ?? [];

  if (rows.length === 0) {
    return (
      <EmptyState
        variant="text"
        message="No custom data recorded for this Proposal version."
      />
    );
  }

  const filteredRows = rows.filter((row) => matchesCustomDataQuery(row, query));
  const groups = groupCustomData(filteredRows);

  return (
    <Stack spacing={2}>
      <TextField
        size="small"
        placeholder="Search custom data by label or value…"
        value={query}
        onChange={(event) => setQuery(event.target.value)}
        fullWidth
      />

      {groups.length === 0 && (
        <EmptyState variant="text" message={`No custom data matches "${query}".`} />
      )}

      {groups.map((group, groupIndex) => (
        <Accordion
          key={group.groupName}
          defaultExpanded={groupIndex === 0}
          disableGutters
          variant="outlined"
          sx={{ "&:before": { display: "none" } }}
        >
          <AccordionSummary expandIcon={<ExpandMoreOutlined />}>
            <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
              <Typography sx={{ fontWeight: 700 }}>
                {group.groupName}
              </Typography>
              <Chip size="small" variant="outlined" label={group.rows.length} />
            </Stack>
          </AccordionSummary>
          <AccordionDetails>
            <Box
              sx={{
                display: "grid",
                gridTemplateColumns: "minmax(200px, 320px) 1fr",
                rowGap: 1.25,
                columnGap: 2,
              }}
            >
              {group.rows.map((row: ProposalCustomDataV1) => (
                <CustomDataRow key={row.proposalCustomDataId} row={row} />
              ))}
            </Box>
          </AccordionDetails>
        </Accordion>
      ))}
    </Stack>
  );
}

function CustomDataRow({ row }: { row: ProposalCustomDataV1 }) {
  const label = resolveCustomDataLabel(row);
  const hasValue = row.value !== null && row.value.trim() !== "";

  return (
    <>
      <Typography
        variant="body2"
        color="text.secondary"
        sx={{ fontWeight: 600 }}
      >
        {label}
      </Typography>
      <Typography
        variant="body2"
        sx={{
          fontStyle: hasValue ? "normal" : "italic",
          color: hasValue ? "text.primary" : "text.disabled",
        }}
      >
        {hasValue ? row.value : "(blank)"}
      </Typography>
    </>
  );
}
