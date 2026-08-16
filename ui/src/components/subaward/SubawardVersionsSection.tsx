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
import { useNavigate } from "react-router-dom";

import { getSubawardVersions } from "../../api/client";
import { ErrorState } from "../common/ErrorState";
import { LoadingState } from "../common/LoadingState";
import { PaginationFooter } from "../common/PaginationFooter";
import { StatusPill } from "../common/StatusPill";

const PAGE_SIZE = 10;

// Every archived version of this Subaward's own subaward_code, newest
// first - fed from the live GET /api/subawards/{subawardId}/versions
// endpoint. Mirrors AwardVersionsSection exactly. currentSubawardId (the
// version currently open in the workspace) is highlighted distinctly
// from latestVersion (the server-computed "highest sequence_number in
// the family") - the two are often, but not always, the same row.
export function SubawardVersionsSection({
  subawardId,
  currentSubawardId,
}: {
  subawardId: number;
  currentSubawardId: number;
}) {
  const navigate = useNavigate();
  const [page, setPage] = useState(0);

  const versionsQuery = useQuery({
    queryKey: ["subaward-versions", subawardId, page],
    queryFn: ({ signal }) =>
      getSubawardVersions(subawardId, { page, size: PAGE_SIZE }, signal),
  });

  if (versionsQuery.isLoading) {
    return <LoadingState mode="skeleton" height={220} />;
  }

  if (versionsQuery.isError) {
    return <ErrorState message="Unable to load archived versions." />;
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
              <TableCell>Start date</TableCell>
              <TableCell>End date</TableCell>
              <TableCell>Updated</TableCell>
              <TableCell>Document number</TableCell>
              <TableCell>Latest</TableCell>
            </TableRow>
          </TableHead>

          <TableBody>
            {versions.content.map((version) => {
              const isCurrentlyViewed =
                version.subawardId === currentSubawardId;
              return (
                <TableRow
                  key={version.subawardId}
                  hover
                  selected={isCurrentlyViewed}
                  sx={{ cursor: "pointer" }}
                  role="button"
                  tabIndex={0}
                  onClick={() => navigate(`/subawards/${version.subawardId}`)}
                  onKeyDown={(event) => {
                    if (event.key === "Enter" || event.key === " ") {
                      event.preventDefault();
                      navigate(`/subawards/${version.subawardId}`);
                    }
                  }}
                >
                  <TableCell>
                    <Stack direction="row" spacing={0.5} sx={{ alignItems: "center" }}>
                      <Chip size="small" label={version.sequenceNumber} />
                      {isCurrentlyViewed && (
                        <Chip size="small" variant="outlined" label="Viewing" />
                      )}
                    </Stack>
                  </TableCell>
                  <TableCell>
                    <StatusPill status={version.status} domain="subaward" />
                  </TableCell>
                  <TableCell>{version.startDate ?? "—"}</TableCell>
                  <TableCell>{version.endDate ?? "—"}</TableCell>
                  <TableCell>{version.updateTimestamp ?? "—"}</TableCell>
                  <TableCell sx={{ userSelect: "text" }}>
                    {version.documentNumber ?? "—"}
                  </TableCell>
                  <TableCell>
                    {version.latestVersion && (
                      <Chip size="small" color="success" label="Latest" />
                    )}
                  </TableCell>
                </TableRow>
              );
            })}
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
