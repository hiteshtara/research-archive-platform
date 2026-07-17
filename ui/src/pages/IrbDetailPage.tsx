import {
  ArrowBackOutlined,
  AttachMoneyOutlined,
  DescriptionOutlined,
  EmailOutlined,
  FolderOutlined,
  HistoryOutlined,
  PersonOutlined,
  SecurityOutlined,
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
  Tab,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Tabs,
  Typography,
} from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { useNavigate, useParams } from "react-router-dom";

import {
  getIrbProtocolByRecordId,
  getIrbWorkspace,
} from "../api/client";

function DetailItem({
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
          ? "Not available"
          : value}
      </Typography>
    </Box>
  );
}

function EmptyState({
  title,
  description,
}: {
  title: string;
  description: string;
}) {
  return (
    <Card>
      <CardContent sx={{ p: 5 }}>
        <Stack
          spacing={1.5}
          sx={{
            alignItems: "center",
            textAlign: "center",
          }}
        >
          <FolderOutlined color="primary" sx={{ fontSize: 48 }} />

          <Typography variant="h6">{title}</Typography>

          <Typography color="text.secondary">
            {description}
          </Typography>
        </Stack>
      </CardContent>
    </Card>
  );
}

function formatDate(value: string | null | undefined): string {
  if (!value) {
    return "Not available";
  }

  const date = new Date(`${value}T00:00:00`);

  if (Number.isNaN(date.getTime())) {
    return value;
  }

  return new Intl.DateTimeFormat("en-US", {
    year: "numeric",
    month: "short",
    day: "numeric",
  }).format(date);
}

