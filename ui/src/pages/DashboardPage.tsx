import {
  ArchiveOutlined,
  DescriptionOutlined,
  FolderOutlined,
  GavelOutlined,
  HandshakeOutlined,
  HistoryOutlined,
  SearchOutlined,
} from "@mui/icons-material";
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  Grid,
  InputAdornment,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Link as RouterLink, useNavigate } from "react-router-dom";

import { getDashboard } from "../api/client";
import { LoadingState } from "../components/common/LoadingState";
import {
  canSubmitSearchText,
  isSearchTextTooLong,
  searchLengthHelperText,
} from "../features/common/searchPresentation.mjs";
import {
  futureModuleCards,
  historicalActivityCards,
  primaryBusinessCards,
} from "../features/dashboard/dashboardPresentation.mjs";
import type { DashboardSummary } from "../types/api";

// Icons are JSX and can't live in the plain-data presentation-helper
// module, so each card's icon is looked up here by key instead.
const CARD_ICONS: Record<string, React.ReactNode> = {
  awards: <ArchiveOutlined />,
  proposals: <DescriptionOutlined />,
  awardHistoryRecords: <HistoryOutlined />,
  proposalHistoryRecords: <HistoryOutlined />,
  negotiations: <HandshakeOutlined />,
  subawards: <GavelOutlined />,
  documents: <FolderOutlined />,
};

