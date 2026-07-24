import {
  ArrowForwardOutlined,
  SearchOutlined,
} from "@mui/icons-material";
import {
  Alert,
  Box,
  Card,
  CardContent,
  Chip,
  CircularProgress,
  InputAdornment,
  Pagination,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TextField,
  Typography,
} from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { useNavigate } from "react-router-dom";

import { getSubawards } from "../api/client";

const pageSize = 25;

function display(value: string | number | null) {
  return value ?? "—";
}

export function SubawardFamiliesPage() {
  const navigate = useNavigate();
  const [search, setSearch] = useState("");
  const [appliedSearch, setAppliedSearch] = useState("");
  const [page, setPage] = useState(0);

  const query = useQuery({
    queryKey: ["subawards", appliedSearch, page],
    queryFn: ({ signal }) =>
      getSubawards({
        query: appliedSearch,
        page,
        size: pageSize,
      }, signal),
  });

  const applySearch = () => {
    setAppliedSearch(search.trim());
    setPage(0);
  };

  return (
    <Stack spacing={3}>
      <Card>
        <CardContent>
          <Typography variant="h4">Subawards</Typography>

          <Typography color="text.secondary" sx={{ mt: 1 }}>
            Search physical archived Subaward records and open a specific
            source version.
          </Typography>

          <TextField
            fullWidth
            sx={{ mt: 3 }}
            placeholder="Subaward ID, code, document, title, status, organization, account..."
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter") {
                applySearch();
              }
            }}
            slotProps={{
              input: {
                startAdornment: (
                  <InputAdornment position="start">
                    <SearchOutlined />
                  </InputAdornment>
                ),
              },
            }}
          />
        </CardContent>
      </Card>

      <Card>
        {query.isLoading && (
          <Box sx={{ display: "grid", placeItems: "center", py: 8 }}>
            <CircularProgress />
          </Box>
        )}

        {query.isError && (
          <Alert severity="error">Unable to load Subawards.</Alert>
        )}

        {query.data && (
          <>
            <Box sx={{ px: 3, py: 2 }}>
              <Typography sx={{ fontWeight: 700 }}>
                {query.data.totalElements.toLocaleString()} physical Subaward
                records
              </Typography>
            </Box>

            {query.data.content.length === 0 ? (
              <Box sx={{ px: 3, pb: 3 }}>
                <Alert severity="info">
                  No matching Subaward records were found.
                </Alert>
              </Box>
            ) : (
              <TableContainer>
                <Table>
                  <TableHead>
                    <TableRow>
                      <TableCell>Subaward</TableCell>
                      <TableCell>Sequence</TableCell>
                      <TableCell>Title</TableCell>
                      <TableCell>Status</TableCell>
                      <TableCell>Organization</TableCell>
                      <TableCell>Dates</TableCell>
                      <TableCell />
                    </TableRow>
                  </TableHead>

                  <TableBody>
                    {query.data.content.map((subaward) => (
                      <TableRow
                        key={subaward.subawardId}
                        hover
                        sx={{ cursor: "pointer" }}
                        onClick={() =>
                          navigate(
                            `/subawards/${encodeURIComponent(
                              subaward.subawardId,
                            )}`,
                          )
                        }
                      >
                        <TableCell>
                          <Typography sx={{ fontWeight: 700 }}>
                            {subaward.subawardCode}
                          </Typography>
                          <Typography variant="caption" color="text.secondary">
                            ID {subaward.subawardId} · Document{" "}
                            {display(subaward.documentNumber)}
                          </Typography>
                        </TableCell>
                        <TableCell>
                          <Chip
                            size="small"
                            variant="outlined"
                            label={`Sequence ${subaward.sequenceNumber}`}
                          />
                          <Typography
                            variant="caption"
                            color="text.secondary"
                            sx={{ display: "block", mt: 0.5 }}
                          >
                            {display(subaward.subawardSequenceStatus)}
                          </Typography>
                        </TableCell>
                        <TableCell sx={{ minWidth: 260 }}>
                          {display(subaward.title)}
                        </TableCell>
                        <TableCell>
                          <Chip
                            size="small"
                            variant="outlined"
                            label={display(subaward.statusDescription)}
                          />
                        </TableCell>
                        <TableCell>
                          {display(subaward.organizationId)}
                        </TableCell>
                        <TableCell>
                          {display(subaward.startDate)} –{" "}
                          {display(subaward.endDate)}
                        </TableCell>
                        <TableCell align="right">
                          <ArrowForwardOutlined color="action" />
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </TableContainer>
            )}

            {query.data.totalPages > 1 && (
              <Stack sx={{ alignItems: "center", py: 3 }}>
                <Pagination
                  page={query.data.page + 1}
                  count={query.data.totalPages}
                  onChange={(_, nextPage) => setPage(nextPage - 1)}
                  color="primary"
                />
              </Stack>
            )}
          </>
        )}
      </Card>
    </Stack>
  );
}
