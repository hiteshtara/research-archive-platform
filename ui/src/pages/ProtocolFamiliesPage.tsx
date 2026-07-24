import {
  ArrowForwardOutlined,
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
  InputAdornment,
  Pagination,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  TableContainer,
  TextField,
  Typography,
} from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { useNavigate } from "react-router-dom";

import { getProtocols } from "../api/client";

export function ProtocolFamiliesPage() {
  const navigate = useNavigate();
  const [queryText, setQueryText] = useState("");
  const [appliedQuery, setAppliedQuery] = useState("");
  const [page, setPage] = useState(0);
  const query = useQuery({
    queryKey: ["protocols", appliedQuery, page],
    queryFn: () => getProtocols({ query: appliedQuery, page, size: 25 }),
  });

  function applySearch() {
    setPage(0);
    setAppliedQuery(queryText.trim());
  }

  return (
    <Stack spacing={3}>
      <Card>
        <CardContent
          component="form"
          onSubmit={(event) => {
            event.preventDefault();
            applySearch();
          }}
        >
          <Stack
            direction={{ xs: "column", md: "row" }}
            spacing={2}
            sx={{ justifyContent: "space-between" }}
          >
            <Box>
              <Chip
                label="Canonical Kuali History"
                color="primary"
                size="small"
                sx={{ mb: 1.5 }}
              />
              <Typography variant="h4">Protocol Archive</Typography>
              <Typography color="text.secondary" sx={{ mt: 1 }}>
                One row per institutional protocol number, with complete
                version history and related research records.
              </Typography>
            </Box>
            {query.data && (
              <Box sx={{ textAlign: { xs: "left", md: "right" } }}>
                <Typography variant="h4">
                  {query.data.totalElements.toLocaleString()}
                </Typography>
                <Typography color="text.secondary">
                  Protocol families
                </Typography>
              </Box>
            )}
          </Stack>
          <Stack direction={{ xs: "column", sm: "row" }} spacing={1.5}>
            <TextField
              fullWidth
              sx={{ mt: 2 }}
              label="Search Protocol archive"
              helperText={
                "Search protocols, titles, people, units, funding, research " +
                "areas, locations, submissions, actions, and amendments"
              }
              value={queryText}
              onChange={(event) => setQueryText(event.target.value)}
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
            <Button
              type="submit"
              variant="contained"
              sx={{ mt: 2, alignSelf: "flex-start", minHeight: 56 }}
            >
              Search
            </Button>
            {appliedQuery && (
              <Button
                variant="text"
                sx={{ mt: 2, alignSelf: "flex-start", minHeight: 56 }}
                onClick={() => {
                  setQueryText("");
                  setAppliedQuery("");
                  setPage(0);
                }}
              >
                Clear
              </Button>
            )}
          </Stack>
          {appliedQuery && (
            <Chip
              label={`Search: ${appliedQuery}`}
              size="small"
              onDelete={() => {
                setQueryText("");
                setAppliedQuery("");
                setPage(0);
              }}
              sx={{ mt: 2 }}
            />
          )}
        </CardContent>
      </Card>
      {query.isLoading && (
        <Card>
          <Box sx={{ display: "grid", placeItems: "center", py: 10 }}>
            <CircularProgress />
          </Box>
        </Card>
      )}
      {query.isError && (
        <Alert severity="error">Unable to load Protocol families.</Alert>
      )}
      {query.data && query.data.content.length === 0 && (
        <Card>
          <CardContent sx={{ py: 6, textAlign: "center" }}>
            <Typography variant="h6">
              No Protocol families found
            </Typography>
            <Typography color="text.secondary" sx={{ mt: 1 }}>
              Try a protocol number, title, person, unit, funding source,
              research area, location, submission, action, or amendment.
            </Typography>
          </CardContent>
        </Card>
      )}
      {query.data && query.data.content.length > 0 && (
        <Card>
          <Box sx={{ px: 3, py: 2 }}>
            <Typography sx={{ fontWeight: 700 }}>
              {query.data.totalElements.toLocaleString()} protocol families
            </Typography>
            <Typography variant="body2" color="text.secondary">
              Select a row to review all versions and related archive
              objects.
            </Typography>
          </Box>
          <TableContainer>
            <Table sx={{ minWidth: 1100 }}>
              <TableHead>
                <TableRow>
                  <TableCell>Protocol Number</TableCell>
                  <TableCell>Title</TableCell>
                  <TableCell>Type</TableCell>
                  <TableCell>Status</TableCell>
                  <TableCell>Latest Sequence</TableCell>
                  <TableCell>Versions</TableCell>
                  <TableCell>Active</TableCell>
                  <TableCell>Expiration</TableCell>
                  <TableCell />
                </TableRow>
              </TableHead>
              <TableBody>
                {query.data.content.map((protocol) => (
                  <TableRow
                    hover
                    key={protocol.protocolNumber}
                    sx={{
                      cursor: "pointer",
                      "&:hover": {
                        backgroundColor: "rgba(139, 24, 50, 0.035)",
                      },
                    }}
                    onClick={() =>
                      navigate(
                        `/protocols/${encodeURIComponent(
                          protocol.protocolNumber,
                        )}`,
                      )
                    }
                  >
                    <TableCell sx={{ fontWeight: 700 }}>
                      {protocol.protocolNumber}
                    </TableCell>
                    <TableCell sx={{ maxWidth: 380 }}>
                      <Typography variant="body2" noWrap>
                        {protocol.title ?? "Untitled protocol"}
                      </Typography>
                    </TableCell>
                    <TableCell>
                      {protocol.protocolTypeDescription ?? "—"}
                    </TableCell>
                    <TableCell>
                      <Chip
                        label={
                          protocol.protocolStatusDescription ?? "Unknown"
                        }
                        size="small"
                        variant="outlined"
                      />
                    </TableCell>
                    <TableCell>
                      {protocol.latestSequenceNumber}
                    </TableCell>
                    <TableCell>
                      <Chip
                        label={protocol.versionCount.toLocaleString()}
                        size="small"
                      />
                    </TableCell>
                    <TableCell>
                      {protocol.active ? (
                        <Chip
                          label={protocol.active}
                          size="small"
                          color={
                            protocol.active.trim().toUpperCase() === "Y"
                              ? "success"
                              : "default"
                          }
                        />
                      ) : (
                        "—"
                      )}
                    </TableCell>
                    <TableCell>{protocol.expirationDate ?? "—"}</TableCell>
                    <TableCell align="right">
                      <ArrowForwardOutlined color="action" />
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
          {query.data.totalPages > 1 && (
            <Box sx={{ display: "flex", justifyContent: "center", py: 3 }}>
              <Pagination
                page={page + 1}
                count={query.data.totalPages}
                onChange={(_, value) => setPage(value - 1)}
                color="primary"
                showFirstButton
                showLastButton
              />
            </Box>
          )}
        </Card>
      )}
    </Stack>
  );
}
