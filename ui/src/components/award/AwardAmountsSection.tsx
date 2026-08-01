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
  Typography,
} from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";

import { getAwardAmountsV1 } from "../../api/client";
import { formatCurrencyAmount } from "../../features/award/awardSectionsPresentation.mjs";

const PAGE_SIZE = 50;

// Amounts - lazy-loads from GET /api/v1/awards/{awardId}/amounts, the
// whole Award's amount history (newest version first), distinct from
// the not-yet-built Budget/Time and Money sections.
export function AwardAmountsSection({ awardId }: { awardId: number }) {
  const [page, setPage] = useState(0);

  const amountsQuery = useQuery({
    queryKey: ["award-amounts-v1", awardId, page],
    queryFn: ({ signal }) =>
      getAwardAmountsV1(awardId, { page, size: PAGE_SIZE }, signal),
  });

  if (amountsQuery.isLoading) {
    return <Skeleton variant="rounded" height={220} />;
  }

  if (amountsQuery.isError) {
    return <Alert severity="error">Unable to load amount history.</Alert>;
  }

  const amounts = amountsQuery.data;

  if (!amounts) {
    return null;
  }

  if (amounts.content.length === 0) {
    return (
      <Typography color="text.secondary">
        No amount history is recorded for this Award.
      </Typography>
    );
  }

  return (
    <Stack spacing={2}>
      <TableContainer>
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>Sequence</TableCell>
              <TableCell>Obligated total</TableCell>
              <TableCell>Anticipated total</TableCell>
              <TableCell>Effective date</TableCell>
              <TableCell>Document number</TableCell>
            </TableRow>
          </TableHead>

          <TableBody>
            {amounts.content.map((amount) => (
              <TableRow key={amount.awardAmountInfoId}>
                <TableCell>
                  <Chip size="small" label={amount.sequenceNumber} />
                </TableCell>
                <TableCell>{formatCurrencyAmount(amount.obligatedTotalAmount)}</TableCell>
                <TableCell>{formatCurrencyAmount(amount.anticipatedTotalAmount)}</TableCell>
                <TableCell>{amount.awardEffectiveDate ?? "—"}</TableCell>
                <TableCell>{amount.documentNumber ?? "—"}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>

      {amounts.totalPages > 1 && (
        <Box sx={{ display: "flex", justifyContent: "center" }}>
          <Pagination
            count={amounts.totalPages}
            page={page + 1}
            onChange={(_event, value) => setPage(value - 1)}
          />
        </Box>
      )}
    </Stack>
  );
}
