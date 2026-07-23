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

import { getNegotiations } from "../api/client";

const pageSize = 25;

function display(value: string | number | null) {
  return value ?? "—";
}

export function NegotiationFamiliesPage() {
  const navigate = useNavigate();
  const [search, setSearch] = useState("");
  const [appliedSearch, setAppliedSearch] = useState("");
  const [page, setPage] = useState(0);

  const query = useQuery({
    queryKey: ["negotiations", appliedSearch, page],
    queryFn: () =>
      getNegotiations({
        query: appliedSearch,
        page,
        size: pageSize,
      }),
  });

  const applySearch = () => {
    setAppliedSearch(search.trim());
    setPage(0);
  };

  return (
    <Stack spacing={3}>
      <Card>
        <CardContent>
          <Typography variant="h4">Negotiations</Typography>

          <Typography color="text.secondary" sx={{ mt: 1 }}>
            Search archived agreements and their associated Kuali records.
          </Typography>

          <TextField
            fullWidth
            sx={{ mt: 3 }}
            placeholder="Negotiation ID, document, type, status, association, negotiator..."
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
          <Alert severity="error">Unable to load Negotiations.</Alert>
        )}

        {query.data && (
          <>
            <Box sx={{ px: 3, py: 2 }}>
              <Typography sx={{ fontWeight: 700 }}>
                {query.data.totalElements.toLocaleString()} Negotiations
              </Typography>
            </Box>

            {query.data.content.length === 0 ? (
              <Box sx={{ px: 3, pb: 3 }}>
                <Alert severity="info">
                  No matching Negotiations were found.
                </Alert>
              </Box>
            ) : (
              <TableContainer>
                <Table>
                  <TableHead>
                    <TableRow>
                      <TableCell>Negotiation ID</TableCell>
                      <TableCell>Status</TableCell>
                      <TableCell>Agreement Type</TableCell>
                      <TableCell>Association Type</TableCell>
                      <TableCell>Associated Document</TableCell>
                      <TableCell>Negotiator</TableCell>
                      <TableCell>Start Date</TableCell>
                      <TableCell />
                    </TableRow>
                  </TableHead>

                  <TableBody>
                    {query.data.content.map((negotiation) => (
                      <TableRow
                        key={negotiation.negotiationId}
                        hover
                        sx={{ cursor: "pointer" }}
                        onClick={() =>
                          navigate(
                            `/negotiations/${encodeURIComponent(
                              negotiation.negotiationId,
                            )}`,
                          )
                        }
                      >
                        <TableCell>
                          <Typography sx={{ fontWeight: 700 }}>
                            {negotiation.negotiationId}
                          </Typography>
                          <Typography variant="caption" color="text.secondary">
                            {display(negotiation.documentNumber)}
                          </Typography>
                        </TableCell>
                        <TableCell>
                          <Chip
                            size="small"
                            variant="outlined"
                            label={display(
                              negotiation.negotiationStatusDescription,
                            )}
                          />
                        </TableCell>
                        <TableCell>
                          {display(
                            negotiation.negotiationAgreementTypeDescription,
                          )}
                        </TableCell>
                        <TableCell>
                          {display(
                            negotiation.negotiationAssociationTypeDescription,
                          )}
                        </TableCell>
                        <TableCell>
                          {display(negotiation.associatedDocumentId)}
                        </TableCell>
                        <TableCell>
                          {display(negotiation.negotiatorFullName)}
                        </TableCell>
                        <TableCell>
                          {display(negotiation.negotiationStartDate)}
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
