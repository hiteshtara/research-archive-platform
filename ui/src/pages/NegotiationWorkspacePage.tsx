import {
  Alert,
  Box,
  Card,
  CardContent,
  Chip,
  CircularProgress,
  Stack,
  Tab,
  Tabs,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
} from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { useParams } from "react-router-dom";

import {
  getNegotiationActivities,
  getNegotiationCustomData,
  getNegotiationNotifications,
  getNegotiationUnassociatedDetails,
  getNegotiationWorkspace,
} from "../api/client";

const tabs = [
  "General",
  "Activities",
  "Custom Data",
  "Notifications",
  "Unassociated Details",
];

function display(value: string | number | null) {
  return value ?? "—";
}

function DetailTable({
  rows,
}: {
  rows: Array<[string, string | number | null]>;
}) {
  return (
    <Table size="small">
      <TableBody>
        {rows.map(([label, value]) => (
          <TableRow key={label}>
            <TableCell sx={{ width: 280, fontWeight: 600 }}>{label}</TableCell>
            <TableCell sx={{ whiteSpace: "pre-wrap", overflowWrap: "anywhere" }}>
              {display(value)}
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}

function LoadingState() {
  return (
    <Box sx={{ display: "grid", placeItems: "center", py: 8 }}>
      <CircularProgress />
    </Box>
  );
}

export function NegotiationWorkspacePage() {
  const { negotiationId } = useParams();

  return (
    <NegotiationWorkspaceContent
      key={negotiationId}
      negotiationId={negotiationId}
    />
  );
}

function NegotiationWorkspaceContent({
  negotiationId,
}: {
  negotiationId: string | undefined;
}) {
  const [activeTab, setActiveTab] = useState(0);
  const parsedNegotiationId = Number(negotiationId);
  const validNegotiationId =
    Number.isSafeInteger(parsedNegotiationId) && parsedNegotiationId > 0;

  const workspaceQuery = useQuery({
    queryKey: ["negotiation-workspace", parsedNegotiationId],
    enabled: validNegotiationId,
    queryFn: () => getNegotiationWorkspace(parsedNegotiationId),
  });

  const activitiesQuery = useQuery({
    queryKey: ["negotiation-activities", parsedNegotiationId],
    enabled: validNegotiationId && activeTab === 1,
    queryFn: () => getNegotiationActivities(parsedNegotiationId),
  });

  const customDataQuery = useQuery({
    queryKey: ["negotiation-custom-data", parsedNegotiationId],
    enabled: validNegotiationId && activeTab === 2,
    queryFn: () => getNegotiationCustomData(parsedNegotiationId),
  });

  const notificationsQuery = useQuery({
    queryKey: ["negotiation-notifications", parsedNegotiationId],
    enabled: validNegotiationId && activeTab === 3,
    queryFn: () => getNegotiationNotifications(parsedNegotiationId),
  });

  const unassociatedQuery = useQuery({
    queryKey: ["negotiation-unassociated", parsedNegotiationId],
    enabled: validNegotiationId && activeTab === 4,
    queryFn: () => getNegotiationUnassociatedDetails(parsedNegotiationId),
  });

  if (!validNegotiationId) {
    return <Alert severity="error">Invalid Negotiation ID.</Alert>;
  }

  if (workspaceQuery.isLoading) {
    return <LoadingState />;
  }

  if (workspaceQuery.isError || !workspaceQuery.data) {
    return <Alert severity="error">Unable to load Negotiation workspace.</Alert>;
  }

  const current = workspaceQuery.data.current;
  const generalRows: Array<[string, string | number | null]> = [
    ["Negotiation ID", current.negotiationId],
    ["Document Number", current.documentNumber],
    ["Status ID", current.negotiationStatusId],
    ["Status Code", current.negotiationStatusCode],
    ["Status", current.negotiationStatusDescription],
    ["Agreement Type ID", current.negotiationAgreementTypeId],
    ["Agreement Type Code", current.negotiationAgreementTypeCode],
    ["Agreement Type", current.negotiationAgreementTypeDescription],
    ["Association Type ID", current.negotiationAssociationTypeId],
    ["Association Type Code", current.negotiationAssociationTypeCode],
    ["Association Type", current.negotiationAssociationTypeDescription],
    ["Associated Document ID", current.associatedDocumentId],
    ["Negotiator Person ID", current.negotiatorPersonId],
    ["Negotiator", current.negotiatorFullName],
    ["Negotiation Start Date", current.negotiationStartDate],
    ["Negotiation End Date", current.negotiationEndDate],
    ["Anticipated Award Date", current.anticipatedAwardDate],
    ["Document Folder", current.documentFolder],
    ["Source Update Timestamp", current.sourceUpdateTimestamp],
    ["Source Update User", current.sourceUpdateUser],
    ["Source Version Number", current.sourceVersionNumber],
    ["Source Object ID", current.sourceObjectId],
    ["Document Update Timestamp", current.documentSourceUpdateTimestamp],
    ["Document Update User", current.documentSourceUpdateUser],
    ["Document Version Number", current.documentSourceVersionNumber],
    ["Document Object ID", current.documentSourceObjectId],
  ];

  return (
    <Stack spacing={3}>
      <Card>
        <CardContent>
          <Typography variant="h4">
            Negotiation {current.negotiationId}
          </Typography>

          <Typography variant="h6" sx={{ mt: 1 }}>
            {current.negotiationAgreementTypeDescription ??
              "Unspecified agreement type"}
          </Typography>

          <Typography color="text.secondary" sx={{ mt: 1 }}>
            Negotiator: {current.negotiatorFullName ?? "Unknown"}
          </Typography>

          <Stack
            sx={{
              mt: 3,
              flexDirection: "row",
              alignItems: "center",
              flexWrap: "wrap",
              gap: 1,
            }}
          >
            <Chip
              size="small"
              color="primary"
              label={current.negotiationStatusDescription ?? "Unknown status"}
            />
            <Chip
              size="small"
              variant="outlined"
              label={`Association: ${
                current.negotiationAssociationTypeDescription ?? "Unknown"
              }`}
            />
            <Chip
              size="small"
              variant="outlined"
              label={`Associated ID: ${current.associatedDocumentId ?? "—"}`}
            />
          </Stack>
        </CardContent>
      </Card>

      <Card>
        <Tabs
          value={activeTab}
          onChange={(_, nextTab) => setActiveTab(nextTab)}
          variant="scrollable"
          scrollButtons="auto"
          sx={{ px: 2, borderBottom: "1px solid", borderColor: "divider" }}
        >
          {tabs.map((tab) => (
            <Tab key={tab} label={tab} />
          ))}
        </Tabs>

        <CardContent>
          {activeTab === 0 && <DetailTable rows={generalRows} />}

          {activeTab === 1 && (
            <Stack spacing={2}>
              {activitiesQuery.isLoading && <LoadingState />}
              {activitiesQuery.isError && (
                <Alert severity="error">
                  Unable to load Negotiation activities.
                </Alert>
              )}
              {activitiesQuery.data && (
                <>
                  <Typography sx={{ fontWeight: 700 }}>
                    {activitiesQuery.data.length.toLocaleString()} activities
                  </Typography>
                  {activitiesQuery.data.length === 0 ? (
                    <Alert severity="info">
                      No activities are archived for this Negotiation.
                    </Alert>
                  ) : (
                    <TableContainer>
                      <Table size="small">
                        <TableHead>
                          <TableRow>
                            <TableCell>Activity</TableCell>
                            <TableCell>Location</TableCell>
                            <TableCell>Start</TableCell>
                            <TableCell>End</TableCell>
                            <TableCell>Follow-up</TableCell>
                            <TableCell>Description</TableCell>
                            <TableCell>Restricted</TableCell>
                          </TableRow>
                        </TableHead>
                        <TableBody>
                          {activitiesQuery.data.map((activity) => (
                            <TableRow key={activity.negotiationActivityId} hover>
                              <TableCell>
                                {display(activity.activityTypeDescription)}
                                <Typography
                                  variant="caption"
                                  color="text.secondary"
                                  sx={{ display: "block" }}
                                >
                                  {display(activity.activityTypeCode)}
                                </Typography>
                              </TableCell>
                              <TableCell>
                                {display(activity.locationDescription)}
                              </TableCell>
                              <TableCell>{display(activity.startDate)}</TableCell>
                              <TableCell>{display(activity.endDate)}</TableCell>
                              <TableCell>
                                {display(activity.followupDate)}
                              </TableCell>
                              <TableCell sx={{ minWidth: 280 }}>
                                <Typography
                                  variant="body2"
                                  sx={{ whiteSpace: "pre-wrap" }}
                                >
                                  {display(activity.description)}
                                </Typography>
                              </TableCell>
                              <TableCell>{display(activity.restricted)}</TableCell>
                            </TableRow>
                          ))}
                        </TableBody>
                      </Table>
                    </TableContainer>
                  )}
                </>
              )}
            </Stack>
          )}

          {activeTab === 2 && (
            <Stack spacing={2}>
              {customDataQuery.isLoading && <LoadingState />}
              {customDataQuery.isError && (
                <Alert severity="error">
                  Unable to load Negotiation custom data.
                </Alert>
              )}
              {customDataQuery.data && (
                <>
                  <Typography sx={{ fontWeight: 700 }}>
                    {customDataQuery.data.length.toLocaleString()} custom values
                  </Typography>
                  {customDataQuery.data.length === 0 ? (
                    <Alert severity="info">
                      No custom data is archived for this Negotiation.
                    </Alert>
                  ) : (
                    <TableContainer>
                      <Table size="small">
                        <TableHead>
                          <TableRow>
                            <TableCell>Custom Attribute ID</TableCell>
                            <TableCell>Negotiation Number</TableCell>
                            <TableCell>Value</TableCell>
                            <TableCell>Updated</TableCell>
                          </TableRow>
                        </TableHead>
                        <TableBody>
                          {customDataQuery.data.map((item) => (
                            <TableRow key={item.negotiationCustomDataId} hover>
                              <TableCell>
                                {display(item.customAttributeId)}
                              </TableCell>
                              <TableCell>
                                {display(item.negotiationNumber)}
                              </TableCell>
                              <TableCell sx={{ whiteSpace: "pre-wrap" }}>
                                {display(item.value)}
                              </TableCell>
                              <TableCell>
                                {display(item.sourceUpdateTimestamp)}
                              </TableCell>
                            </TableRow>
                          ))}
                        </TableBody>
                      </Table>
                    </TableContainer>
                  )}
                </>
              )}
            </Stack>
          )}

          {activeTab === 3 && (
            <Stack spacing={2}>
              {notificationsQuery.isLoading && <LoadingState />}
              {notificationsQuery.isError && (
                <Alert severity="error">
                  Unable to load Negotiation notifications.
                </Alert>
              )}
              {notificationsQuery.data &&
                (notificationsQuery.data.length === 0 ? (
                  <Alert severity="info">
                    No archived notifications are available. The Negotiation
                    notification source currently contains no records.
                  </Alert>
                ) : (
                  <TableContainer>
                    <Table size="small">
                      <TableHead>
                        <TableRow>
                          <TableCell>Notification ID</TableCell>
                          <TableCell>Type ID</TableCell>
                          <TableCell>Recipients</TableCell>
                          <TableCell>Subject</TableCell>
                          <TableCell>Message</TableCell>
                          <TableCell>Updated</TableCell>
                        </TableRow>
                      </TableHead>
                      <TableBody>
                        {notificationsQuery.data.map((notification) => (
                          <TableRow key={notification.notificationId} hover>
                            <TableCell>{notification.notificationId}</TableCell>
                            <TableCell>
                              {display(notification.notificationTypeId)}
                            </TableCell>
                            <TableCell>
                              {display(notification.recipients)}
                            </TableCell>
                            <TableCell>{display(notification.subject)}</TableCell>
                            <TableCell sx={{ whiteSpace: "pre-wrap" }}>
                              {display(notification.message)}
                            </TableCell>
                            <TableCell>
                              {display(notification.sourceUpdateTimestamp)}
                            </TableCell>
                          </TableRow>
                        ))}
                      </TableBody>
                    </Table>
                  </TableContainer>
                ))}
            </Stack>
          )}

          {activeTab === 4 && (
            <Stack spacing={2}>
              {unassociatedQuery.isLoading && <LoadingState />}
              {unassociatedQuery.isError && (
                <Alert severity="error">
                  Unable to load unassociated Negotiation details.
                </Alert>
              )}
              {unassociatedQuery.data && (
                <>
                  <Typography sx={{ fontWeight: 700 }}>
                    {unassociatedQuery.data.length.toLocaleString()} detail rows
                  </Typography>
                  {unassociatedQuery.data.length === 0 ? (
                    <Alert severity="info">
                      No unassociated details are archived for this Negotiation.
                    </Alert>
                  ) : (
                    <Stack spacing={2}>
                      {unassociatedQuery.data.map((detail) => (
                        <Card
                          key={detail.negotiationUnassocDetailId}
                          variant="outlined"
                        >
                          <CardContent>
                            <Typography variant="h6">
                              {detail.title ?? "Untitled detail"}
                            </Typography>
                            <DetailTable
                              rows={[
                                ["Principal Investigator", detail.piName],
                                ["PI Person ID", detail.piPersonId],
                                ["PI Rolodex ID", detail.piRolodexId],
                                ["Lead Unit", detail.leadUnit],
                                ["Sponsor Code", detail.sponsorCode],
                                ["Prime Sponsor Code", detail.primeSponsorCode],
                                [
                                  "Sponsor Award Number",
                                  detail.sponsorAwardNumber,
                                ],
                                [
                                  "Contact Administrator Person ID",
                                  detail.contactAdminPersonId,
                                ],
                                ["Subaward Organization", detail.subawardOrg],
                                [
                                  "Source Update Timestamp",
                                  detail.sourceUpdateTimestamp,
                                ],
                              ]}
                            />
                          </CardContent>
                        </Card>
                      ))}
                    </Stack>
                  )}
                </>
              )}
            </Stack>
          )}
        </CardContent>
      </Card>
    </Stack>
  );
}
