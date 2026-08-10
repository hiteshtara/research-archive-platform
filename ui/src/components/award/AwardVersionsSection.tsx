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

import { getAwardVersionsV1 } from "../../api/client";
import { ErrorState } from "../common/ErrorState";
import { LoadingState } from "../common/LoadingState";
import { PaginationFooter } from "../common/PaginationFooter";
import { StatusPill } from "../common/StatusPill";

const PAGE_SIZE = 10;

// Every version of this Award's own award_number, newest first - fed
// from the live GET /api/v1/awards/{awardId}/versions endpoint.
export function AwardVersionsSection({ awardId }: { awardId: number }) {
  const [page, setPage] = useState(0);

  const versionsQuery = useQuery({
    queryKey: ["award-versions-v1", awardId, page],
    queryFn: ({ signal }) =>
      getAwardVersionsV1(awardId, { page, size: PAGE_SIZE }, signal),
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
              <TableCell>Sequence</TableCell>
              <TableCell>Status</TableCell>
              <TableCell>Transaction type</TableCell>
              <TableCell>Effective date</TableCell>
              <TableCell>Updated</TableCell>
              <TableCell>Document number</TableCell>
              <TableCell>Current</TableCell>
            </TableRow>
          </TableHead>

          <TableBody>
            {versions.content.map((version) => (
              <TableRow key={version.awardId}>
                <TableCell>
                  <Chip size="small" label={version.sequenceNumber} />
                </TableCell>
                <TableCell>
                  <StatusPill status={version.status} domain="award" />
                </TableCell>
                <TableCell>{version.transactionType ?? "—"}</TableCell>
                <TableCell>{version.awardEffectiveDate ?? "—"}</TableCell>
                <TableCell>{version.updateTimestamp ?? "—"}</TableCell>
                <TableCell sx={{ userSelect: "text" }}>
                  {version.documentNumber ?? "—"}
                </TableCell>
                <TableCell>
                  {version.primaryCurrent && (
                    <Chip size="small" color="success" label="Current" />
                  )}
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
