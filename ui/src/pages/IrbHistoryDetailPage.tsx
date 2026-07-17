import {
  ArrowBackOutlined,
  CalendarMonthOutlined,
  DescriptionOutlined,
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

import { getIrbHistoryVersion } from "../api/client";
import { IrbArchiveTabs } from "../components/IrbArchiveTabs";

function DetailField({
  label,
  value,
}: {
  label: string;
  value: string | number | null | undefined;
}) {
  return (
    <Box>
      <Typography variant="caption" color="text.secondary">
        {label}
      </Typography>

      <Typography sx={{ mt: 0.4, fontWeight: 600 }}>
        {value === null || value === undefined || value === ""
          ? "—"
          : value}
      </Typography>
    </Box>
  );
}

export function IrbHistoryDetailPage() {
  const navigate = useNavigate();
  const { protocolId } = useParams();

  const numericProtocolId = Number(protocolId);

  const versionQuery = useQuery({
    queryKey: ["irb-history-version", numericProtocolId],
    queryFn: () => getIrbHistoryVersion(numericProtocolId),
    enabled: Number.isFinite(numericProtocolId),
  });

  if (!Number.isFinite(numericProtocolId)) {
    return (
      <Alert severity="error">
        The historical protocol identifier is invalid.
      </Alert>
    );
  }

  if (versionQuery.isLoading) {
    return (
      <Box sx={{ display: "grid", placeItems: "center", minHeight: 400 }}>
        <CircularProgress />
      </Box>
    );
  }

  if (versionQuery.isError || !versionQuery.data) {
    return (
      <Alert severity="error">
        The historical protocol version could not be loaded.
      </Alert>
    );
  }

  const version = versionQuery.data;

  return (
    <Stack spacing={3}>
      <IrbArchiveTabs />

      <Button
        startIcon={<ArrowBackOutlined />}
        onClick={() => navigate(-1)}
        sx={{ alignSelf: "flex-start" }}
      >
        Back to historical versions
      </Button>

      <Card
        sx={{
          background:
            "linear-gradient(135deg, #ffffff 0%, #f8edf0 100%)",
        }}
      >
        <CardContent sx={{ p: { xs: 3, md: 4 } }}>
          <Stack
            spacing={2}
            direction={{ xs: "column", md: "row" }}
            justifyContent="space-between"
          >
            <Box>
              <Chip
                label="Historical IRB Version"
                color="primary"
                variant="outlined"
                size="small"
                sx={{ mb: 1.5 }}
              />

              <Typography variant="h4">
                {version.title ?? "Untitled protocol"}
              </Typography>

              <Typography color="text.secondary" sx={{ mt: 1 }}>
                Protocol {version.protocolNumber}
              </Typography>
            </Box>

            <Stack alignItems={{ xs: "flex-start", md: "flex-end" }}>
              <Typography variant="h4" sx={{ fontWeight: 800 }}>
                {version.documentNumber ?? "No document number"}
              </Typography>

              <Typography color="text.secondary">
                Document number
              </Typography>
            </Stack>
          </Stack>
        </CardContent>
      </Card>

      <Grid container spacing={3}>
        <Grid size={{ xs: 12, lg: 8 }}>
          <Card>
            <CardContent sx={{ p: 3 }}>
              <Stack direction="row" spacing={1.5} alignItems="center">
                <DescriptionOutlined color="primary" />
                <Typography variant="h6">Version details</Typography>
              </Stack>

              <Divider sx={{ my: 2.5 }} />

              <Grid container spacing={3}>
                <Grid size={{ xs: 12, sm: 6 }}>
                  <DetailField
                    label="Protocol base"
                    value={version.protocolBase}
                  />
                </Grid>

                <Grid size={{ xs: 12, sm: 6 }}>
                  <DetailField
                    label="Protocol number"
                    value={version.protocolNumber}
                  />
                </Grid>

                <Grid size={{ xs: 12, sm: 6 }}>
                  <DetailField
                    label="Sequence"
                    value={version.sequenceNumber}
                  />
                </Grid>

                <Grid size={{ xs: 12, sm: 6 }}>
                  <DetailField
                    label="CRC protocol number"
                    value={version.crcProtocolNumber}
                  />
                </Grid>

                <Grid size={{ xs: 12, sm: 6 }}>
                  <DetailField
                    label="Protocol type"
                    value={version.protocolType}
                  />
                </Grid>

                <Grid size={{ xs: 12, sm: 6 }}>
                  <DetailField
                    label="Protocol status"
                    value={version.protocolStatus}
                  />
                </Grid>
              </Grid>
            </CardContent>
          </Card>
        </Grid>

        <Grid size={{ xs: 12, lg: 4 }}>
          <Stack spacing={3}>
            <Card>
              <CardContent sx={{ p: 3 }}>
                <Stack direction="row" spacing={1.5} alignItems="center">
                  <PersonOutlined color="primary" />
                  <Typography variant="h6">Investigator</Typography>
                </Stack>

                <Divider sx={{ my: 2.5 }} />

                <Stack spacing={2}>
                  <DetailField label="PI ID" value={version.piId} />
                  <DetailField label="PI email" value={version.piEmail} />
                </Stack>
              </CardContent>
            </Card>

            <Card>
              <CardContent sx={{ p: 3 }}>
                <Stack direction="row" spacing={1.5} alignItems="center">
                  <CalendarMonthOutlined color="primary" />
                  <Typography variant="h6">Dates</Typography>
                </Stack>

                <Divider sx={{ my: 2.5 }} />

                <Stack spacing={2}>
                  <DetailField
                    label="Approval date"
                    value={version.approvalDate}
                  />
                  <DetailField
                    label="Expiration date"
                    value={version.expirationDate}
                  />
                </Stack>
              </CardContent>
            </Card>
          </Stack>
        </Grid>
      </Grid>

      <Button
        variant="outlined"
        onClick={() =>
          navigate(
            `/irb/history?query=${encodeURIComponent(
              version.protocolBase,
            )}`,
          )
        }
        sx={{ alignSelf: "flex-start" }}
      >
        View all versions for this study
      </Button>
    </Stack>
  );
}
