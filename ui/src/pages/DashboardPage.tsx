import {
  ArchiveOutlined,
  DescriptionOutlined,
  FolderOutlined,
  GavelOutlined,
  HandshakeOutlined,
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
  Grid,
  InputAdornment,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";

import { getDashboard } from "../api/client";

const cards = [
  {
    key: "irb",
    title: "IRB Protocols",
    description: "Protocols, investigators and review history",
    icon: <MenuBookOutlined />,
    path: "/irb",
  },
  {
    key: "awards",
    title: "Awards",
    description: "Award records and sponsor information",
    icon: <ArchiveOutlined />,
    path: "/awards",
  },
  {
    key: "proposals",
    title: "Proposals",
    description: "Institutional proposal archive",
    icon: <DescriptionOutlined />,
    path: "/proposals",
  },
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
    description: "Subaward history and organizations",
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
] as const;

export function DashboardPage() {
  const navigate = useNavigate();

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
          Search and review historical research administration records.
        </Typography>
      </Box>

      <TextField
        fullWidth
        placeholder="Search by study ID, protocol number, title or investigator"
        onKeyDown={(event) => {
          if (event.key === "Enter") {
            const value = (
              event.currentTarget as HTMLInputElement
            ).value.trim();

            if (value) {
              navigate(`/irb?query=${encodeURIComponent(value)}`);
            }
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
          maxWidth: 900,
          "& .MuiOutlinedInput-root": {
            backgroundColor: "white",
            minHeight: 58,
          },
        }}
      />

      <Grid container spacing={2.5}>
        {cards.map((card) => {
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

      <Card>
        <CardContent sx={{ p: 3 }}>
          <Typography variant="h6">Latest archive load</Typography>

          <Stack
            direction={{ xs: "column", sm: "row" }}
            spacing={3}
            sx={{ mt: 2 }}
          >
            <Box>
              <Typography variant="caption" color="text.secondary">
                Domain
              </Typography>
              <Typography sx={{ fontWeight: 700 }}>IRB</Typography>
            </Box>

            <Box>
              <Typography variant="caption" color="text.secondary">
                Records
              </Typography>
              <Typography sx={{ fontWeight: 700 }}>
                {dashboard.irb.toLocaleString()}
              </Typography>
            </Box>

            <Box>
              <Typography variant="caption" color="text.secondary">
                Status
              </Typography>
              <Chip label="Loaded" color="success" size="small" />
            </Box>
          </Stack>
        </CardContent>
      </Card>
    </Stack>
  );
}