export function IrbDetailPage() {
  const navigate = useNavigate();
  const { recordId = "" } = useParams();
  const numericRecordId = Number(recordId);
  const [selectedTab, setSelectedTab] = useState(0);

  const validRecordId =
    Number.isInteger(numericRecordId) && numericRecordId > 0;

  const summaryQuery = useQuery({
    queryKey: ["irb-detail", numericRecordId],
    queryFn: () => getIrbProtocolByRecordId(numericRecordId),
    enabled: validRecordId,
  });

  const workspaceQuery = useQuery({
    queryKey: ["irb-workspace", numericRecordId],
    queryFn: () => getIrbWorkspace(numericRecordId),
    enabled: validRecordId,
  });

  if (!validRecordId) {
    return <Alert severity="error">Invalid IRB record ID.</Alert>;
  }

  if (summaryQuery.isLoading || workspaceQuery.isLoading) {
    return (
      <Box sx={{ display: "grid", placeItems: "center", minHeight: 400 }}>
        <CircularProgress />
      </Box>
    );
  }

  if (
    summaryQuery.isError ||
    workspaceQuery.isError ||
    !summaryQuery.data ||
    !workspaceQuery.data
  ) {
    return (
      <Alert severity="error">
        The IRB workspace could not be loaded.
      </Alert>
    );
  }

  const summary = summaryQuery.data;
  const workspace = workspaceQuery.data;
  const protocol = workspace.protocol;

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
          spacing={2}
          sx={{
            mt: 2,
            flexDirection: { xs: "column", md: "row" },
            justifyContent: "space-between",
          }}
        >
          <Box>
            <Typography color="text.secondary">
              Study {summary.studyId ?? protocol.protocolBase}
            </Typography>

            <Typography variant="h4" sx={{ mt: 0.5, maxWidth: 1000 }}>
              {protocol.title ?? summary.title}
            </Typography>

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
                label={protocol.protocolNumber}
                variant="outlined"
              />

              <Chip
                label={protocol.protocolType ?? "Type unavailable"}
                variant="outlined"
              />

              {protocol.sequenceNumber !== null &&
                protocol.sequenceNumber !== undefined && (
                  <Chip
                    label={`Version ${protocol.sequenceNumber}`}
                    variant="outlined"
                  />
                )}
            </Stack>
          </Box>

          <Stack
            spacing={1.5}
            sx={{
              alignItems: {
                xs: "flex-start",
                md: "flex-end",
              },
            }}
          >
            <Chip
              label={protocol.protocolStatus ?? "Unknown"}
              color="primary"
            />

            <Button
              variant="outlined"
              size="small"
              startIcon={<HistoryOutlined />}
              onClick={() =>
                navigate(
                  `/irb/history?query=${encodeURIComponent(
                    protocol.protocolBase,
                  )}`,
                )
              }
            >
              View historical versions
            </Button>
          </Stack>
        </Stack>
      </Box>

      <Grid container spacing={2}>
        <Grid size={{ xs: 6, md: 3 }}>
          <Card>
            <CardContent>
              <Typography variant="caption" color="text.secondary">
                Funding Sources
              </Typography>
              <Typography variant="h4" sx={{ mt: 0.5 }}>
                {workspace.funding.length}
              </Typography>
            </CardContent>
          </Card>
        </Grid>

        <Grid size={{ xs: 6, md: 3 }}>
          <Card>
            <CardContent>
              <Typography variant="caption" color="text.secondary">
                Submissions
              </Typography>
              <Typography variant="h4" sx={{ mt: 0.5 }}>
                {workspace.submissions.length}
              </Typography>
            </CardContent>
          </Card>
        </Grid>

        <Grid size={{ xs: 6, md: 3 }}>
          <Card>
            <CardContent>
              <Typography variant="caption" color="text.secondary">
                Timeline Events
              </Typography>
              <Typography variant="h4" sx={{ mt: 0.5 }}>
                {workspace.timeline.length}
              </Typography>
            </CardContent>
          </Card>
        </Grid>

        <Grid size={{ xs: 6, md: 3 }}>
          <Card>
            <CardContent>
              <Typography variant="caption" color="text.secondary">
                Calendar Days
              </Typography>
              <Typography variant="h4" sx={{ mt: 0.5 }}>
                {protocol.calendarDays ?? "—"}
              </Typography>
            </CardContent>
          </Card>
        </Grid>
      </Grid>

      <Card>
        <Tabs
          value={selectedTab}
          onChange={(_, value) => setSelectedTab(value)}
          variant="scrollable"
          scrollButtons="auto"
          sx={{
            px: 2,
            borderBottom: "1px solid #e7e9ee",
          }}
        >
          <Tab
            icon={<DescriptionOutlined />}
            iconPosition="start"
            label="Summary"
          />
          <Tab
            icon={<PersonOutlined />}
            iconPosition="start"
            label="People"
          />
          <Tab
            icon={<AttachMoneyOutlined />}
            iconPosition="start"
            label={`Funding (${workspace.funding.length})`}
          />
          <Tab
            icon={<FolderOutlined />}
            iconPosition="start"
            label="Documents"
          />
          <Tab
            icon={<HistoryOutlined />}
            iconPosition="start"
            label={`History (${workspace.timeline.length})`}
          />
          <Tab
            icon={<DescriptionOutlined />}
            iconPosition="start"
            label={`Submissions (${workspace.submissions.length})`}
          />
          <Tab
            icon={<SecurityOutlined />}
            iconPosition="start"
            label="Load Audit"
          />
        </Tabs>
      </Card>

      {selectedTab === 0 && (
        <Grid container spacing={2.5}>
          <Grid size={{ xs: 12, lg: 8 }}>
            <Card>
              <CardContent sx={{ p: 3 }}>
                <Typography variant="h6">Protocol overview</Typography>
                <Divider sx={{ my: 2.5 }} />

                <Grid container spacing={3}>
                  <Grid size={{ xs: 12, sm: 6 }}>
                    <DetailItem
                      label="Protocol base"
                      value={protocol.protocolBase}
                    />
                  </Grid>

                  <Grid size={{ xs: 12, sm: 6 }}>
                    <DetailItem
                      label="Protocol number"
                      value={protocol.protocolNumber}
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
                      label="Protocol status"
                      value={protocol.protocolStatus}
                    />
                  </Grid>

                  <Grid size={{ xs: 12, sm: 6 }}>
                    <DetailItem
                      label="CRC protocol number"
                      value={protocol.crcProtocolNumber}
                    />
                  </Grid>

                  <Grid size={{ xs: 12, sm: 6 }}>
                    <DetailItem
                      label="Document number"
                      value={protocol.documentNumber}
                    />
                  </Grid>

                  <Grid size={{ xs: 12, sm: 6 }}>
                    <DetailItem
                      label="Approval date"
                      value={formatDate(protocol.approvalDate)}
                    />
                  </Grid>

                  <Grid size={{ xs: 12, sm: 6 }}>
                    <DetailItem
                      label="Expiration date"
                      value={formatDate(protocol.expirationDate)}
                    />
                  </Grid>

                  <Grid size={{ xs: 12, sm: 6 }}>
                    <DetailItem
                      label="Closure date"
                      value={formatDate(protocol.closureDate)}
                    />
                  </Grid>

                  <Grid size={{ xs: 12, sm: 6 }}>
                    <DetailItem
                      label="Authorization date"
                      value={formatDate(protocol.authorizationDate)}
                    />
                  </Grid>

                  <Grid size={{ xs: 12 }}>
                    <DetailItem
                      label="Summary keywords"
                      value={protocol.summaryKeywords}
                    />
                  </Grid>

                  <Grid size={{ xs: 12 }}>
                    <DetailItem
                      label="OHRP categories"
                      value={protocol.ohrpCategories}
                    />
                  </Grid>
                </Grid>
              </CardContent>
            </Card>
          </Grid>

          <Grid size={{ xs: 12, lg: 4 }}>
            <Stack spacing={2.5}>
              <Card>
                <CardContent sx={{ p: 3 }}>
                  <Typography variant="h6">IRB processing</Typography>
                  <Divider sx={{ my: 2.5 }} />

                  <Stack spacing={2}>
                    <DetailItem
                      label="Received"
                      value={formatDate(protocol.receivedDate)}
                    />
                    <DetailItem
                      label="Claimed"
                      value={formatDate(protocol.claimedDate)}
                    />
                    <DetailItem
                      label="Determination"
                      value={formatDate(protocol.determinationDate)}
                    />
                    <DetailItem
                      label="IRB days"
                      value={protocol.irbDays}
                    />
                    <DetailItem
                      label="PI days"
                      value={protocol.piDays}
                    />
                  </Stack>
                </CardContent>
              </Card>

              <Card>
                <CardContent sx={{ p: 3 }}>
                  <Typography variant="h6">Archive metadata</Typography>
                  <Divider sx={{ my: 2.5 }} />

                  <Stack spacing={2}>
                    <DetailItem
                      label="Protocol ID"
                      value={protocol.protocolId}
                    />
                    <DetailItem
                      label="Sequence number"
                      value={protocol.sequenceNumber}
                    />
                    <DetailItem
                      label="Storage box"
                      value={protocol.recordStorageBox}
                    />
                    <DetailItem
                      label="Expiration status"
                      value={protocol.expirationStatus}
                    />
                  </Stack>
                </CardContent>
              </Card>
            </Stack>
          </Grid>
        </Grid>
      )}

      {selectedTab === 1 && (
        <Grid container spacing={2.5}>
          <Grid size={{ xs: 12, md: 6 }}>
            <Card>
              <CardContent sx={{ p: 3 }}>
                <Typography variant="h6">
                  Principal investigator
                </Typography>
                <Divider sx={{ my: 2.5 }} />

                <Stack spacing={2}>
                  <Stack
                    spacing={1.5}
                    sx={{
                      flexDirection: "row",
                      alignItems: "center",
                    }}
                  >
                    <PersonOutlined color="action" />
                    <DetailItem
                      label="Name"
                      value={summary.piFullName}
                    />
                  </Stack>

                  <Stack
                    spacing={1.5}
                    sx={{
                      flexDirection: "row",
                      alignItems: "center",
                    }}
                  >
                    <EmailOutlined color="action" />

                    <Box>
                      <Typography
                        variant="caption"
                        color="text.secondary"
                      >
                        Email
                      </Typography>

                      {protocol.piEmail ? (
                        <Button
                          variant="text"
                          size="small"
                          onClick={() =>
                            navigate(
                              `/investigators/${encodeURIComponent(
                                protocol.piEmail ?? "",
                              )}`,
                            )
                          }
                          sx={{
                            display: "block",
                            minWidth: 0,
                            mt: 0.2,
                            p: 0,
                            fontWeight: 600,
                            textTransform: "none",
                          }}
                        >
                          {protocol.piEmail}
                        </Button>
                      ) : (
                        <Typography sx={{ mt: 0.4, fontWeight: 600 }}>
                          Not available
                        </Typography>
                      )}
                    </Box>
                  </Stack>

                  {protocol.piEmail && (
                    <Button
                      variant="outlined"
                      size="small"
                      startIcon={<PersonOutlined />}
                      onClick={() =>
                        navigate(
                          `/search?query=${encodeURIComponent(
                            protocol.piEmail ?? "",
                          )}`,
                        )
                      }
                      sx={{ alignSelf: "flex-start" }}
                    >
                      View all studies by this investigator
                    </Button>
                  )}

                  <DetailItem
                    label="PI ID"
                    value={protocol.piId}
                  />

                  <DetailItem
                    label="Affiliation"
                    value={protocol.piAffiliation}
                  />
                </Stack>
              </CardContent>
            </Card>
          </Grid>

          <Grid size={{ xs: 12, md: 6 }}>
            <Card>
              <CardContent sx={{ p: 3 }}>
                <Typography variant="h6">
                  Organization
                </Typography>
                <Divider sx={{ my: 2.5 }} />

                <Stack spacing={2}>
                  <DetailItem
                    label="Fund center"
                    value={protocol.fundCenterNumber}
                  />

                  <DetailItem
                    label="School"
                    value={protocol.schoolNumber}
                  />

                  <DetailItem
                    label="IRB analyst"
                    value={protocol.irbAnalystId}
                  />

                  <DetailItem
                    label="IRB advisor"
                    value={protocol.irbAdvisorId}
                  />
                </Stack>
              </CardContent>
            </Card>
          </Grid>
        </Grid>
      )}

      {selectedTab === 2 && (
        <>
          {workspace.funding.length === 0 ? (
            <EmptyState
              title="No funding sources"
              description="No funding sources were present in the archived composite record."
            />
          ) : (
            <Grid container spacing={2}>
              {workspace.funding.map((funding, index) => (
                <Grid
                  key={`${funding.source}-${funding.sequence ?? index}`}
                  size={{ xs: 12, sm: 6, lg: 4 }}
                >
                  <Card sx={{ height: "100%" }}>
                    <CardContent sx={{ p: 3 }}>
                      <AttachMoneyOutlined color="primary" />

                      <Typography variant="h6" sx={{ mt: 2 }}>
                        {funding.source}
                      </Typography>

                      <Typography
                        variant="body2"
                        color="text.secondary"
                        sx={{ mt: 0.8 }}
                      >
                        Funding source {funding.sequence ?? index + 1}
                      </Typography>
                    </CardContent>
                  </Card>
                </Grid>
              ))}
            </Grid>
          )}
        </>
      )}

      {selectedTab === 3 && (
        <EmptyState
          title="Protocol documents"
          description="Documents will appear here when the Kuali document archive is copied to S3."
        />
      )}

      {selectedTab === 4 && (
        <>
          {workspace.timeline.length === 0 ? (
            <EmptyState
              title="No timeline events"
              description="No workflow dates were present in the archived record."
            />
          ) : (
            <Card>
              <CardContent sx={{ p: 3 }}>
                <Typography variant="h6">Protocol timeline</Typography>
                <Divider sx={{ my: 2.5 }} />

                <Stack spacing={0}>
                  {workspace.timeline.map((event, index) => (
                    <Box
                      key={`${event.date}-${event.type}-${index}`}
                      sx={{
                        display: "grid",
                        gridTemplateColumns: "28px 150px 1fr",
                        gap: 2,
                        pb: 3,
                        position: "relative",
                      }}
                    >
                      <Box
                        sx={{
                          width: 14,
                          height: 14,
                          borderRadius: "50%",
                          backgroundColor: "primary.main",
                          mt: 0.5,
                          position: "relative",
                          zIndex: 1,
                          "&::after":
                            index < workspace.timeline.length - 1
                              ? {
                                  content: '""',
                                  position: "absolute",
                                  top: 14,
                                  left: 6,
                                  width: 2,
                                  height: 52,
                                  backgroundColor: "#e1e4e8",
                                }
                              : undefined,
                        }}
                      />

                      <Typography
                        variant="body2"
                        color="text.secondary"
                      >
                        {formatDate(event.date)}
                      </Typography>

                      <Box>
                        <Typography sx={{ fontWeight: 700 }}>
                          {event.type}
                        </Typography>

                        {event.sequence !== null && (
                          <Typography
                            variant="caption"
                            color="text.secondary"
                          >
                            Sequence {event.sequence}
                          </Typography>
                        )}
                      </Box>
                    </Box>
                  ))}
                </Stack>
              </CardContent>
            </Card>
          )}
        </>
      )}

      {selectedTab === 5 && (
        <>
          {workspace.submissions.length === 0 ? (
            <EmptyState
              title="No submissions"
              description="No submission records were present for this protocol."
            />
          ) : (
            <Card>
              <CardContent sx={{ p: 0 }}>
                <Box sx={{ px: 3, py: 2.5 }}>
                  <Typography variant="h6">
                    Submission history
                  </Typography>
                </Box>

                <Divider />

                <TableContainer>
                  <Table>
                    <TableHead>
                      <TableRow>
                        <TableCell>Version</TableCell>
                        <TableCell>Submission</TableCell>
                        <TableCell>Type</TableCell>
                        <TableCell>Status</TableCell>
                        <TableCell>Event</TableCell>
                        <TableCell>Review</TableCell>
                      </TableRow>
                    </TableHead>

                    <TableBody>
                      {workspace.submissions.map((submission, index) => (
                        <TableRow
                          key={`${submission.sequenceNumber}-${submission.submissionNumber}-${index}`}
                          hover
                        >
                          <TableCell>
                            {submission.sequenceNumber ?? "—"}
                          </TableCell>
                          <TableCell>
                            {submission.submissionNumber ?? "—"}
                          </TableCell>
                          <TableCell>
                            {submission.submissionType ?? "—"}
                          </TableCell>
                          <TableCell>
                            <Chip
                              label={
                                submission.submissionStatus ?? "Unknown"
                              }
                              size="small"
                              variant="outlined"
                            />
                          </TableCell>
                          <TableCell>
                            {submission.eventType ?? "—"}
                          </TableCell>
                          <TableCell>
                            {submission.reviewType ?? "—"}
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </TableContainer>
              </CardContent>
            </Card>
          )}
        </>
      )}

      {selectedTab === 6 && (
        <Card>
          <CardContent sx={{ p: 3 }}>
            <Typography variant="h6">Archive load audit</Typography>
            <Divider sx={{ my: 2.5 }} />

            <Grid container spacing={3}>
              <Grid size={{ xs: 12, md: 4 }}>
                <DetailItem
                  label="Archive record ID"
                  value={summary.recordId}
                />
              </Grid>

              <Grid size={{ xs: 12, md: 4 }}>
                <DetailItem
                  label="Historical protocol ID"
                  value={protocol.protocolId}
                />
              </Grid>

              <Grid size={{ xs: 12, md: 4 }}>
                <DetailItem
                  label="Source system"
                  value="Kuali"
                />
              </Grid>

              <Grid size={{ xs: 12, md: 4 }}>
                <DetailItem
                  label="Current archive status"
                  value="Loaded"
                />
              </Grid>

              <Grid size={{ xs: 12, md: 4 }}>
                <DetailItem
                  label="Historical versions"
                  value={workspace.submissions.length}
                />
              </Grid>

              <Grid size={{ xs: 12, md: 4 }}>
                <DetailItem
                  label="Timeline events"
                  value={workspace.timeline.length}
                />
              </Grid>
            </Grid>
          </CardContent>
        </Card>
      )}
    </Stack>
  );
}
