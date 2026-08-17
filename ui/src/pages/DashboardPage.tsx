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
import { useNavigate } from "react-router-dom";

import { getDashboard } from "../api/client";
import { LoadingState } from "../components/common/LoadingState";
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

  function submitSearch() {
    const normalized = searchText.trim();

    if (normalized.length >= 2) {
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
          placeholder="Search document number, PI, sponsor, award, title..."
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
              backgroundColor: "white",
              minHeight: 58,
            },
          }}
        />

        <Button
          type="submit"
          variant="contained"
          size="large"
          disabled={searchText.trim().length < 2}
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
                  <Card
                    role="button"
                    tabIndex={0}
                    onClick={() => navigate(card.path)}
                    onKeyDown={(event) => {
                      if (event.key === "Enter" || event.key === " ") {
                        event.preventDefault();
                        navigate(card.path);
                      }
                    }}
                    sx={{
                      height: "100%",
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
                <Card
                  role="button"
                  tabIndex={0}
                  onClick={() => navigate(card.path)}
                  onKeyDown={(event) => {
                    if (event.key === "Enter" || event.key === " ") {
                      event.preventDefault();
                      navigate(card.path);
                    }
                  }}
                  sx={{
                    height: "100%",
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
