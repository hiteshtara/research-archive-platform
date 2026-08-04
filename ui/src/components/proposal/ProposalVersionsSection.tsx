import {
  Alert,
  Box,
  Chip,
  Pagination,
  Skeleton,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
} from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";

import { getProposalVersionsV1 } from "../../api/client";
import { AwardStatusPill } from "../award/AwardStatusPill";

const PAGE_SIZE = 10;

// Every version of this Proposal's own proposalNumber family, newest
// first - fed from GET /api/v1/proposals/{proposalId}/versions.
export function ProposalVersionsSection({
  proposalId,
}: {
  proposalId: number;
}) {
  const [page, setPage] = useState(0);

  const versionsQuery = useQuery({
    queryKey: ["proposal-versions-v1", proposalId, page],
    queryFn: ({ signal }) =>
      getProposalVersionsV1(proposalId, { page, size: PAGE_SIZE }, signal),
  });

  if (versionsQuery.isLoading) {
    return <Skeleton variant="rounded" height={220} />;
  }

  if (versionsQuery.isError) {
    return <Alert severity="error">Unable to load version history.</Alert>;
  }

  const versions = versionsQuery.data;

  if (!versions) {
    return null;
  }

  return (
    <Stack spacing={2}>
      <TableContainer>
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>Version</TableCell>
              <TableCell>Sequence status</TableCell>
              <TableCell>Status</TableCell>
              <TableCell>Title</TableCell>
              <TableCell>Updated</TableCell>
              <TableCell>Workflow document</TableCell>
            </TableRow>
          </TableHead>

          <TableBody>
            {versions.content.map((version) => (
              <TableRow key={version.proposalId}>
                <TableCell>
                  <Chip size="small" label={version.sequenceNumber} />
                </TableCell>
                <TableCell>{version.proposalSequenceStatus ?? "—"}</TableCell>
                <TableCell>
                  <AwardStatusPill status={version.status} />
                </TableCell>
                <TableCell
                  sx={{
                    maxWidth: 320,
                    overflow: "hidden",
                    textOverflow: "ellipsis",
                    whiteSpace: "nowrap",
                  }}
                >
                  {version.title ?? "—"}
                </TableCell>
                <TableCell>{version.sourceUpdateTimestamp ?? "—"}</TableCell>
                <TableCell sx={{ userSelect: "text" }}>
                  {version.workflowDocumentNumber ?? "—"}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>

      {versions.totalPages > 1 && (
        <Box sx={{ display: "flex", justifyContent: "center" }}>
          <Pagination
            count={versions.totalPages}
            page={page + 1}
            onChange={(_event, value) => setPage(value - 1)}
          />
        </Box>
      )}
    </Stack>
  );
}
