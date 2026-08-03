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
  TextField,
  Typography,
} from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { useNavigate } from "react-router-dom";

import { searchAwardsV1 } from "../../api/client";
import { AwardStatusPill } from "../../components/award/AwardStatusPill";
import { formatCurrencyAmount } from "../../features/award/awardSectionsPresentation.mjs";
import { describeSearchResults } from "../../features/award/awardSearchPresentation.mjs";

const PAGE_SIZE = 25;

const SEARCH_DIMENSIONS = [
  "Award Number",
  "Partial Award Number",
  "Wildcard (*text*)",
  "PI",
  "Sponsor",
  "Lead Unit",
  "Title",
  "Document Number",
];

// Entry point of the primary Award workflow: Search -> Search Results ->
// Award Hierarchy -> Award Dashboard. Matches the approved mockup's
// search-hero + results-list presentation.
export function AwardSearchPage() {
  const navigate = useNavigate();

  const [query, setQuery] = useState("");
  const [appliedQuery, setAppliedQuery] = useState("");
  const [page, setPage] = useState(0);

  const searchQuery = useQuery({
    queryKey: ["award-search-v1", appliedQuery, page],
    queryFn: ({ signal }) =>
      searchAwardsV1({ q: appliedQuery, page, size: PAGE_SIZE }, signal),
    enabled: appliedQuery.trim().length > 0,
  });

  function runSearch(value: string) {
    setQuery(value);
    setAppliedQuery(value.trim());
    setPage(0);
  }

  const hasSearched = appliedQuery.trim().length > 0;

  return (
    <Stack spacing={4} sx={{ alignItems: "center" }}>
      <Box sx={{ maxWidth: 640, width: "100%", textAlign: "center", mt: 4 }}>
        <Typography variant="h4" sx={{ fontWeight: 700, mb: 1 }}>
          Find an Award
        </Typography>

        <Typography color="text.secondary" sx={{ mb: 4 }}>
          Search by Award number, PI, sponsor, lead unit, title, or document
          number. Use *text* for a wildcard search.
        </Typography>

        <TextField
          fullWidth
          autoFocus
          placeholder="105698, *105698*, Orsmond, NIH..."
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === "Enter") {
              runSearch(query);
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
          sx={{
            "& .MuiOutlinedInput-root": {
              fontSize: 16,
              borderRadius: 2.5,
            },
          }}
        />

        <Stack
          direction="row"
          spacing={1}
          sx={{ flexWrap: "wrap", justifyContent: "center", mt: 2 }}
        >
          {SEARCH_DIMENSIONS.map((dimension) => (
            <Chip
              key={dimension}
              label={dimension}
              size="small"
              variant="outlined"
            />
          ))}
        </Stack>
      </Box>

      {hasSearched && (
        <Box sx={{ maxWidth: 680, width: "100%" }}>
          {searchQuery.isLoading && (
            <Box sx={{ display: "grid", placeItems: "center", py: 6 }}>
              <CircularProgress />
            </Box>
          )}

          {searchQuery.isError && (
            <Alert severity="error">
              Unable to search Awards right now. Try again in a moment.
            </Alert>
          )}

          {searchQuery.data && (() => {
            const { totalElements, totalPages, content, exactDocumentMatch } =
              describeSearchResults(searchQuery.data);

            return (
            <>
              {exactDocumentMatch && (
                <Card
                  variant="outlined"
                  sx={{
                    mb: 2.5,
                    cursor: "pointer",
                    borderColor: "primary.main",
                    borderWidth: 2,
                    transition: "border-color .12s ease",
                    "&:hover": { borderColor: "primary.dark" },
                  }}
                  role="button"
                  tabIndex={0}
                  onClick={() =>
                    navigate(`/awards/${exactDocumentMatch.awardId}`)
                  }
                  onKeyDown={(event) => {
                    if (event.key === "Enter" || event.key === " ") {
                      event.preventDefault();
                      navigate(`/awards/${exactDocumentMatch.awardId}`);
                    }
                  }}
                >
                  <CardContent sx={{ "&:last-child": { pb: 2 } }}>
                    <Stack
                      direction="row"
                      spacing={1}
                      sx={{ alignItems: "center", mb: 0.5 }}
                    >
                      <Chip
                        label="Exact document number match"
                        color="primary"
                        size="small"
                      />
                      <AwardStatusPill status={exactDocumentMatch.status} />
                    </Stack>
                    <Typography sx={{ fontWeight: 700 }}>
                      {exactDocumentMatch.awardNumber} &middot;
                      sequence{" "}
                      {exactDocumentMatch.sequenceNumber}
                    </Typography>
                    <Typography variant="body2" color="text.secondary">
                      Document {exactDocumentMatch.workflowDocumentNumber}
                      {" "}&middot;{" "}
                      {exactDocumentMatch.title ?? "Untitled award"}
                    </Typography>
                  </CardContent>
                </Card>
              )}

              <Typography
                variant="overline"
                color="text.secondary"
                sx={{ display: "block", mb: 1.5 }}
              >
                {totalElements.toLocaleString()} award
                {totalElements === 1 ? "" : "s"} found
              </Typography>

              {content.length === 0 && !exactDocumentMatch && (
                <Typography color="text.secondary" sx={{ py: 2 }}>
                  No awards match &ldquo;{appliedQuery}&rdquo;.
                </Typography>
              )}

              <Stack spacing={1.25}>
                {content.map((hit) => (
                  <Card
                    key={hit.awardId}
                    variant="outlined"
                    sx={{
                      cursor: "pointer",
                      transition: "border-color .12s ease",
                      "&:hover": { borderColor: "primary.main" },
                    }}
                    role="button"
                    tabIndex={0}
                    onClick={() =>
                      navigate(
                        `/awards/hierarchy/${encodeURIComponent(
                          hit.awardNumber,
                        )}`,
                      )
                    }
                    onKeyDown={(event) => {
                      if (event.key === "Enter" || event.key === " ") {
                        event.preventDefault();
                        navigate(
                          `/awards/hierarchy/${encodeURIComponent(
                            hit.awardNumber,
                          )}`,
                        );
                      }
                    }}
                  >
                    <CardContent
                      sx={{
                        display: "flex",
                        justifyContent: "space-between",
                        alignItems: "center",
                        gap: 2,
                        "&:last-child": { pb: 2 },
                      }}
                    >
                      <Box sx={{ minWidth: 0 }}>
                        <Stack
                          direction="row"
                          spacing={1}
                          sx={{ alignItems: "center" }}
                        >
                          <Typography sx={{ fontWeight: 700 }}>
                            {hit.awardNumber}
                          </Typography>
                          <AwardStatusPill status={hit.status} />
                        </Stack>

                        <Typography
                          variant="body2"
                          color="text.secondary"
                          noWrap
                          sx={{ mt: 0.5 }}
                        >
                          {hit.title ?? "Untitled award"}
                        </Typography>

                        <Typography
                          variant="caption"
                          color="text.secondary"
                          sx={{ display: "block", mt: 0.25 }}
                        >
                          PI: {hit.principalInvestigator ?? "—"} &middot;{" "}
                          {hit.sponsor ?? "Sponsor unknown"}
                          {hit.leadUnit ? ` · ${hit.leadUnit}` : ""}
                        </Typography>
                      </Box>

                      <Typography sx={{ fontWeight: 700, whiteSpace: "nowrap" }}>
                        {formatCurrencyAmount(hit.currentObligatedAmount)}
                      </Typography>
                    </CardContent>
                  </Card>
                ))}
              </Stack>

              {totalPages > 1 && (
                <Stack sx={{ alignItems: "center", mt: 3 }}>
                  <Pagination
                    count={totalPages}
                    page={page + 1}
                    onChange={(_event, value) => setPage(value - 1)}
                  />
                </Stack>
              )}
            </>
            );
          })()}
        </Box>
      )}
    </Stack>
  );
}
