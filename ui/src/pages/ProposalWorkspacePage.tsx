import {
  Alert,
  Box,
  Card,
  CardContent,
  Chip,
  CircularProgress,
  Link as MuiLink,
  Pagination,
  Stack,
  Tab,
  Tabs,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Typography,
} from "@mui/material";

import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Link as RouterLink, useParams } from "react-router-dom";

import {
  getProposalAwards,
  getProposalHistory,
  getProposalPeople,
  getProposalWorkspace,
} from "../api/client";

const tabs = ["General", "People", "Awards", "History"];

const currency = new Intl.NumberFormat("en-US", {
  style: "currency",
  currency: "USD",
});

function display(value: string | number | null) {
  return value ?? "—";
}

function money(value: number | null) {
  return value === null ? "—" : currency.format(value);
}

export function ProposalWorkspacePage() {
  const { proposalNumber } = useParams();

  return (
    <ProposalWorkspaceContent
      key={proposalNumber}
      proposalNumber={proposalNumber}
    />
  );
}

function ProposalWorkspaceContent({
  proposalNumber,
}: {
  proposalNumber: string | undefined;
}) {
  const [activeTab, setActiveTab] = useState(0);
  const [historyPage, setHistoryPage] = useState(0);

  const workspaceQuery = useQuery({
    queryKey: ["proposal-workspace", proposalNumber],
    enabled: !!proposalNumber,
    queryFn: () => getProposalWorkspace(proposalNumber!),
  });

  const peopleQuery = useQuery({
    queryKey: ["proposal-people", proposalNumber],
    enabled: !!proposalNumber && activeTab === 1,
    queryFn: () => getProposalPeople(proposalNumber!),
  });

  const awardsQuery = useQuery({
    queryKey: ["proposal-awards", proposalNumber],
    enabled: !!proposalNumber && activeTab === 2,
    queryFn: () => getProposalAwards(proposalNumber!),
  });

  const historyQuery = useQuery({
    queryKey: ["proposal-history", proposalNumber, historyPage],
    enabled: !!proposalNumber && activeTab === 3,
    queryFn: () =>
      getProposalHistory(proposalNumber!, {
        page: historyPage,
        size: 10,
      }),
  });

  if (workspaceQuery.isLoading) {
    return (
      <Box sx={{ display: "grid", placeItems: "center", py: 10 }}>
        <CircularProgress />
      </Box>
    );
  }

  if (workspaceQuery.isError || !workspaceQuery.data) {
    return <Alert severity="error">Unable to load Proposal workspace.</Alert>;
  }

  const current = workspaceQuery.data.current;

  const generalRows: Array<[string, string | number]> = [
    ["Proposal Number", current.proposalNumber],
    ["Proposal ID", current.proposalId],
    ["Version Number", current.versionNumber],
    ["Title", display(current.title)],
    ["Status", display(current.status)],
    ["Proposal Type", display(current.proposalType)],
    ["Activity Type", display(current.activityType)],
    ["Sponsor Code", display(current.sponsorCode)],
    ["Sponsor Name", display(current.sponsorName)],
    ["Lead Unit Number", display(current.leadUnitNumber)],
    ["Lead Unit Name", display(current.leadUnitName)],
    ["Principal Investigator ID", display(current.principalInvestigatorId)],
    ["Principal Investigator", display(current.principalInvestigator)],
    ["Initial Start Date", display(current.initialStartDate)],
    ["Initial End Date", display(current.initialEndDate)],
    ["Initial Direct Cost", money(current.initialDirectCost)],
    ["Initial Indirect Cost", money(current.initialIndirectCost)],
    ["Initial Total Cost", money(current.initialTotalCost)],
    ["Total Start Date", display(current.totalStartDate)],
    ["Total End Date", display(current.totalEndDate)],
    ["Total Direct Cost", money(current.totalDirectCost)],
    ["Total Indirect Cost", money(current.totalIndirectCost)],
    ["Total Cost", money(current.totalCost)],
  ];

  return (
    <Stack spacing={3}>
      <Card>
        <CardContent>
          <Typography variant="h4">
            Proposal {current.proposalNumber}
          </Typography>

          <Typography variant="h6" sx={{ mt: 1 }}>
            {current.title ?? "Untitled Proposal"}
          </Typography>

          <Typography color="text.secondary" sx={{ mt: 1 }}>
            {current.sponsorName ?? "Unknown sponsor"}
          </Typography>

          <Typography color="text.secondary">
            {current.leadUnitName ?? "Unknown lead unit"}
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
              label={`Version ${current.versionNumber}`}
            />

            <Chip
              size="small"
              variant="outlined"
              label={current.status ?? "Unknown status"}
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
          sx={{
            px: 2,
            borderBottom: "1px solid",
            borderColor: "divider",
          }}
        >
          {tabs.map((tab) => (
            <Tab key={tab} label={tab} />
          ))}
        </Tabs>

        <CardContent>
          {activeTab === 0 && (
            <Table size="small">
              <TableBody>
                {generalRows.map(([label, value]) => (
                  <TableRow key={label}>
                    <TableCell sx={{ width: 240 }}>{label}</TableCell>
                    <TableCell>{value}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}

          {activeTab === 1 && (
            <Stack spacing={2}>
              {peopleQuery.isLoading && (
                <Box sx={{ display: "grid", placeItems: "center", py: 8 }}>
                  <CircularProgress />
                </Box>
              )}

              {peopleQuery.isError && (
                <Alert severity="error">Unable to load Proposal people.</Alert>
              )}

              {peopleQuery.data && (
                <>
                  <Typography sx={{ fontWeight: 700 }}>
                    {peopleQuery.data.length.toLocaleString()} people
                  </Typography>

                  {peopleQuery.data.length === 0 ? (
                    <Alert severity="info">
                      No people are associated with the current Proposal.
                    </Alert>
                  ) : (
                    <Box sx={{ overflowX: "auto" }}>
                      <Table size="small">
                        <TableHead>
                          <TableRow>
                            <TableCell>Role</TableCell>
                            <TableCell>Name</TableCell>
                            <TableCell>Person ID</TableCell>
                            <TableCell>Project Role</TableCell>
                            <TableCell>Faculty</TableCell>
                            <TableCell>Academic Year Effort</TableCell>
                            <TableCell>Calendar Year Effort</TableCell>
                            <TableCell>Summer Effort</TableCell>
                            <TableCell>Total Effort</TableCell>
                          </TableRow>
                        </TableHead>

                        <TableBody>
                          {peopleQuery.data.map((person) => (
                            <TableRow
                              key={[
                                person.proposalId,
                                person.versionNumber,
                                person.personId,
                                person.fullName,
                                person.role,
                                person.projectRole,
                                person.sourceUpdateTimestamp,
                                person.sourceUpdateUser,
                                person.verNbr,
                              ].join("-")}
                              hover
                            >
                              <TableCell>
                                <Stack
                                  sx={{
                                    flexDirection: "row",
                                    alignItems: "center",
                                    gap: 1,
                                  }}
                                >
                                  {person.role ?? "—"}

                                  {person.principalInvestigator && (
                                    <Chip
                                      size="small"
                                      color="success"
                                      label="PI"
                                    />
                                  )}
                                </Stack>
                              </TableCell>
                              <TableCell>{person.fullName ?? "—"}</TableCell>
                              <TableCell>{person.personId ?? "—"}</TableCell>
                              <TableCell>{person.projectRole ?? "—"}</TableCell>
                              <TableCell>{person.facultyFlag ?? "—"}</TableCell>
                              <TableCell>
                                {person.academicYearEffort ?? "—"}
                              </TableCell>
                              <TableCell>
                                {person.calendarYearEffort ?? "—"}
                              </TableCell>
                              <TableCell>{person.summerEffort ?? "—"}</TableCell>
                              <TableCell>{person.totalEffort ?? "—"}</TableCell>
                            </TableRow>
                          ))}
                        </TableBody>
                      </Table>
                    </Box>
                  )}
                </>
              )}
            </Stack>
          )}

          {activeTab === 2 && (
            <Stack spacing={2}>
              {awardsQuery.isLoading && (
                <Box sx={{ display: "grid", placeItems: "center", py: 8 }}>
                  <CircularProgress />
                </Box>
              )}

              {awardsQuery.isError && (
                <Alert severity="error">Unable to load linked Awards.</Alert>
              )}

              {awardsQuery.data && (
                <>
                  <Typography sx={{ fontWeight: 700 }}>
                    {awardsQuery.data.length.toLocaleString()} linked awards
                  </Typography>

                  {awardsQuery.data.length === 0 ? (
                    <Alert severity="info">
                      No Awards are linked to this Proposal.
                    </Alert>
                  ) : (
                    <Table size="small">
                      <TableHead>
                        <TableRow>
                          <TableCell>Award Number</TableCell>
                          <TableCell>Award ID</TableCell>
                          <TableCell>Proposal ID</TableCell>
                        </TableRow>
                      </TableHead>

                      <TableBody>
                        {awardsQuery.data.map((award) => (
                          <TableRow
                            key={`${award.proposalId}-${award.awardId}-${award.awardNumber}`}
                            hover
                          >
                            <TableCell>
                              {award.awardNumber ? (
                                <MuiLink
                                  component={RouterLink}
                                  to={`/awards/history/${encodeURIComponent(
                                    award.awardNumber,
                                  )}`}
                                  sx={{ fontWeight: 700 }}
                                >
                                  {award.awardNumber}
                                </MuiLink>
                              ) : (
                                "—"
                              )}
                            </TableCell>
                            <TableCell>{award.awardId ?? "—"}</TableCell>
                            <TableCell>{award.proposalId}</TableCell>
                          </TableRow>
                        ))}
                      </TableBody>
                    </Table>
                  )}
                </>
              )}
            </Stack>
          )}

          {activeTab === 3 && (
            <Stack spacing={3}>
              {historyQuery.isLoading && (
                <Box sx={{ display: "grid", placeItems: "center", py: 8 }}>
                  <CircularProgress />
                </Box>
              )}

              {historyQuery.isError && (
                <Alert severity="error">Unable to load Proposal history.</Alert>
              )}

              {historyQuery.data && (
                <>
                  <Typography sx={{ fontWeight: 700 }}>
                    {historyQuery.data.totalElements.toLocaleString()} versions
                  </Typography>

                  {historyQuery.data.content.length === 0 ? (
                    <Alert severity="info">
                      No Proposal history rows were found.
                    </Alert>
                  ) : (
                    <Box sx={{ overflowX: "auto" }}>
                      <Table size="small">
                        <TableHead>
                          <TableRow>
                            <TableCell>Proposal ID</TableCell>
                            <TableCell>Version</TableCell>
                            <TableCell>Status</TableCell>
                            <TableCell>Title</TableCell>
                            <TableCell>Sponsor</TableCell>
                            <TableCell>Lead Unit</TableCell>
                            <TableCell>Current</TableCell>
                          </TableRow>
                        </TableHead>

                        <TableBody>
                          {historyQuery.data.content.map((version) => {
                            const isCurrent =
                              version.proposalId === current.proposalId &&
                              version.versionNumber === current.versionNumber;

                            return (
                              <TableRow
                                key={`${version.proposalId}-${version.versionNumber}`}
                                hover
                                selected={isCurrent}
                              >
                                <TableCell>{version.proposalId}</TableCell>
                                <TableCell>{version.versionNumber}</TableCell>
                                <TableCell>{version.status ?? "—"}</TableCell>
                                <TableCell>{version.title ?? "—"}</TableCell>
                                <TableCell>
                                  {version.sponsorName ?? "—"}
                                </TableCell>
                                <TableCell>
                                  {version.leadUnitName ?? "—"}
                                </TableCell>
                                <TableCell>
                                  {isCurrent ? (
                                    <Chip
                                      color="success"
                                      size="small"
                                      label="Current"
                                    />
                                  ) : (
                                    "—"
                                  )}
                                </TableCell>
                              </TableRow>
                            );
                          })}
                        </TableBody>
                      </Table>
                    </Box>
                  )}

                  {historyQuery.data.totalPages > 1 && (
                    <Box
                      sx={{
                        display: "flex",
                        justifyContent: "center",
                        pt: 2,
                      }}
                    >
                      <Pagination
                        page={historyPage + 1}
                        count={historyQuery.data.totalPages}
                        onChange={(_, nextPage) =>
                          setHistoryPage(nextPage - 1)
                        }
                        color="primary"
                        showFirstButton
                        showLastButton
                      />
                    </Box>
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
