import {
  Alert,
  Box,
  Card,
  CardContent,
  Chip,
  CircularProgress,
  Stack,
  Tab,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Tabs,
  Typography,
} from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";

import {
  getProtocolActions,
  getProtocolAmendRenewals,
  getProtocolFunding,
  getProtocolHistory,
  getProtocolLocations,
  getProtocolPersonnel,
  getProtocolResearchAreas,
  getProtocolSubmissions,
} from "../api/client";

const show = (value: string | number | null) => value ?? "—";
const tabs = [
  "Overview",
  "History",
  "Personnel",
  "Funding",
  "Research Areas",
  "Locations",
  "Submissions",
  "Actions",
  "Amend/Renewals",
];

export function ProtocolWorkspacePage() {
  const { protocolNumber } = useParams();
  const [tab, setTab] = useState(0);
  const [protocolId, setProtocolId] = useState<number | null>(null);
  const history = useQuery({
    queryKey: ["protocol-history", protocolNumber],
    enabled: !!protocolNumber,
    queryFn: () => getProtocolHistory(protocolNumber!),
  });
  const personnel = useQuery({
    queryKey: ["protocol-personnel", protocolId],
    enabled: protocolId !== null,
    queryFn: () => getProtocolPersonnel(protocolId!),
  });
  const funding = useQuery({
    queryKey: ["protocol-funding", protocolId],
    enabled: tab === 3 && protocolId !== null,
    queryFn: () => getProtocolFunding(protocolId!),
  });
  const researchAreas = useQuery({
    queryKey: ["protocol-research-areas", protocolId],
    enabled: tab === 4 && protocolId !== null,
    queryFn: () => getProtocolResearchAreas(protocolId!),
  });
  const locations = useQuery({
    queryKey: ["protocol-locations", protocolId],
    enabled: tab === 5 && protocolId !== null,
    queryFn: () => getProtocolLocations(protocolId!),
  });
  const submissions = useQuery({
    queryKey: ["protocol-submissions", protocolId],
    enabled: tab === 6 && protocolId !== null,
    queryFn: () => getProtocolSubmissions(protocolId!),
  });
  const actions = useQuery({
    queryKey: ["protocol-actions", protocolId],
    enabled: tab === 7 && protocolId !== null,
    queryFn: () => getProtocolActions(protocolId!),
  });
  const amendRenewals = useQuery({
    queryKey: ["protocol-amend-renewals", protocolId],
    enabled: tab === 8 && protocolId !== null,
    queryFn: () => getProtocolAmendRenewals(protocolId!),
  });

  useEffect(() => {
    setTab(0);
    setProtocolId(null);
  }, [protocolNumber]);
  useEffect(() => {
    if (history.data?.length && protocolId === null) {
      setProtocolId(history.data[0].protocolId);
    }
  }, [history.data, protocolId]);

  if (history.isLoading) return <CircularProgress />;
  if (history.isError || !history.data) {
    return <Alert severity="error">Unable to load Protocol history.</Alert>;
  }
  const selected =
    history.data.find((version) => version.protocolId === protocolId) ??
    history.data[0];
  const leadAssignment = personnel.data
    ?.flatMap((person) =>
      person.units.map((unit) => ({ person, unit })),
    )
    .find(({ unit }) => unit.leadUnitFlag?.toUpperCase() === "Y");
  const principalInvestigator =
    leadAssignment?.person ??
    personnel.data?.find((person) => {
      const role = person.protocolPersonRoleId?.trim().toUpperCase();
      return role === "PI" || role === "PRINCIPAL INVESTIGATOR";
    });
  const personnelMetadata = personnel.isLoading ? "Loading…" : "—";
  const metadata: Array<{
    label: string;
    value: string | number;
    secondary?: string;
  }> = [
    { label: "Protocol Number", value: selected.protocolNumber },
    { label: "Sequence Number", value: selected.sequenceNumber },
    {
      label: "Protocol Type",
      value: show(selected.protocolTypeDescription),
    },
    {
      label: "Current Status",
      value: show(selected.protocolStatusDescription),
    },
    {
      label: "Principal Investigator",
      value: principalInvestigator?.personName ?? personnelMetadata,
    },
    {
      label: "Lead Unit",
      value:
        selected.leadUnitName ??
        leadAssignment?.unit.unitName ??
        (personnel.isLoading ? "Loading…" : "Unit name unavailable"),
      secondary:
        selected.leadUnitNumber ??
        leadAssignment?.unit.unitNumber ??
        undefined,
    },
    { label: "Approval Date", value: show(selected.approvalDate) },
    { label: "Expiration Date", value: show(selected.expirationDate) },
  ];

  return (
    <Stack spacing={3}>
      <Card>
        <CardContent>
          <Typography
            variant="h4"
            component="h1"
            sx={{ maxWidth: 1100, overflowWrap: "anywhere" }}
          >
            {selected.title ?? "Untitled Protocol"}
          </Typography>
          <Box
            sx={{
              display: "grid",
              gridTemplateColumns: {
                xs: "minmax(0, 1fr)",
                sm: "repeat(2, minmax(0, 1fr))",
                md: "repeat(4, minmax(0, 1fr))",
              },
              gap: 1.25,
              mt: 2.5,
            }}
          >
            {metadata.map(({ label, value, secondary }) => (
              <Box
                key={label}
                sx={{
                  minWidth: 0,
                  border: "1px solid",
                  borderColor: "divider",
                  borderRadius: 1.5,
                  px: 1.5,
                  py: 1.1,
                  bgcolor: "background.default",
                }}
              >
                <Typography
                  variant="caption"
                  color="text.secondary"
                  sx={{ display: "block", fontWeight: 700 }}
                >
                  {label}
                </Typography>
                {label === "Current Status" ? (
                  <Chip
                    label={value}
                    size="small"
                    variant="outlined"
                    sx={{ mt: 0.5, maxWidth: "100%" }}
                  />
                ) : (
                  <Typography
                    variant="body2"
                    sx={{
                      mt: 0.25,
                      fontWeight: 600,
                      overflowWrap: "anywhere",
                    }}
                  >
                    {value}
                  </Typography>
                )}
                {secondary && (
                  <Typography
                    variant="caption"
                    color="text.secondary"
                    sx={{ display: "block", mt: 0.25 }}
                  >
                    {secondary}
                  </Typography>
                )}
              </Box>
            ))}
          </Box>
        </CardContent>
      </Card>
      <Tabs
        value={tab}
        onChange={(_, value) => setTab(value)}
        variant="scrollable"
        scrollButtons="auto"
        aria-label="Protocol detail sections"
      >
        {tabs.map((label) => (
          <Tab key={label} label={label} />
        ))}
      </Tabs>
      {tab === 0 && (
        <Card>
          <CardContent>
            <Typography variant="h6">Study overview</Typography>
            <Typography
              color="text.secondary"
              sx={{ mt: 1, whiteSpace: "pre-wrap" }}
            >
              {selected.description ?? "No protocol description is archived."}
            </Typography>
            <Stack
              direction={{ xs: "column", sm: "row" }}
              spacing={1}
              sx={{ mt: 2, flexWrap: "wrap" }}
            >
              <Chip
                size="small"
                label={`Protocol ID ${selected.protocolId}`}
              />
              <Chip
                size="small"
                variant="outlined"
                label={`Document ${show(selected.documentNumber)}`}
              />
              <Chip
                size="small"
                variant="outlined"
                label={`Active ${show(selected.active)}`}
              />
            </Stack>
          </CardContent>
        </Card>
      )}
      {tab === 1 && (
        <Card>
          <Table>
            <TableHead>
              <TableRow>
                <TableCell>Sequence</TableCell>
                <TableCell>Protocol ID</TableCell>
                <TableCell>Status</TableCell>
                <TableCell>Title</TableCell>
                <TableCell>Approval</TableCell>
                <TableCell>Expiration</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {history.data.map((version) => (
                <TableRow
                  hover
                  selected={version.protocolId === selected.protocolId}
                  key={version.protocolId}
                  sx={{ cursor: "pointer" }}
                  onClick={() => setProtocolId(version.protocolId)}
                >
                  <TableCell>{version.sequenceNumber}</TableCell>
                  <TableCell>{version.protocolId}</TableCell>
                  <TableCell>
                    {show(version.protocolStatusDescription)}
                  </TableCell>
                  <TableCell>{show(version.title)}</TableCell>
                  <TableCell>{show(version.approvalDate)}</TableCell>
                  <TableCell>{show(version.expirationDate)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Card>
      )}
      {tab === 2 && personnel.isLoading && <CircularProgress />}
      {tab === 2 && personnel.isError && (
        <Alert severity="error">
          Unable to load Personnel for this exact Protocol version.
        </Alert>
      )}
      {tab === 2 && personnel.data?.length === 0 && (
        <Alert severity="info">
          No Personnel are archived for this exact Protocol version.
        </Alert>
      )}
      {tab === 2 && personnel.data && personnel.data.length > 0 && (
        <Card>
          <Table>
            <TableHead>
              <TableRow>
                <TableCell>Role</TableCell>
                <TableCell>Name</TableCell>
                <TableCell>Person ID</TableCell>
                <TableCell>Affiliation</TableCell>
                <TableCell>Units</TableCell>
                <TableCell>Comments</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {personnel.data.map((person) => (
                <TableRow key={person.protocolPersonId}>
                  <TableCell>
                    {show(person.protocolPersonRoleId)}
                  </TableCell>
                  <TableCell>{show(person.personName)}</TableCell>
                  <TableCell>{show(person.personId)}</TableCell>
                  <TableCell>
                    {show(person.affiliationTypeCode)}
                  </TableCell>
                  <TableCell>
                    {person.units.length
                      ? person.units
                          .map((unit) =>
                            `${unit.unitNumber ?? "—"}${
                              unit.leadUnitFlag === "Y" ? " (Lead)" : ""
                            }`,
                          )
                          .join(", ")
                      : "—"}
                  </TableCell>
                  <TableCell>{show(person.comments)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Card>
      )}
      {tab === 3 && funding.isLoading && <CircularProgress />}
      {tab === 3 && funding.isError && (
        <Alert severity="error">
          Unable to load Funding for this exact Protocol version.
        </Alert>
      )}
      {tab === 3 && funding.data?.length === 0 && (
        <Alert severity="info">
          No Funding sources are archived for this exact Protocol version.
        </Alert>
      )}
      {tab === 3 && funding.data && funding.data.length > 0 && (
        <Card>
          <Table>
            <TableHead>
              <TableRow>
                <TableCell>Type</TableCell>
                <TableCell>Source number</TableCell>
                <TableCell>Source name</TableCell>
                <TableCell>Updated</TableCell>
                <TableCell>Updated by</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {funding.data.map((source) => (
                <TableRow key={source.protocolFundingSourceId}>
                  <TableCell>
                    {show(source.fundingSourceTypeCode)}
                  </TableCell>
                  <TableCell>
                    {show(source.fundingSourceNumber)}
                  </TableCell>
                  <TableCell>
                    {show(source.fundingSourceName)}
                  </TableCell>
                  <TableCell>
                    {show(source.sourceUpdateTimestamp)}
                  </TableCell>
                  <TableCell>
                    {show(source.sourceUpdateUser)}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Card>
      )}
      {tab === 4 && researchAreas.isLoading && <CircularProgress />}
      {tab === 4 && researchAreas.isError && (
        <Alert severity="error">
          Unable to load Research Areas for this exact Protocol version.
        </Alert>
      )}
      {tab === 4 && researchAreas.data?.length === 0 && (
        <Alert severity="info">
          No Research Areas are archived for this exact Protocol version.
        </Alert>
      )}
      {tab === 4 &&
        researchAreas.data &&
        researchAreas.data.length > 0 && (
          <Card>
            <Table>
              <TableHead>
                <TableRow>
                  <TableCell>Research area code</TableCell>
                  <TableCell>Updated</TableCell>
                  <TableCell>Updated by</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {researchAreas.data.map((area) => (
                  <TableRow key={area.protocolResearchAreaId}>
                    <TableCell>{show(area.researchAreaCode)}</TableCell>
                    <TableCell>
                      {show(area.sourceUpdateTimestamp)}
                    </TableCell>
                    <TableCell>{show(area.sourceUpdateUser)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </Card>
        )}
      {tab === 5 && locations.isLoading && <CircularProgress />}
      {tab === 5 && locations.isError && (
        <Alert severity="error">
          Unable to load Locations for this exact Protocol version.
        </Alert>
      )}
      {tab === 5 && locations.data?.length === 0 && (
        <Alert severity="info">
          No Locations are archived for this exact Protocol version.
        </Alert>
      )}
      {tab === 5 && locations.data && locations.data.length > 0 && (
        <Card>
          <Table>
            <TableHead>
              <TableRow>
                <TableCell>Type</TableCell>
                <TableCell>Parent resolution</TableCell>
                <TableCell>Organization ID</TableCell>
                <TableCell>Rolodex ID</TableCell>
                <TableCell>Updated</TableCell>
                <TableCell>Updated by</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {locations.data.map((location) => (
                <TableRow key={location.protocolLocationId}>
                  <TableCell>
                    {show(location.protocolOrgTypeCode)}
                  </TableCell>
                  <TableCell>
                    {location.parentResolutionMethod}
                  </TableCell>
                  <TableCell>{show(location.organizationId)}</TableCell>
                  <TableCell>{show(location.rolodexId)}</TableCell>
                  <TableCell>
                    {show(location.sourceUpdateTimestamp)}
                  </TableCell>
                  <TableCell>{show(location.sourceUpdateUser)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Card>
      )}
      {tab === 6 && submissions.isLoading && <CircularProgress />}
      {tab === 6 && submissions.isError && (
        <Alert severity="error">
          Unable to load Submissions for this exact Protocol version.
        </Alert>
      )}
      {tab === 6 && submissions.data?.length === 0 && (
        <Alert severity="info">
          No Submissions are archived for this exact Protocol version.
        </Alert>
      )}
      {tab === 6 &&
        submissions.data &&
        submissions.data.length > 0 && (
          <Card>
            <Table>
              <TableHead>
                <TableRow>
                  <TableCell>Submission</TableCell>
                  <TableCell>Date</TableCell>
                  <TableCell>Type</TableCell>
                  <TableCell>Qualifier</TableCell>
                  <TableCell>Review type</TableCell>
                  <TableCell>Status</TableCell>
                  <TableCell>Committee</TableCell>
                  <TableCell>Schedule</TableCell>
                  <TableCell>Votes</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {submissions.data.map((submission) => (
                  <TableRow key={submission.submissionId}>
                    <TableCell>
                      {show(submission.submissionNumber)}
                    </TableCell>
                    <TableCell>
                      {show(submission.submissionDate)}
                    </TableCell>
                    <TableCell>
                      {show(submission.submissionTypeCode)}
                    </TableCell>
                    <TableCell>
                      {show(submission.submissionTypeQualCode)}
                    </TableCell>
                    <TableCell>
                      {show(submission.protocolReviewTypeCode)}
                    </TableCell>
                    <TableCell>
                      {show(submission.submissionStatusCode)}
                    </TableCell>
                    <TableCell>
                      {show(submission.committeeId)}
                    </TableCell>
                    <TableCell>
                      {show(submission.scheduleId)}
                    </TableCell>
                    <TableCell>
                      {`Yes ${show(submission.yesVoteCount)} · No ${show(
                        submission.noVoteCount,
                      )} · Abstain ${show(
                        submission.abstainerCount,
                      )} · Recused ${show(submission.recusedCount)}`}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </Card>
        )}
      {tab === 7 && actions.isLoading && <CircularProgress />}
      {tab === 7 && actions.isError && (
        <Alert severity="error">
          Unable to load Actions for this exact Protocol version.
        </Alert>
      )}
      {tab === 7 && actions.data?.length === 0 && (
        <Alert severity="info">
          No Actions are archived for this exact Protocol version.
        </Alert>
      )}
      {tab === 7 && actions.data && actions.data.length > 0 && (
        <Card>
          <Table>
            <TableHead>
              <TableRow>
                <TableCell>Action</TableCell>
                <TableCell>Action date</TableCell>
                <TableCell>Actual date</TableCell>
                <TableCell>Type</TableCell>
                <TableCell>Submission</TableCell>
                <TableCell>Previous protocol status</TableCell>
                <TableCell>Follow-up</TableCell>
                <TableCell>Comments</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {actions.data.map((action) => (
                <TableRow key={action.protocolActionId}>
                  <TableCell>{show(action.actionId)}</TableCell>
                  <TableCell>{show(action.actionDate)}</TableCell>
                  <TableCell>{show(action.actualActionDate)}</TableCell>
                  <TableCell>
                    {show(action.protocolActionTypeCode)}
                  </TableCell>
                  <TableCell>
                    {action.submissionNumber !== null
                      ? `${action.submissionNumber}${
                          action.submissionIdFk !== null
                            ? ` (${action.submissionIdFk})`
                            : ""
                        }`
                      : "—"}
                  </TableCell>
                  <TableCell>
                    {show(action.prevProtocolStatusCode)}
                  </TableCell>
                  <TableCell>
                    {show(action.followupActionCode)}
                  </TableCell>
                  <TableCell>{show(action.comments)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Card>
      )}
      {tab === 8 && amendRenewals.isLoading && <CircularProgress />}
      {tab === 8 && amendRenewals.isError && (
        <Alert severity="error">
          Unable to load Amendments/Renewals for this exact Protocol
          version.
        </Alert>
      )}
      {tab === 8 && amendRenewals.data?.length === 0 && (
        <Alert severity="info">
          No Amendments/Renewals are archived for this exact Protocol
          version.
        </Alert>
      )}
      {tab === 8 &&
        amendRenewals.data &&
        amendRenewals.data.length > 0 && (
          <Card>
            <Table>
              <TableHead>
                <TableRow>
                  <TableCell>Number</TableCell>
                  <TableCell>Date created</TableCell>
                  <TableCell>Summary</TableCell>
                  <TableCell>Updated</TableCell>
                  <TableCell>Updated by</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {amendRenewals.data.map((renewal) => (
                  <TableRow key={renewal.protoAmendRenewalId}>
                    <TableCell>
                      {show(renewal.protoAmendRenNumber)}
                    </TableCell>
                    <TableCell>{show(renewal.dateCreated)}</TableCell>
                    <TableCell>{show(renewal.summary)}</TableCell>
                    <TableCell>
                      {show(renewal.sourceUpdateTimestamp)}
                    </TableCell>
                    <TableCell>
                      {show(renewal.sourceUpdateUser)}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </Card>
        )}
    </Stack>
  );
}
