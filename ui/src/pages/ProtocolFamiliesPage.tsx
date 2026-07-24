import {
  Alert,
  Button,
  Card,
  CardContent,
  CircularProgress,
  InputAdornment,
  Pagination,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  TextField,
  Typography,
} from "@mui/material";
import { SearchOutlined } from "@mui/icons-material";
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
          <Typography variant="h4">Protocol Archive</Typography>
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
          </Stack>
        </CardContent>
      </Card>
      {query.isLoading && <CircularProgress />}
      {query.isError && (
        <Alert severity="error">Unable to load Protocol families.</Alert>
      )}
      {query.data && query.data.content.length === 0 && (
        <Alert severity="info">No Protocol families found.</Alert>
      )}
      {query.data && query.data.content.length > 0 && (
        <Card>
          <Table>
            <TableHead>
              <TableRow>
                <TableCell>Protocol Number</TableCell>
                <TableCell>Latest Sequence</TableCell>
                <TableCell>Versions</TableCell>
                <TableCell>Status</TableCell>
                <TableCell>Title</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {query.data.content.map((protocol) => (
                <TableRow
                  hover
                  key={protocol.protocolNumber}
                  sx={{ cursor: "pointer" }}
                  onClick={() =>
                    navigate(
                      `/protocols/${encodeURIComponent(
                        protocol.protocolNumber,
                      )}`,
                    )
                  }
                >
                  <TableCell>{protocol.protocolNumber}</TableCell>
                  <TableCell>{protocol.latestSequenceNumber}</TableCell>
                  <TableCell>{protocol.versionCount}</TableCell>
                  <TableCell>
                    {protocol.protocolStatusDescription ?? "—"}
                  </TableCell>
                  <TableCell>{protocol.title ?? "—"}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          {query.data.totalPages > 1 && (
            <Pagination
              sx={{ p: 2 }}
              page={page + 1}
              count={query.data.totalPages}
              onChange={(_, value) => setPage(value - 1)}
            />
          )}
        </Card>
      )}
    </Stack>
  );
}
