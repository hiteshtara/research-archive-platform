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

import { getProposalFamilies } from "../api/client";

export function ProposalFamiliesPage() {
  const navigate = useNavigate();
  const [search, setSearch] = useState("");
  const [appliedSearch, setAppliedSearch] = useState("");

  const query = useQuery({
    queryKey: ["proposal-families", appliedSearch],
    queryFn: ({ signal }) =>
      getProposalFamilies({
        query: appliedSearch,
        limit: 100,
      }, signal),
  });

  return (
    <Stack spacing={3}>
      <Card>
        <CardContent>
          <Typography variant="h4">Proposal Families</Typography>

          <Typography color="text.secondary" sx={{ mt: 1 }}>
            One row per Proposal Number.
          </Typography>

          <TextField
            fullWidth
            sx={{ mt: 3 }}
            placeholder="Proposal number, sponsor, title, lead unit, PI..."
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter") {
                setAppliedSearch(search.trim());
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
          <Alert severity="error">Unable to load Proposal families.</Alert>
        )}

        {query.data && (
          <>
            <Box sx={{ px: 3, py: 2 }}>
              <Typography sx={{ fontWeight: 700 }}>
                {query.data.length.toLocaleString()} Proposal Families
              </Typography>
            </Box>

            {query.data.length === 0 ? (
              <Box sx={{ px: 3, pb: 3 }}>
                <Alert severity="info">
                  No matching Proposal families were found.
                </Alert>
              </Box>
            ) : (
              <TableContainer>
                <Table>
                  <TableHead>
                    <TableRow>
                      <TableCell>Proposal Number</TableCell>
                      <TableCell>Version</TableCell>
                      <TableCell>Status</TableCell>
                      <TableCell>Sponsor</TableCell>
                      <TableCell>Lead Unit</TableCell>
                      <TableCell>Principal Investigator</TableCell>
                      <TableCell>Title</TableCell>
                      <TableCell />
                    </TableRow>
                  </TableHead>

                  <TableBody>
                    {query.data.map((proposal) => (
                      <TableRow
                        key={proposal.proposalNumber}
                        hover
                        sx={{ cursor: "pointer" }}
                        onClick={() =>
                          navigate(
                            `/proposals/${encodeURIComponent(
                              proposal.proposalNumber,
                            )}`,
                          )
                        }
                      >
                        <TableCell>
                          <Typography sx={{ fontWeight: 700 }}>
                            {proposal.proposalNumber}
                          </Typography>
                        </TableCell>
                        <TableCell>
                          <Chip
                            size="small"
                            label={proposal.latestVersionNumber}
                          />
                        </TableCell>
                        <TableCell>{proposal.status ?? "—"}</TableCell>
                        <TableCell>{proposal.sponsorName ?? "—"}</TableCell>
                        <TableCell>{proposal.leadUnitName ?? "—"}</TableCell>
                        <TableCell>
                          {proposal.principalInvestigator ?? "—"}
                        </TableCell>
                        <TableCell sx={{ maxWidth: 420 }}>
                          <Typography noWrap variant="body2">
                            {proposal.title ?? "Untitled Proposal"}
                          </Typography>
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
          </>
        )}
      </Card>
    </Stack>
  );
}
