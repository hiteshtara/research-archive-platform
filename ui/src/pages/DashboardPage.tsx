import {
  ArchiveOutlined,
  DescriptionOutlined,
  FolderOutlined,
  GavelOutlined,
  HandshakeOutlined,
  HistoryOutlined,
  MenuBookOutlined,
  SearchOutlined,
  TimelineOutlined,
} from "@mui/icons-material";
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  CircularProgress,
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
import type { DashboardSummary } from "../types/api";

const primaryBusinessCards: Array<{
  key: keyof DashboardSummary;
  title: string;
  description: string;
  icon: React.ReactNode;
  path: string;
}> = [
  {
    key: "irb",
    title: "Current IRB Records",
    description: "Current curated IRB records available for search",
    icon: <MenuBookOutlined />,
    path: "/irb",
  },
  {
    key: "protocolFamilies",
    title: "Protocol Families",
    description: "Unique IRB protocol bases across the historical archive",
    icon: <ArchiveOutlined />,
    path: "/irb",
  },
  {
    key: "awards",
    title: "Awards",
    description: "Distinct institutional award numbers",
    icon: <ArchiveOutlined />,
    path: "/awards",
  },
  {
    key: "proposals",
    title: "Proposals",
    description: "Distinct institutional proposal numbers",
    icon: <DescriptionOutlined />,
    path: "/proposals",
  },
];

const historicalActivityCards: Array<{
  key: keyof DashboardSummary;
  title: string;
  description: string;
  icon: React.ReactNode;
  path: string;
}> = [
  {
    key: "protocolVersions",
    title: "Historical Protocol Versions",
    description: "All historical protocol versions loaded from Kuali",
    icon: <HistoryOutlined />,
    path: "/irb",
  },
  {
    key: "awardHistoryRecords",
    title: "Historical Award Records",
    description: "All preserved Kuali Award source rows",
    icon: <HistoryOutlined />,
    path: "/awards",
  },
  {
    key: "proposalHistoryRecords",
    title: "Historical Proposal Records",
    description: "All preserved Kuali Proposal history rows",
    icon: <HistoryOutlined />,
    path: "/proposals",
  },
  {
    key: "submissions",
    title: "Submissions",
    description: "Initial applications, amendments, renewals and other submissions",
    icon: <DescriptionOutlined />,
    path: "/irb",
  },
  {
    key: "fundingRecords",
    title: "Funding Relationships",
    description: "Archived protocol funding source relationships",
    icon: <GavelOutlined />,
    path: "/irb",
  },
  {
    key: "timelineEvents",
    title: "Timeline Events",
    description: "Historical workflow and review events",
    icon: <TimelineOutlined />,
    path: "/irb",
  },
];

const futureModuleCards: Array<{
  key: keyof DashboardSummary;
  title: string;
  description: string;
  icon: React.ReactNode;
  path: string;
}> = [
  {
    key: "negotiations",
    title: "Negotiations",
    description: "Agreement and negotiation records",
    icon: <HandshakeOutlined />,
    path: "/negotiations",
  },
  {
    key: "subawards",
    title: "Subawards",
    description: "Distinct institutional subaward codes",
    icon: <GavelOutlined />,
    path: "/subawards",
  },
  {
    key: "documents",
    title: "Documents",
    description: "Legacy files and attachments",
    icon: <FolderOutlined />,
    path: "/documents",
  },
];

