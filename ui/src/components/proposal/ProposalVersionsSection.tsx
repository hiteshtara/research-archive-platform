import {
  Chip,
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
import { ErrorState } from "../common/ErrorState";
import { LoadingState } from "../common/LoadingState";
import { PaginationFooter } from "../common/PaginationFooter";
import { StatusPill } from "../common/StatusPill";

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
    return <LoadingState mode="skeleton" height={220} />;
  }

  if (versionsQuery.isError) {
    return <ErrorState message="Unable to load version history." />;
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
                  <StatusPill status={version.status} domain="proposal" />
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

      <PaginationFooter
        totalPages={versions.totalPages}
        page={page}
        onPageChange={setPage}
      />
    </Stack>
  );
}
