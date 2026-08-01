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

import { getAwardVersionsV1 } from "../../api/client";
import { AwardStatusPill } from "./AwardStatusPill";

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
              <TableCell>Sequence</TableCell>
              <TableCell>Status</TableCell>
              <TableCell>Transaction type</TableCell>
              <TableCell>Effective date</TableCell>
              <TableCell>Updated</TableCell>
              <TableCell>Document number</TableCell>
            </TableRow>
          </TableHead>

          <TableBody>
            {versions.content.map((version) => (
              <TableRow key={version.awardId}>
                <TableCell>
                  <Chip size="small" label={version.sequenceNumber} />
                </TableCell>
                <TableCell>
                  <AwardStatusPill status={version.status} />
                </TableCell>
                <TableCell>{version.transactionType ?? "—"}</TableCell>
                <TableCell>{version.awardEffectiveDate ?? "—"}</TableCell>
                <TableCell>{version.updateTimestamp ?? "—"}</TableCell>
                <TableCell>{version.documentNumber ?? "—"}</TableCell>
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