export function DashboardPage() {
  const navigate = useNavigate();
  const [searchText, setSearchText] = useState("");

  // The dashboard has its own search box rather than the shared one, so
  // it needs the same length rule applied explicitly (QA TC-018) - it
  // sends people to Global Search, which would otherwise refuse the
  // query only after the navigation. The existing two-character minimum
  // is unchanged.
  const searchTooLong = isSearchTextTooLong(searchText);
  const searchHelperText = searchLengthHelperText(searchText);

  function submitSearch() {
    const normalized = searchText.trim();

    if (normalized.length >= 2 && canSubmitSearchText(searchText)) {
      navigate(`/search?query=${encodeURIComponent(normalized)}`);
    }
  }

  const dashboardQuery = useQuery({
    queryKey: ["dashboard"],
    queryFn: getDashboard,
  });

  if (dashboardQuery.isLoading) {
    return <LoadingState />;
  }

  if (dashboardQuery.isError || !dashboardQuery.data) {
    return (
      <Alert severity="error">
        The dashboard data could not be loaded.
      </Alert>
    );
  }

  const dashboard = dashboardQuery.data;

  return (
    <Stack spacing={4}>
      <Box>
        <Chip label="Research Archive" size="small" sx={{ mb: 1.5 }} />

        <Typography variant="h4">
          Welcome to the Research Archive
        </Typography>

        <Typography color="text.secondary" sx={{ mt: 1 }}>
          Search and review archived Awards, Proposals, Negotiations,
          Subawards, and Kuali business documents.
        </Typography>
      </Box>

      <Stack
        component="form"
        onSubmit={(event) => {
          event.preventDefault();
          submitSearch();
        }}
        sx={{
          maxWidth: 1050,
          flexDirection: {
            xs: "column",
            sm: "row",
          },
          gap: 1.5,
        }}
      >
        <TextField
          fullWidth
          value={searchText}
          onChange={(event) => setSearchText(event.target.value)}
          error={searchTooLong}
          helperText={searchHelperText ?? undefined}
          placeholder="Search document number, PI, sponsor, award, title..."
          slotProps={{
            input: {
              "aria-invalid": searchTooLong,
              startAdornment: (
                <InputAdornment position="start">
                  <SearchOutlined />
                </InputAdornment>
              ),
            },
          }}
          sx={{
            "& .MuiOutlinedInput-root": {
              backgroundColor: "white",
              minHeight: 58,
            },
          }}
        />

        <Button
          type="submit"
          variant="contained"
          size="large"
          disabled={searchText.trim().length < 2 || searchTooLong}
          sx={{
            minWidth: 140,
            minHeight: 58,
          }}
        >
          Search
        </Button>
      </Stack>

      <Box>
        <Typography variant="h6">Awards and Proposals</Typography>

        <Typography color="text.secondary" sx={{ mt: 0.5, mb: 2.5 }}>
          Current records and complete preserved history for the
          archive's two core business objects.
        </Typography>

        <Grid container spacing={2.5}>
          {[...primaryBusinessCards, ...historicalActivityCards].map(
            (card) => {
              const value = dashboard[card.key as keyof DashboardSummary];

              return (
                <Grid key={card.key} size={{ xs: 12, sm: 6, lg: 3 }}>
                  {/*
                    * A real link, not a div that navigates (QA TC-053).
                    * The card used to be role="button" with tabIndex and
                    * a keydown handler: that gave keyboard access, but a
                    * control with no address cannot be middle-clicked
                    * into a new tab, cannot have its address copied, and
                    * is announced as a button although it navigates.
                    * Rendering the Card as a RouterLink gives all of
                    * that from the platform, and lets the hand-rolled
                    * Enter/Space handling go.
                    */}
                  <Card
                    component={RouterLink}
                    to={card.path}
                    sx={{
                      height: "100%",
                      display: "block",
                      textDecoration: "none",
                      color: "inherit",
                      cursor: "pointer",
                      transition:
                        "transform 160ms ease, box-shadow 160ms ease",
                      "&:hover": {
                        transform: "translateY(-3px)",
                        boxShadow: "0 14px 30px rgba(15, 23, 42, 0.10)",
                      },
                    }}
                  >
                    <CardContent sx={{ p: 3 }}>
                      <Stack
                        sx={{
                          flexDirection: "row",
                          alignItems: "flex-start",
                          justifyContent: "space-between",
                        }}
                      >
                        <Box
                          sx={{
                            width: 46,
                            height: 46,
                            borderRadius: 2.5,
                            display: "grid",
                            placeItems: "center",
                            backgroundColor: "rgba(139, 24, 50, 0.10)",
                            color: "primary.main",
                          }}
                        >
                          {CARD_ICONS[card.key]}
                        </Box>

                        <Typography variant="h4">
                          {value.toLocaleString()}
                        </Typography>
                      </Stack>

                      <Typography variant="h6" sx={{ mt: 3 }}>
                        {card.title}
                      </Typography>

                      <Typography
                        variant="body2"
                        color="text.secondary"
                        sx={{ mt: 0.5 }}
                      >
                        {card.description}
                      </Typography>
                    </CardContent>
                  </Card>
                </Grid>
              );
            },
          )}
        </Grid>
      </Box>

      <Box>
        <Typography variant="h6">Additional modules</Typography>

        <Typography color="text.secondary" sx={{ mt: 0.5, mb: 2.5 }}>
          Other archive domains and document collections.
        </Typography>

        <Grid container spacing={2.5}>
          {futureModuleCards.map((card) => {
            const value = dashboard[card.key as keyof DashboardSummary];

            return (
              <Grid key={card.key} size={{ xs: 12, sm: 6, lg: 4 }}>
                {/* Same as the cards above - a real link. */}
                <Card
                  component={RouterLink}
                  to={card.path}
                  sx={{
                    height: "100%",
                    display: "block",
                    textDecoration: "none",
                    color: "inherit",
                    cursor: "pointer",
                    opacity: value === 0 ? 0.78 : 1,
                  }}
                >
                  <CardContent sx={{ p: 3 }}>
                    <Stack
                      sx={{
                        flexDirection: "row",
                        alignItems: "flex-start",
                        justifyContent: "space-between",
                      }}
                    >
                      <Box
                        sx={{
                          width: 46,
                          height: 46,
                          borderRadius: 2.5,
                          display: "grid",
                          placeItems: "center",
                          backgroundColor: "rgba(15, 23, 42, 0.06)",
                          color: "text.secondary",
                        }}
                      >
                        {CARD_ICONS[card.key]}
                      </Box>

                      <Typography variant="h4">
                        {value.toLocaleString()}
                      </Typography>
                    </Stack>

                    <Typography variant="h6" sx={{ mt: 3 }}>
                      {card.title}
                    </Typography>

                    <Typography
                      variant="body2"
                      color="text.secondary"
                      sx={{ mt: 0.5 }}
                    >
                      {card.description}
                    </Typography>
                  </CardContent>
                </Card>
              </Grid>
            );
          })}
        </Grid>
      </Box>
    </Stack>
  );
}
