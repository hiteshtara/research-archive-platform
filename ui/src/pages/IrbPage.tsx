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
  IconButton,
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
import { useMemo, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";

import { getIrbProtocols } from "../api/client";

export function IrbPage() {
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();

  const initialQuery = searchParams.get("query") ?? "";
  const [search, setSearch] = useState(initialQuery);
  const [page, setPage] = useState(0);

  const queryValue = useMemo(
    () => searchParams.get("query") ?? "",
    [searchParams],
  );

  const irbQuery = useQuery({
    queryKey: ["irb", page, queryValue],
    queryFn: () => getIrbProtocols(page, 10, queryValue),
  });

  const runSearch = () => {
    setPage(0);

    if (search.trim()) {
      setSearchParams({ query: search.trim() });
    } else {
      setSearchParams({});
    }
  };

  return (
    <Stack spacing={3}>
      <Box>
        <Typography variant="h4">IRB Protocols</Typography>
        <Typography color="text.secondary" sx={{ mt: 1 }}>
          Search the active protocol archive.
        </Typography>
      </Box>

      <TextField
        value={search}
        onChange={(event) => setSearch(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === "Enter") {
            runSearch();
          }
        }}
        placeholder="Study ID, protocol number, title or PI"
        sx={{ maxWidth: 700 }}
        InputProps={{
          startAdornment: (
            <InputAdornment position="start">
              <SearchOutlined />
            </InputAdornment>
          ),
        }}
      />

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
                }}
              >
                <Typography fontWeight={700}>
                  {irbQuery.data.totalElements.toLocaleString()} records
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
                    {irbQuery.data.content.map((protocol) => (
                      <TableRow hover key={protocol.recordId}>
                        <TableCell>{protocol.studyId ?? "—"}</TableCell>
                        <TableCell>{protocol.protocolNumber}</TableCell>
                        <TableCell sx={{ maxWidth: 360 }}>
                          <Typography
                            variant="body2"
                            fontWeight={600}
                            noWrap
                          >
                            {protocol.title}
                          </Typography>
                        </TableCell>
                        <TableCell>
                          {protocol.piFullName ?? "Not available"}
                        </TableCell>
                        <TableCell>
                          <Chip
                            label={protocol.protocolStatus ?? "Unknown"}
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
                          <IconButton
                            aria-label="View protocol"
                            onClick={() => {
                              if (protocol.studyId) {
                                navigate(`/irb/${protocol.studyId}`);
                              }
                            }}
                          >
                            <ArrowForwardOutlined />
                          </IconButton>
                        </TableCell>
                      </TableRow>
                    ))}
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
                />
              </Box>
            </>
          )}
        </CardContent>
      </Card>
    </Stack>
  );
}
