import { SearchOutlined } from "@mui/icons-material";
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

import { getIrbFamilies } from "../api/client";
import { IrbArchiveTabs } from "../components/IrbArchiveTabs";

export function IrbFamiliesPage() {
  const [search, setSearch] = useState("");
  const [appliedSearch, setAppliedSearch] = useState("");
  const [page, setPage] = useState(0);

  const query = useQuery({
    queryKey: ["irb-families", page, appliedSearch],
    queryFn: () =>
      getIrbFamilies({
        page,
        size: 10,
        query: appliedSearch,
      }),
  });

  return (
    <Stack spacing={3}>
      <IrbArchiveTabs />

      <Card>
        <CardContent sx={{ p: 3 }}>
          <Typography variant="h4">Protocol Families</Typography>

          <Typography color="text.secondary" sx={{ mt: 1 }}>
            One row per protocol base, with the latest available version.
          </Typography>

          <TextField
            fullWidth
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter") {
                setPage(0);
                setAppliedSearch(search.trim());
              }
            }}
            placeholder="Protocol, document number, title, PI or email"
            sx={{ mt: 3 }}
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
          <Box sx={{ display: "grid", placeItems: "center", py: 10 }}>
            <CircularProgress />
          </Box>
        )}

        {query.isError && (
          <Alert severity="error">
            Protocol families could not be loaded.
          </Alert>
        )}

        {query.data && (
          <>
            <Box sx={{ px: 3, py: 2 }}>
              <Typography sx={{ fontWeight: 700 }}>
                {query.data.totalElements.toLocaleString()} families
              </Typography>
            </Box>

            <TableContainer>
              <Table>
                <TableHead>
                  <TableRow>
                    <TableCell>Protocol Base</TableCell>
                    <TableCell>Versions</TableCell>
                    <TableCell>Latest Protocol</TableCell>
                    <TableCell>Latest Title</TableCell>
                    <TableCell>Status</TableCell>
                    <TableCell>PI</TableCell>
                    <TableCell>Approval</TableCell>
                  </TableRow>
                </TableHead>

                <TableBody>
                  {query.data.content.map((family) => (
                    <TableRow key={family.protocolBase} hover>
                      <TableCell>{family.protocolBase}</TableCell>

                      <TableCell>
                        <Chip
                          label={family.versionCount.toLocaleString()}
                          size="small"
                        />
                      </TableCell>

                      <TableCell>
                        {family.latestProtocolNumber}
                      </TableCell>

                      <TableCell sx={{ maxWidth: 420 }}>
                        <Typography variant="body2" noWrap>
                          {family.latestTitle ?? "Untitled protocol"}
                        </Typography>
                      </TableCell>

                      <TableCell>
                        {family.latestStatus ?? "Unknown"}
                      </TableCell>

                      <TableCell>
                        {family.piEmail ?? family.piId ?? "—"}
                      </TableCell>

                      <TableCell>
                        {family.latestApprovalDate ?? "—"}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>

            <Box sx={{ display: "flex", justifyContent: "center", py: 3 }}>
              <Pagination
                page={page + 1}
                count={query.data.totalPages}
                onChange={(_, nextPage) => setPage(nextPage - 1)}
                color="primary"
                showFirstButton
                showLastButton
              />
            </Box>
          </>
        )}
      </Card>
    </Stack>
  );
}
