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

import { getIrbHistory } from "../api/client";
import { IrbArchiveTabs } from "../components/IrbArchiveTabs";

export function IrbHistoryPage() {
  const navigate = useNavigate();
  const [search, setSearch] = useState("");
  const [appliedSearch, setAppliedSearch] = useState("");
  const [page, setPage] = useState(0);

  const query = useQuery({
    queryKey: ["irb-history", page, appliedSearch],
    queryFn: () =>
      getIrbHistory({
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
          <Typography variant="h4">Historical IRB Versions</Typography>

          <Typography color="text.secondary" sx={{ mt: 1 }}>
            Every historical protocol version loaded from the Kuali archive.
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
            placeholder="Document number, protocol, CRC, title, PI or status"
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
            Historical versions could not be loaded.
          </Alert>
        )}

        {query.data && (
          <>
            <Box sx={{ px: 3, py: 2 }}>
              <Typography sx={{ fontWeight: 700 }}>
                {query.data.totalElements.toLocaleString()} versions
              </Typography>
            </Box>

            <TableContainer>
              <Table>
                <TableHead>
                  <TableRow>
                    <TableCell>Document #</TableCell>
                    <TableCell>Protocol</TableCell>
                    <TableCell>Sequence</TableCell>
                    <TableCell>Title</TableCell>
                    <TableCell>Status</TableCell>
                    <TableCell>Type</TableCell>
                    <TableCell>PI</TableCell>
                    <TableCell>Approval</TableCell>
                    <TableCell />
                  </TableRow>
                </TableHead>

                <TableBody>
                  {query.data.content.map((version) => (
                    <TableRow
                      key={version.protocolId}
                      hover
                      onClick={() =>
                        navigate(
                          `/irb/history?query=${encodeURIComponent(
                            version.protocolBase,
                          )}`,
                        )
                      }
                      sx={{
                        cursor: "pointer",
                        "&:hover": {
                          backgroundColor: "rgba(139, 24, 50, 0.035)",
                        },
                      }}
                    >
                      <TableCell>
                        {version.documentNumber ?? "—"}
                      </TableCell>

                      <TableCell>{version.protocolNumber}</TableCell>

                      <TableCell>
                        {version.sequenceNumber ?? "—"}
                      </TableCell>

                      <TableCell sx={{ maxWidth: 420 }}>
                        <Typography variant="body2" noWrap>
                          {version.title ?? "Untitled protocol"}
                        </Typography>
                      </TableCell>

                      <TableCell>
                        <Chip
                          label={version.protocolStatus ?? "Unknown"}
                          size="small"
                          variant="outlined"
                        />
                      </TableCell>

                      <TableCell>
                        {version.protocolType ?? "—"}
                      </TableCell>

                      <TableCell>
                        {version.piEmail ?? version.piId ?? "—"}
                      </TableCell>

                      <TableCell>
                        {version.approvalDate ?? "—"}
                      </TableCell>

                      <TableCell align="right">
                        <ArrowForwardOutlined color="action" />
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
