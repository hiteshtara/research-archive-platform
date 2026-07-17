import {
  ArrowBackOutlined,
  ArrowForwardOutlined,
  EmailOutlined,
  HistoryOutlined,
  MenuBookOutlined,
  PersonOutlined,
} from "@mui/icons-material";
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  CircularProgress,
  Divider,
  Grid,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
} from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { useNavigate, useParams } from "react-router-dom";

import { getInvestigatorProfile } from "../api/client";
import type { InvestigatorStudy } from "../types/api";

function studyPath(study: InvestigatorStudy): string {
  if (study.recordId !== null) {
    return `/irb/record/${study.recordId}`;
  }

  return `/irb/history/${study.protocolId}`;
}

function StudyTable({
  studies,
  emptyMessage,
}: {
  studies: InvestigatorStudy[];
  emptyMessage: string;
}) {
  const navigate = useNavigate();

  if (studies.length === 0) {
    return (
      <Alert severity="info" sx={{ m: 3 }}>
        {emptyMessage}
      </Alert>
    );
  }

  return (
    <TableContainer>
      <Table>
        <TableHead>
          <TableRow>
            <TableCell>Protocol</TableCell>
            <TableCell>Title</TableCell>
            <TableCell>Status</TableCell>
            <TableCell>Type</TableCell>
            <TableCell>Approval</TableCell>
            <TableCell />
          </TableRow>
        </TableHead>

        <TableBody>
          {studies.map((study) => (
            <TableRow
              key={`${study.recordId ?? "history"}-${study.protocolId}`}
              hover
              onClick={() => navigate(studyPath(study))}
              sx={{ cursor: "pointer" }}
            >
              <TableCell>
                <Typography sx={{ fontWeight: 700 }}>
                  {study.protocolNumber}
                </Typography>

                <Typography variant="caption" color="text.secondary">
                  Base {study.protocolBase}
                </Typography>
              </TableCell>

              <TableCell sx={{ maxWidth: 480 }}>
                <Typography variant="body2" noWrap>
                  {study.title ?? "Untitled protocol"}
                </Typography>
              </TableCell>

              <TableCell>
                <Chip
                  label={study.status ?? "Unknown"}
                  size="small"
                  variant="outlined"
                />
              </TableCell>

              <TableCell>{study.recordType ?? "—"}</TableCell>

              <TableCell>{study.approvalDate ?? "—"}</TableCell>

              <TableCell align="right">
                <ArrowForwardOutlined color="action" />
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TableContainer>
  );
}

export function InvestigatorProfilePage() {
  const navigate = useNavigate();
  const { email = "" } = useParams();

  const decodedEmail = decodeURIComponent(email);

  const profileQuery = useQuery({
    queryKey: ["investigator-profile", decodedEmail],
    queryFn: () => getInvestigatorProfile(decodedEmail),
    enabled: decodedEmail.trim().length > 0,
  });

  if (profileQuery.isLoading) {
    return (
      <Box sx={{ display: "grid", placeItems: "center", minHeight: 400 }}>
        <CircularProgress />
      </Box>
    );
  }

  if (profileQuery.isError || !profileQuery.data) {
    return (
      <Alert severity="error">
        The investigator profile could not be loaded.
      </Alert>
    );
  }

  const profile = profileQuery.data;

  return (
    <Stack spacing={3}>
      <Button
        startIcon={<ArrowBackOutlined />}
        onClick={() => navigate(-1)}
        sx={{ alignSelf: "flex-start" }}
      >
        Back
      </Button>

      <Card
        sx={{
          background:
            "linear-gradient(135deg, #ffffff 0%, #f8edf0 100%)",
        }}
      >
        <CardContent sx={{ p: { xs: 3, md: 4 } }}>
          <Grid container spacing={3} sx={{ alignItems: "center" }}>
            <Grid size={{ xs: 12, md: 8 }}>
              <Chip
                label="Investigator Profile"
                size="small"
                color="primary"
                variant="outlined"
                sx={{ mb: 1.5 }}
              />

              <Typography variant="h4">{profile.name}</Typography>

              <Stack
                spacing={1}
                sx={{
                  mt: 2,
                  flexDirection: "row",
                  flexWrap: "wrap",
                  gap: 1,
                }}
              >
                <Chip
                  icon={<EmailOutlined />}
                  label={profile.email}
                  variant="outlined"
                />

                <Chip
                  icon={<PersonOutlined />}
                  label={profile.buid ?? "BUID unavailable"}
                  variant="outlined"
                />
              </Stack>
            </Grid>

            <Grid size={{ xs: 12, md: 4 }}>
              <Grid container spacing={2}>
                <Grid size={{ xs: 6 }}>
                  <Card>
                    <CardContent>
                      <Typography
                        variant="caption"
                        color="text.secondary"
                      >
                        Current studies
                      </Typography>

                      <Typography variant="h4">
                        {profile.currentStudyCount}
                      </Typography>
                    </CardContent>
                  </Card>
                </Grid>

                <Grid size={{ xs: 6 }}>
                  <Card>
                    <CardContent>
                      <Typography
                        variant="caption"
                        color="text.secondary"
                      >
                        Historical studies
                      </Typography>

                      <Typography variant="h4">
                        {profile.historicalStudyCount}
                      </Typography>
                    </CardContent>
                  </Card>
                </Grid>
              </Grid>
            </Grid>
          </Grid>
        </CardContent>
      </Card>

      <Card>
        <Box sx={{ px: 3, py: 2.5 }}>
          <Stack
            sx={{
              flexDirection: "row",
              alignItems: "center",
              gap: 1.5,
            }}
          >
            <MenuBookOutlined color="primary" />
            <Typography variant="h6">Current Workspaces</Typography>
          </Stack>
        </Box>

        <Divider />

        <StudyTable
          studies={profile.currentStudies}
          emptyMessage="No current IRB workspaces were found for this investigator."
        />
      </Card>

      <Card>
        <Box sx={{ px: 3, py: 2.5 }}>
          <Stack
            sx={{
              flexDirection: "row",
              alignItems: "center",
              gap: 1.5,
            }}
          >
            <HistoryOutlined color="primary" />
            <Typography variant="h6">
              Historical Research Studies
            </Typography>
          </Stack>
        </Box>

        <Divider />

        <StudyTable
          studies={profile.historicalStudies}
          emptyMessage="No historical studies were found for this investigator."
        />
      </Card>
    </Stack>
  );
}
