import {
  ArrowBackOutlined,
  EmailOutlined,
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
  Typography,
} from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { useNavigate, useParams } from "react-router-dom";

import { getIrbProtocol } from "../api/client";

function DetailItem({
  label,
  value,
}: {
  label: string;
  value: string | null | undefined;
}) {
  return (
    <Box>
      <Typography variant="caption" color="text.secondary">
        {label}
      </Typography>
      <Typography sx={{ mt: 0.4 }} fontWeight={600}>
        {value || "Not available"}
      </Typography>
    </Box>
  );
}

export function IrbDetailPage() {
  const navigate = useNavigate();
  const { studyId = "" } = useParams();

  const protocolQuery = useQuery({
    queryKey: ["irb-detail", studyId],
    queryFn: () => getIrbProtocol(studyId),
    enabled: Boolean(studyId),
  });

  if (protocolQuery.isLoading) {
    return (
      <Box sx={{ display: "grid", placeItems: "center", minHeight: 400 }}>
        <CircularProgress />
      </Box>
    );
  }

  if (protocolQuery.isError || !protocolQuery.data) {
    return <Alert severity="error">The protocol could not be loaded.</Alert>;
  }

  const protocol = protocolQuery.data;

  return (
    <Stack spacing={3}>
      <Box>
        <Button
          startIcon={<ArrowBackOutlined />}
          onClick={() => navigate("/irb")}
        >
          Back to IRB
        </Button>

        <Stack
          direction={{ xs: "column", md: "row" }}
          justifyContent="space-between"
          spacing={2}
          sx={{ mt: 2 }}
        >
          <Box>
            <Typography color="text.secondary">
              Study {protocol.studyId ?? protocol.protocolBase}
            </Typography>
            <Typography variant="h4" sx={{ mt: 0.5, maxWidth: 950 }}>
              {protocol.title}
            </Typography>
          </Box>

          <Chip
            label={protocol.protocolStatus ?? "Unknown"}
            color="primary"
            sx={{ alignSelf: "flex-start" }}
          />
        </Stack>
      </Box>

      <Grid container spacing={2.5}>
        <Grid size={{ xs: 12, lg: 8 }}>
          <Card>
            <CardContent sx={{ p: 3 }}>
              <Typography variant="h6">Protocol summary</Typography>
              <Divider sx={{ my: 2.5 }} />

              <Grid container spacing={3}>
                <Grid size={{ xs: 12, sm: 6 }}>
                  <DetailItem
                    label="Protocol number"
                    value={protocol.protocolNumber}
                  />
                </Grid>

                <Grid size={{ xs: 12, sm: 6 }}>
                  <DetailItem
                    label="Protocol base"
                    value={protocol.protocolBase}
                  />
                </Grid>

                <Grid size={{ xs: 12, sm: 6 }}>
                  <DetailItem
                    label="Protocol type"
                    value={protocol.protocolType}
                  />
                </Grid>

                <Grid size={{ xs: 12, sm: 6 }}>
                  <DetailItem
                    label="Approval date"
                    value={protocol.approvalDate}
                  />
                </Grid>
              </Grid>
            </CardContent>
          </Card>
        </Grid>

        <Grid size={{ xs: 12, lg: 4 }}>
          <Card>
            <CardContent sx={{ p: 3 }}>
              <Typography variant="h6">
                Principal investigator
              </Typography>
              <Divider sx={{ my: 2.5 }} />

              <Stack spacing={2}>
                <Stack direction="row" spacing={1.5}>
                  <PersonOutlined color="action" />
                  <Box>
                    <Typography fontWeight={700}>
                      {protocol.piFullName ?? "Not available"}
                    </Typography>
                    <Typography variant="body2" color="text.secondary">
                      {protocol.piBuid ?? "No BUID"}
                    </Typography>
                  </Box>
                </Stack>

                <Stack direction="row" spacing={1.5}>
                  <EmailOutlined color="action" />
                  <Typography variant="body2">
                    {protocol.piEmail ?? "Email not available"}
                  </Typography>
                </Stack>
              </Stack>
            </CardContent>
          </Card>
        </Grid>
      </Grid>
    </Stack>
  );
}