export function DashboardPage() {
  const navigate = useNavigate();
  const [searchText, setSearchText] = useState("");

  function submitSearch() {
    const normalized = searchText.trim();

    if (normalized) {
      navigate(`/search?query=${encodeURIComponent(normalized)}`);
    }
  }

  const dashboardQuery = useQuery({
    queryKey: ["dashboard"],
    queryFn: getDashboard,
  });

  if (dashboardQuery.isLoading) {
    return (
      <Box sx={{ display: "grid", placeItems: "center", minHeight: 400 }}>
        <CircularProgress />
      </Box>
    );
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
          Search and review current and historical research administration
          records.
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
          disabled={!searchText.trim()}
          sx={{
            minWidth: 140,
            minHeight: 58,
          }}
        >
          Search
        </Button>
      </Stack>

      <Box>
        <Typography variant="h6">Primary business objects</Typography>

        <Typography color="text.secondary" sx={{ mt: 0.5, mb: 2.5 }}>
          Current records and stable institutional business identifiers.
        </Typography>

        <Grid container spacing={2.5}>
          {primaryBusinessCards.map((card) => {
            const value = dashboard[card.key];

            return (
              <Grid key={card.key} size={{ xs: 12, sm: 6, lg: 4 }}>
                <Card
                  onClick={() => navigate(card.path)}
                  sx={{
                    height: "100%",
                    cursor: "pointer",
                    transition: "transform 160ms ease, box-shadow 160ms ease",
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
                        {card.icon}
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

      <Box>
        <Typography variant="h6">
          Historical and activity metrics
        </Typography>

        <Typography color="text.secondary" sx={{ mt: 0.5, mb: 2.5 }}>
          Complete preserved history and relationships across archive
          domains.
        </Typography>

        <Grid container spacing={2.5}>
          {historicalActivityCards.map((card) => {
            const value = dashboard[card.key];

            return (
              <Grid key={card.key} size={{ xs: 12, sm: 6, lg: 4 }}>
                <Card
                  onClick={() => navigate(card.path)}
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
                          backgroundColor: "rgba(15, 23, 42, 0.06)",
                          color: "text.secondary",
                        }}
                      >
                        {card.icon}
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

      <Box>
        <Typography variant="h6">Additional modules</Typography>

        <Typography color="text.secondary" sx={{ mt: 0.5, mb: 2.5 }}>
          Other archive domains and document collections.
        </Typography>

        <Grid container spacing={2.5}>
          {futureModuleCards.map((card) => {
            const value = dashboard[card.key];

            return (
              <Grid key={card.key} size={{ xs: 12, sm: 6, lg: 4 }}>
                <Card
                  onClick={() => navigate(card.path)}
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
                        {card.icon}
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

      <Card>
        <CardContent sx={{ p: 3 }}>
          <Typography variant="h6">Latest IRB archive load</Typography>

          <Grid container spacing={3} sx={{ mt: 0.5 }}>
            <Grid size={{ xs: 6, sm: 4, md: 2 }}>
              <Typography variant="caption" color="text.secondary">
                Current records
              </Typography>
              <Typography sx={{ fontWeight: 700 }}>
                {dashboard.irb.toLocaleString()}
              </Typography>
            </Grid>

            <Grid size={{ xs: 6, sm: 4, md: 2 }}>
              <Typography variant="caption" color="text.secondary">
                Protocol families
              </Typography>
              <Typography sx={{ fontWeight: 700 }}>
                {dashboard.protocolFamilies.toLocaleString()}
              </Typography>
            </Grid>

            <Grid size={{ xs: 6, sm: 4, md: 2 }}>
              <Typography variant="caption" color="text.secondary">
                Historical versions
              </Typography>
              <Typography sx={{ fontWeight: 700 }}>
                {dashboard.protocolVersions.toLocaleString()}
              </Typography>
            </Grid>

            <Grid size={{ xs: 6, sm: 4, md: 2 }}>
              <Typography variant="caption" color="text.secondary">
                Submissions
              </Typography>
              <Typography sx={{ fontWeight: 700 }}>
                {dashboard.submissions.toLocaleString()}
              </Typography>
            </Grid>

            <Grid size={{ xs: 6, sm: 4, md: 2 }}>
              <Typography variant="caption" color="text.secondary">
                Timeline events
              </Typography>
              <Typography sx={{ fontWeight: 700 }}>
                {dashboard.timelineEvents.toLocaleString()}
              </Typography>
            </Grid>

            <Grid size={{ xs: 6, sm: 4, md: 2 }}>
              <Typography variant="caption" color="text.secondary">
                Status
              </Typography>
              <Box sx={{ mt: 0.4 }}>
                <Chip label="Loaded" color="success" size="small" />
              </Box>
            </Grid>
          </Grid>
        </CardContent>
      </Card>
    </Stack>
  );
}
