import {
  ArrowForwardOutlined,
  FilterAltOffOutlined,
  SearchOutlined,
} from "@mui/icons-material";
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  CircularProgress,
  FormControl,
  Grid,
  InputAdornment,
  InputLabel,
  MenuItem,
  Pagination,
  Select,
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
import { useEffect, useMemo, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";

import { getIrbProtocols } from "../api/client";
import { IrbArchiveTabs } from "../components/IrbArchiveTabs";

const statusOptions = [
  "Active - Open to Enrollment",
  "Pending/In Progress",
  "Submitted to IRB",
];

const typeOptions = [
  "Exempt",
  "Expedited",
  "Not Human Subjects Research",
  "Cede Review B",
];

export function IrbPage() {
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();

  const queryValue = searchParams.get("query") ?? "";
  const statusValue = searchParams.get("status") ?? "";
  const typeValue = searchParams.get("type") ?? "";

  const [search, setSearch] = useState(queryValue);
  const [status, setStatus] = useState(statusValue);
  const [type, setType] = useState(typeValue);
  const [page, setPage] = useState(0);

  useEffect(() => {
    setSearch(queryValue);
    setStatus(statusValue);
    setType(typeValue);
  }, [queryValue, statusValue, typeValue]);

  const activeFilterCount = useMemo(
    () => [queryValue, statusValue, typeValue].filter(Boolean).length,
    [queryValue, statusValue, typeValue],
  );

  const irbQuery = useQuery({
    queryKey: [
      "irb",
      page,
      queryValue,
      statusValue,
      typeValue,
    ],
    queryFn: () =>
      getIrbProtocols({
        page,
        size: 10,
        query: queryValue,
        status: statusValue,
        type: typeValue,
      }),
  });

  const applyFilters = () => {
    const nextParameters: Record<string, string> = {};

    if (search.trim()) {
      nextParameters.query = search.trim();
    }

    if (status) {
      nextParameters.status = status;
    }

    if (type) {
      nextParameters.type = type;
    }

    setPage(0);
    setSearchParams(nextParameters);
  };

  const clearFilters = () => {
    setSearch("");
    setStatus("");
    setType("");
    setPage(0);
    setSearchParams({});
  };

  const firstVisibleRecord =
    irbQuery.data && irbQuery.data.totalElements > 0
      ? page * irbQuery.data.size + 1
      : 0;

  const lastVisibleRecord = irbQuery.data
    ? Math.min(
        (page + 1) * irbQuery.data.size,
        irbQuery.data.totalElements,
      )
    : 0;

  return (
    <Stack spacing={3}>
      <IrbArchiveTabs />
      <Card
        sx={{
          background:
            "linear-gradient(135deg, #ffffff 0%, #f8edf0 100%)",
        }}
      >
        <CardContent sx={{ p: { xs: 3, md: 4 } }}>
          <Grid container spacing={3} sx={{ alignItems: "center" }}>
            <Grid size={{ xs: 12, md: 8 }}>
              <Chip
                label="Kuali Legacy Data"
                size="small"
                color="primary"
                variant="outlined"
                sx={{ mb: 1.5 }}
              />

              <Typography variant="h4">
                IRB Protocol Archive
              </Typography>

              <Typography color="text.secondary" sx={{ mt: 1 }}>
                Search historical protocols, investigators, statuses and
                review types.
              </Typography>
            </Grid>

            <Grid size={{ xs: 12, md: 4 }}>
              <Stack
                spacing={0.5}
                sx={{
                  flexDirection: { xs: "row", md: "column" },
                  alignItems: { xs: "center", md: "flex-end" },
                  justifyContent: "space-between",
                }}
              >
                <Typography variant="h3" sx={{ fontWeight: 800 }}>
                  {irbQuery.data?.totalElements.toLocaleString() ?? "1,852"}
                </Typography>

                <Typography color="text.secondary">
                  archived protocols
                </Typography>
              </Stack>
            </Grid>
          </Grid>
        </CardContent>
      </Card>

      <Card>
        <CardContent sx={{ p: 3 }}>
          <Stack spacing={2.5}>
            <Stack
              spacing={2}
              sx={{
                flexDirection: { xs: "column", lg: "row" },
                alignItems: { lg: "center" },
              }}
            >
              <TextField
                value={search}
                onChange={(event) => setSearch(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === "Enter") {
                    applyFilters();
                  }
                }}
                placeholder="Study ID, protocol number, title or investigator"
                fullWidth
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

              <FormControl sx={{ minWidth: 230 }}>
                <InputLabel>Status</InputLabel>

                <Select
                  value={status}
                  label="Status"
                  onChange={(event) => setStatus(event.target.value)}
                >
                  <MenuItem value="">All statuses</MenuItem>

                  {statusOptions.map((option) => (
                    <MenuItem key={option} value={option}>
                      {option}
                    </MenuItem>
                  ))}
                </Select>
              </FormControl>

              <FormControl sx={{ minWidth: 230 }}>
                <InputLabel>Protocol type</InputLabel>

                <Select
                  value={type}
                  label="Protocol type"
                  onChange={(event) => setType(event.target.value)}
                >
                  <MenuItem value="">All types</MenuItem>

                  {typeOptions.map((option) => (
                    <MenuItem key={option} value={option}>
                      {option}
                    </MenuItem>
                  ))}
                </Select>
              </FormControl>

              <Button
                variant="contained"
                size="large"
                onClick={applyFilters}
                sx={{ minWidth: 110, height: 56 }}
              >
                Search
              </Button>
            </Stack>

            {activeFilterCount > 0 && (
              <Stack
                spacing={1}
                sx={{
                  flexDirection: "row",
                  alignItems: "center",
                  flexWrap: "wrap",
                  gap: 1,
                }}
              >
                <Typography variant="body2" color="text.secondary">
                  Active filters:
                </Typography>

                {queryValue && (
                  <Chip label={`Search: ${queryValue}`} size="small" />
                )}

                {statusValue && (
                  <Chip label={`Status: ${statusValue}`} size="small" />
                )}

                {typeValue && (
                  <Chip label={`Type: ${typeValue}`} size="small" />
                )}

                <Button
                  size="small"
                  startIcon={<FilterAltOffOutlined />}
                  onClick={clearFilters}
                >
                  Clear all
                </Button>
              </Stack>
            )}
          </Stack>
        </CardContent>
      </Card>

      <Card>
        <CardContent sx={{ p: 0 }}>
          {irbQuery.isLoading && (
            <Box sx={{ display: "grid", placeItems: "center", py: 10 }}>
              <CircularProgress />
            </Box>
          )}

          {irbQuery.isError && (
            <Alert severity="error">
              IRB records could not be loaded.
            </Alert>
          )}

          {irbQuery.data && (
            <>
              <Box
                sx={{
                  px: 3,
                  py: 2,
                  borderBottom: "1px solid #e7e9ee",
                  display: "flex",
                  justifyContent: "space-between",
                  alignItems: "center",
                }}
              >
                <Typography sx={{ fontWeight: 700 }}>
                  {irbQuery.data.totalElements.toLocaleString()} records
                </Typography>

                <Typography variant="body2" color="text.secondary">
                  Showing {firstVisibleRecord.toLocaleString()}–
                  {lastVisibleRecord.toLocaleString()}
                </Typography>
              </Box>

              <TableContainer>
                <Table>
                  <TableHead>
                    <TableRow>
                      <TableCell>Study ID</TableCell>
                      <TableCell>Protocol</TableCell>
                      <TableCell>Title</TableCell>
                      <TableCell>Principal Investigator</TableCell>
                      <TableCell>Status</TableCell>
                      <TableCell>Type</TableCell>
                      <TableCell>Approval</TableCell>
                      <TableCell />
                    </TableRow>
                  </TableHead>

                  <TableBody>
                    {irbQuery.data.content.map((protocol) => {
                      const detailPath =
                        `/irb/record/${protocol.recordId}`;

                      return (
                        <TableRow
                          hover
                          key={protocol.recordId}
                          onClick={() => {
                            if (detailPath) {
                              navigate(detailPath);
                            }
                          }}
                          sx={{
                            cursor: "pointer",
                            "&:hover": {
                              backgroundColor: "rgba(139, 24, 50, 0.035)",
                            },
                          }}
                        >
                          <TableCell>
                            {protocol.studyId ?? "—"}
                          </TableCell>

                          <TableCell>
                            {protocol.protocolNumber}
                          </TableCell>

                          <TableCell sx={{ maxWidth: 380 }}>
                            <Typography
                              variant="body2"
                              noWrap
                              sx={{ fontWeight: 600 }}
                            >
                              {protocol.title}
                            </Typography>
                          </TableCell>

                          <TableCell>
                            {protocol.piFullName ?? "Not available"}
                          </TableCell>

                          <TableCell>
                            <Chip
                              label={
                                protocol.protocolStatus ?? "Unknown"
                              }
                              size="small"
                              variant="outlined"
                            />
                          </TableCell>

                          <TableCell>
                            {protocol.protocolType ?? "—"}
                          </TableCell>

                          <TableCell>
                            {protocol.approvalDate ?? "—"}
                          </TableCell>

                          <TableCell align="right">
                            {detailPath && (
                              <ArrowForwardOutlined color="action" />
                            )}
                          </TableCell>
                        </TableRow>
                      );
                    })}
                  </TableBody>
                </Table>
              </TableContainer>

              <Box
                sx={{
                  display: "flex",
                  justifyContent: "center",
                  py: 3,
                  borderTop: "1px solid #e7e9ee",
                }}
              >
                <Pagination
                  page={page + 1}
                  count={irbQuery.data.totalPages}
                  onChange={(_, nextPage) => setPage(nextPage - 1)}
                  color="primary"
                  showFirstButton
                  showLastButton
                />
              </Box>
            </>
          )}
        </CardContent>
      </Card>
    </Stack>
  );
}
