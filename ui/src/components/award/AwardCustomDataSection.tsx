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

import { getAwardCustomDataV1 } from "../../api/client";
import {
  groupAwardCustomData,
  matchesAwardCustomDataQuery,
  resolveAwardCustomDataLabel,
} from "../../features/award/awardSectionsPresentation.mjs";
import type { AwardCustomDataV1 } from "../../types/api";
import { EmptyState } from "../common/EmptyState";
import { ErrorState } from "../common/ErrorState";
import { LoadingState } from "../common/LoadingState";

// Custom Data - archive.award_custom_data for this exact awardId,
// version-scoped (never combined with a sibling version's rows - see
// AwardArchiveRepository.findCustomData). A separate section from
// Terms, never merged. Grouped by the shared archive.custom_attribute
// lookup's groupName when proven; ungrouped rows collapse into a
// trailing "Other" section rather than one group per row.
export function AwardCustomDataSection({ awardId }: { awardId: number }) {
  const [query, setQuery] = useState("");

  const customDataQuery = useQuery({
    queryKey: ["award-custom-data-v1", awardId],
    queryFn: ({ signal }) => getAwardCustomDataV1(awardId, signal),
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
        message="No custom data recorded for this Award version."
      />
    );
  }

  const filteredRows = rows.filter((row) => matchesAwardCustomDataQuery(row, query));
  const groups = groupAwardCustomData(filteredRows);

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
              {group.rows.map((row: AwardCustomDataV1) => (
                <CustomDataRow key={row.awardCustomDataId} row={row} />
              ))}
            </Box>
          </AccordionDetails>
        </Accordion>
      ))}
    </Stack>
  );
}

function CustomDataRow({ row }: { row: AwardCustomDataV1 }) {
  const label = resolveAwardCustomDataLabel(row);
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
