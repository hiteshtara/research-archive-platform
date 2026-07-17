import {
  ArrowForwardOutlined,
  MenuBookOutlined,
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
  TextField,
  Typography,
} from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";

import { globalSearch } from "../api/client";
import type { GlobalSearchItem } from "../types/api";


function resultDetailPath(
  result: GlobalSearchItem,
): string | null {
  const recordId = Number(result.recordId);
  const protocolId = Number(result.protocolId);

  if (Number.isInteger(recordId) && recordId > 0) {
    return `/irb/record/${recordId}`;
  }

  if (Number.isInteger(protocolId) && protocolId > 0) {
    return `/irb/history/${protocolId}`;
  }

  return null;
}

export function GlobalSearchPage() {
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();

  const queryValue = searchParams.get("query") ?? "";
  const [searchText, setSearchText] = useState(queryValue);

  const searchQuery = useQuery({
    queryKey: ["global-search", queryValue],
    queryFn: () => globalSearch(queryValue),
    enabled: queryValue.trim().length >= 2,
  });

  const submitSearch = () => {
    const normalized = searchText.trim();

    if (normalized.length >= 2) {
      setSearchParams({ query: normalized });
    }
  };

  return (
    <Stack spacing={3}>
      <Box>
        <Chip
          label="Search across the archive"
          size="small"
          color="primary"
          variant="outlined"
          sx={{ mb: 1.5 }}
        />

        <Typography variant="h4">
          Global Search
        </Typography>

        <Typography color="text.secondary" sx={{ mt: 1 }}>
          Search by study ID, protocol number, document number, CRC number, title, investigator, sponsor, award, funding source, status, or review type.
        </Typography>
      </Box>

      <Card>
        <CardContent sx={{ p: 3 }}>
          <TextField
            fullWidth
            autoFocus
            value={searchText}
            onChange={(event) => setSearchText(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter") {
                submitSearch();
              }
            }}
            placeholder="Search document number, protocol, PI, sponsor, award, title..."
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

      {!queryValue && (
        <Card>
          <CardContent sx={{ p: 5 }}>
            <Stack
              spacing={2}
              sx={{
                alignItems: "center",
                textAlign: "center",
              }}
            >
              <SearchOutlined
                color="primary"
                sx={{ fontSize: 52 }}
              />

              <Typography variant="h6">
                Search the Research Data Hub
              </Typography>

              <Typography color="text.secondary">
                Try a document number, award number, sponsor, person name, study ID, protocol number, or title keyword.
              </Typography>
            </Stack>
          </CardContent>
        </Card>
      )}

      {searchQuery.isLoading && (
        <Box sx={{ display: "grid", placeItems: "center", py: 8 }}>
          <CircularProgress />
        </Box>
      )}

      {searchQuery.isError && (
        <Alert severity="error">
          Search results could not be loaded.
        </Alert>
      )}

      {searchQuery.data && (
        <Stack spacing={2}>
          <Typography sx={{ fontWeight: 700 }}>
            {searchQuery.data.totalResults.toLocaleString()} results for
            “{searchQuery.data.query}”
          </Typography>

          {searchQuery.data.results.length === 0 && (
            <Alert severity="info">
              No matching archive records were found.
            </Alert>
          )}

          {searchQuery.data.results.map((result) => (
            <Card
              key={`${result.module}-${result.recordId}`}
              onClick={() => {
                const detailPath = resultDetailPath(result);

                if (detailPath) {
                  navigate(detailPath);
                }
              }}
              sx={{
                cursor: "pointer",
                transition: "transform 150ms ease, box-shadow 150ms ease",
                "&:hover": {
                  transform: "translateY(-2px)",
                  boxShadow: "0 12px 28px rgba(15, 23, 42, 0.10)",
                },
              }}
            >
              <CardContent sx={{ p: 3 }}>
                <Stack
                  spacing={2}
                  sx={{
                    flexDirection: {
                      xs: "column",
                      md: "row",
                    },
                    justifyContent: "space-between",
                    alignItems: {
                      md: "center",
                    },
                  }}
                >
                  <Stack
                    spacing={2}
                    sx={{
                      flexDirection: "row",
                      alignItems: "flex-start",
                    }}
                  >
                    <Box
                      sx={{
                        width: 44,
                        height: 44,
                        borderRadius: 2,
                        display: "grid",
                        placeItems: "center",
                        flexShrink: 0,
                        backgroundColor: "rgba(139, 24, 50, 0.10)",
                        color: "primary.main",
                      }}
                    >
                      <MenuBookOutlined />
                    </Box>

                    <Box>
                      <Stack
                        spacing={1}
                        sx={{
                          flexDirection: "row",
                          alignItems: "center",
                          flexWrap: "wrap",
                          gap: 1,
                        }}
                      >
                        <Chip
                          label={result.module}
                          size="small"
                          color="primary"
                        />

                        {result.status && (
                          <Chip
                            label={result.status}
                            size="small"
                            variant="outlined"
                          />
                        )}
                      </Stack>

                      <Typography
                        variant="h6"
                        sx={{ mt: 1.5 }}
                      >
                        {result.title}
                      </Typography>

                      <Typography
                        variant="body2"
                        color="text.secondary"
                        sx={{ mt: 0.8 }}
                      >
                        {result.identifier}
                        {result.secondaryIdentifier
                          ? ` • ${result.secondaryIdentifier}`
                          : ""}
                        {result.personName
                          ? ` • ${result.personName}`
                          : ""}
                      </Typography>
                    </Box>
                  </Stack>

                  <ArrowForwardOutlined color="action" />
                </Stack>
              </CardContent>
            </Card>
          ))}
        </Stack>
      )}
    </Stack>
  );
}
