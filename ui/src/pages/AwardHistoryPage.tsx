import {
  Alert,
  Box,
  Card,
  CardContent,
  Chip,
  CircularProgress,
  Dialog,
  DialogContent,
  DialogTitle,
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
import { useParams } from "react-router-dom";

import {
  getAwardPeople,
  getAwardAmounts,
  getAwardFunding,
  getAwardProposals,
  getAwardUnitContacts,
  getAwardSequenceDetail,
  getAwardSequencePage,
  getAwardWorkspace,
} from "../api/client";

const tabs = [
  "General",
  "People",
  "Unit Contacts",
  "Funding",
  "Amounts",
  "Proposals",
  "History",
];

export function AwardHistoryPage() {
  const { awardNumber } = useParams();

  const [activeTab, setActiveTab] = useState(0);
  const [historyPage, setHistoryPage] = useState(0);
  const [selectedSequence, setSelectedSequence] = useState<number | null>(null);

  const workspaceQuery = useQuery({
    queryKey: ["award-workspace", awardNumber],

    enabled: !!awardNumber,

    queryFn: () => getAwardWorkspace(awardNumber!),
  });

  const peopleQuery = useQuery({
    queryKey: ["award-people", awardNumber],

    enabled: !!awardNumber && activeTab === 1,

    queryFn: () => getAwardPeople(awardNumber!),
  });

  const unitContactsQuery = useQuery({
    queryKey: ["award-unit-contacts", awardNumber],

    enabled: !!awardNumber && activeTab === 2,

    queryFn: () => getAwardUnitContacts(awardNumber!),
  });

  const fundingQuery = useQuery({
    queryKey: ["award-funding", awardNumber],

    enabled: !!awardNumber && activeTab === 3,

    queryFn: () => getAwardFunding(awardNumber!),
  });

  const amountsQuery = useQuery({
    queryKey: ["award-amounts", awardNumber],

    enabled: !!awardNumber && activeTab === 4,

    queryFn: () => getAwardAmounts(awardNumber!),
  });

  const proposalsQuery = useQuery({
    queryKey: ["award-proposals", awardNumber],

    enabled: !!awardNumber && activeTab === 5,

    queryFn: () => getAwardProposals(awardNumber!),
  });

  const historyQuery = useQuery({
    queryKey: ["award-sequence-page", awardNumber, historyPage],

    enabled: !!awardNumber && activeTab === 6,

    queryFn: () =>
      getAwardSequencePage(awardNumber!, {
        page: historyPage,
        size: 10,
      }),
  });

  const sequenceQuery = useQuery({
    queryKey: ["award-sequence-detail", awardNumber, selectedSequence],

    enabled: !!awardNumber && selectedSequence !== null,

    queryFn: () => getAwardSequenceDetail(awardNumber!, selectedSequence!),
  });

  if (workspaceQuery.isLoading) {
    return (
      <Box
        sx={{
          display: "grid",
          placeItems: "center",
          py: 10,
        }}
      >
        <CircularProgress />
      </Box>
    );
  }

  if (workspaceQuery.isError || !workspaceQuery.data) {
    return <Alert severity="error">Unable to load Award workspace.</Alert>;
  }

  const current = workspaceQuery.data.current;

  return (
    <Stack spacing={3}>
      <Card>
        <CardContent>
          <Typography variant="h4">Award {current.awardNumber}</Typography>

          <Typography variant="h6" sx={{ mt: 1 }}>
            {current.title}
          </Typography>

          <Typography color="text.secondary" sx={{ mt: 1 }}>
            {current.sponsor ?? "Unknown sponsor"}
          </Typography>

          <Typography color="text.secondary">
            {current.leadUnit ?? "Unknown lead unit"}
          </Typography>

          <Box sx={{ mt: 3 }}>
            <Table size="small">
              <TableBody>
                <TableRow>
                  <TableCell sx={{ width: 240 }}>Status</TableCell>
                  <TableCell>{current.status ?? "Unknown"}</TableCell>
                </TableRow>

                <TableRow>
                  <TableCell>Current Sequence</TableCell>
                  <TableCell>
                    <Chip
                      color="success"
                      size="small"
                      label={current.sequenceNumber}
                    />
                  </TableCell>
                </TableRow>

                <TableRow>
                  <TableCell>Award ID</TableCell>
                  <TableCell>{current.awardId}</TableCell>
                </TableRow>

                <TableRow>
                  <TableCell>Sequence Status</TableCell>
                  <TableCell>{current.awardSequenceStatus}</TableCell>
                </TableRow>

                <TableRow>
                  <TableCell>Sponsor Award Number</TableCell>
                  <TableCell>{current.sponsorAwardNumber ?? "—"}</TableCell>
                </TableRow>

                <TableRow>
                  <TableCell>Account Number</TableCell>
                  <TableCell>{current.accountNumber ?? "—"}</TableCell>
                </TableRow>
              </TableBody>
            </Table>
          </Box>
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
                <TableRow>
                  <TableCell sx={{ width: 240 }}>Award Number</TableCell>
                  <TableCell>{current.awardNumber}</TableCell>
                </TableRow>

                <TableRow>
                  <TableCell>Award ID</TableCell>
                  <TableCell>{current.awardId}</TableCell>
                </TableRow>

                <TableRow>
                  <TableCell>Sequence Number</TableCell>
                  <TableCell>{current.sequenceNumber}</TableCell>
                </TableRow>

                <TableRow>
                  <TableCell>Title</TableCell>
                  <TableCell>{current.title}</TableCell>
                </TableRow>

                <TableRow>
                  <TableCell>Status</TableCell>
                  <TableCell>{current.status ?? "—"}</TableCell>
                </TableRow>

                <TableRow>
                  <TableCell>Sequence Status</TableCell>
                  <TableCell>{current.awardSequenceStatus}</TableCell>
                </TableRow>

                <TableRow>
                  <TableCell>Sponsor</TableCell>
                  <TableCell>{current.sponsor ?? "—"}</TableCell>
                </TableRow>

                <TableRow>
                  <TableCell>Prime Sponsor</TableCell>
                  <TableCell>{current.primeSponsor ?? "—"}</TableCell>
                </TableRow>

                <TableRow>
                  <TableCell>Lead Unit</TableCell>
                  <TableCell>{current.leadUnit ?? "—"}</TableCell>
                </TableRow>

                <TableRow>
                  <TableCell>Account Number</TableCell>
                  <TableCell>{current.accountNumber ?? "—"}</TableCell>
                </TableRow>

                <TableRow>
                  <TableCell>Sponsor Award Number</TableCell>
                  <TableCell>{current.sponsorAwardNumber ?? "—"}</TableCell>
                </TableRow>

                <TableRow>
                  <TableCell>Begin Date</TableCell>
                  <TableCell>{current.beginDate ?? "—"}</TableCell>
                </TableRow>

                <TableRow>
                  <TableCell>Closeout Date</TableCell>
                  <TableCell>{current.closeoutDate ?? "—"}</TableCell>
                </TableRow>
              </TableBody>
            </Table>
          )}

          {activeTab === 1 && (
            <Stack spacing={2}>
              {peopleQuery.isLoading && (
                <Box
                  sx={{
                    display: "grid",
                    placeItems: "center",
                    py: 8,
                  }}
                >
                  <CircularProgress />
                </Box>
              )}

              {peopleQuery.isError && (
                <Alert severity="error">Unable to load Award people.</Alert>
              )}

              {peopleQuery.data && (
                <>
                  <Typography sx={{ fontWeight: 700 }}>
                    {peopleQuery.data.length.toLocaleString()} people
                  </Typography>

                  {peopleQuery.data.length === 0 ? (
                    <Alert severity="info">
                      No people are associated with the current Award.
                    </Alert>
                  ) : (
                    <Table>
                      <TableHead>
                        <TableRow>
                          <TableCell>Role</TableCell>
                          <TableCell>Name</TableCell>
                          <TableCell>Person ID</TableCell>
                          <TableCell>Project Role</TableCell>
                          <TableCell>Faculty</TableCell>
                          <TableCell>Total Effort</TableCell>
                        </TableRow>
                      </TableHead>

                      <TableBody>
                        {peopleQuery.data.map((person) => (
                          <TableRow key={person.awardPersonId}>
                            <TableCell>
                              {person.contactRoleCode ?? "—"}
                            </TableCell>

                            <TableCell>
                              <Typography sx={{ fontWeight: 700 }}>
                                {person.fullName ?? "Unnamed person"}
                              </Typography>
                            </TableCell>

                            <TableCell>{person.personId ?? "—"}</TableCell>

                            <TableCell>
                              {person.keyPersonProjectRole ?? "—"}
                            </TableCell>

                            <TableCell>{person.facultyFlag ?? "—"}</TableCell>

                            <TableCell>{person.totalEffort ?? "—"}</TableCell>
                          </TableRow>
                        ))}
                      </TableBody>
                    </Table>
                  )}
                </>
              )}
            </Stack>
          )}

          {activeTab === 2 && (
            <Stack spacing={2}>
              {unitContactsQuery.isLoading && (
                <Box
                  sx={{
                    display: "grid",
                    placeItems: "center",
                    py: 8,
                  }}
                >
                  <CircularProgress />
                </Box>
              )}

              {unitContactsQuery.isError && (
                <Alert severity="error">
                  Unable to load Award unit contacts.
                </Alert>
              )}

              {unitContactsQuery.data && (
                <>
                  <Typography sx={{ fontWeight: 700 }}>
                    {unitContactsQuery.data.length.toLocaleString()} unit
                    contacts
                  </Typography>

                  {unitContactsQuery.data.length === 0 ? (
                    <Alert severity="info">
                      No unit contacts are associated with the current Award.
                    </Alert>
                  ) : (
                    <Box sx={{ overflowX: "auto" }}>
                      <Table size="small">
                        <TableHead>
                          <TableRow>
                            <TableCell>Project Role</TableCell>
                            <TableCell>Name</TableCell>
                            <TableCell>Email</TableCell>
                            <TableCell>Phone</TableCell>
                            <TableCell>Title</TableCell>
                            <TableCell>Unit</TableCell>
                            <TableCell>Parent Unit</TableCell>
                            <TableCell>Default</TableCell>
                          </TableRow>
                        </TableHead>

                        <TableBody>
                          {unitContactsQuery.data.map((contact) => (
                            <TableRow key={contact.awardUnitContactId} hover>
                              <TableCell>
                                {contact.projectRole ?? "—"}
                              </TableCell>

                              <TableCell>
                                <Typography sx={{ fontWeight: 700 }}>
                                  {contact.fullName ?? "Unnamed contact"}
                                </Typography>

                                {contact.personId && (
                                  <Typography
                                    variant="caption"
                                    color="text.secondary"
                                  >
                                    {contact.personId}
                                  </Typography>
                                )}
                              </TableCell>

                              <TableCell>
                                {contact.emailAddress ? (
                                  <a href={`mailto:${contact.emailAddress}`}>
                                    {contact.emailAddress}
                                  </a>
                                ) : (
                                  "—"
                                )}
                              </TableCell>

                              <TableCell>
                                {contact.officePhone ?? "—"}

                                {contact.phoneExtension && (
                                  <>
                                    {" ext. "}
                                    {contact.phoneExtension}
                                  </>
                                )}
                              </TableCell>

                              <TableCell>
                                {contact.directoryTitle ??
                                  contact.primaryTitle ??
                                  "—"}
                              </TableCell>

                              <TableCell>
                                <Typography>
                                  {contact.unitName ?? "—"}
                                </Typography>

                                {contact.unitNumber && (
                                  <Typography
                                    variant="caption"
                                    color="text.secondary"
                                  >
                                    {contact.unitNumber}
                                  </Typography>
                                )}
                              </TableCell>

                              <TableCell>
                                <Typography>
                                  {contact.parentUnitName ?? "—"}
                                </Typography>

                                {contact.parentUnitNumber && (
                                  <Typography
                                    variant="caption"
                                    color="text.secondary"
                                  >
                                    {contact.parentUnitNumber}
                                  </Typography>
                                )}
                              </TableCell>

                              <TableCell>
                                {contact.defaultUnitContact === "Y" ? (
                                  <Chip
                                    size="small"
                                    color="primary"
                                    label="Yes"
                                  />
                                ) : (
                                  "No"
                                )}
                              </TableCell>
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

          {activeTab === 3 && (
            <Stack spacing={2}>
              {fundingQuery.isLoading && (
                <Box
                  sx={{
                    display: "grid",
                    placeItems: "center",
                    py: 8,
                  }}
                >
                  <CircularProgress />
                </Box>
              )}

              {fundingQuery.isError && (
                <Alert severity="error">Unable to load Award funding.</Alert>
              )}

              {fundingQuery.data && (
                <>
                  <Typography variant="h6" sx={{ fontWeight: 800 }}>
                    Funding Summary
                  </Typography>

                  <Box
                    sx={{
                      display: "grid",
                      gridTemplateColumns: {
                        xs: "1fr",
                        md: "repeat(2, minmax(0, 1fr))",
                        xl: "repeat(4, minmax(0, 1fr))",
                      },
                      gap: 2,
                    }}
                  >
                    {[
                      ["Sponsor", fundingQuery.data.sponsor ?? "—"],
                      ["Prime Sponsor", fundingQuery.data.primeSponsor ?? "—"],
                      [
                        "Sponsor Award Number",
                        fundingQuery.data.sponsorAwardNumber ?? "—",
                      ],
                      ["Lead Unit", fundingQuery.data.leadUnit ?? "—"],
                      [
                        "Linked Proposals",
                        fundingQuery.data.linkedProposalCount,
                      ],
                      [
                        "Active Proposals",
                        fundingQuery.data.activeProposalCount,
                      ],
                    ].map(([label, value]) => (
                      <Card key={String(label)} variant="outlined">
                        <CardContent>
                          <Typography
                            variant="caption"
                            color="text.secondary"
                            sx={{
                              fontWeight: 700,
                              textTransform: "uppercase",
                            }}
                          >
                            {label}
                          </Typography>

                          <Typography
                            variant="h6"
                            sx={{
                              mt: 1,
                              fontWeight: 800,
                            }}
                          >
                            {value}
                          </Typography>
                        </CardContent>
                      </Card>
                    ))}
                  </Box>
                </>
              )}
            </Stack>
          )}

          {activeTab === 4 && (
            <Stack spacing={2}>
              {amountsQuery.isLoading && (
                <Box
                  sx={{
                    display: "grid",
                    placeItems: "center",
                    py: 8,
                  }}
                >
                  <CircularProgress />
                </Box>
              )}

              {amountsQuery.isError && (
                <Alert severity="error">Unable to load Award amounts.</Alert>
              )}

              {amountsQuery.data && (
                <>
                  <Typography variant="h6" sx={{ fontWeight: 800 }}>
                    Award Amounts
                  </Typography>

                  {amountsQuery.data.length === 0 ? (
                    <Alert severity="info">
                      No amount records are associated with the current Award.
                    </Alert>
                  ) : (
                    <Box sx={{ overflowX: "auto" }}>
                      <Table size="small" sx={{ minWidth: 1450 }}>
                        <TableHead>
                          <TableRow>
                            <TableCell>Sequence</TableCell>
                            <TableCell>Obligated Total</TableCell>
                            <TableCell>Obligated Direct</TableCell>
                            <TableCell>Obligated Indirect</TableCell>
                            <TableCell>Anticipated Total</TableCell>
                            <TableCell>Anticipated Direct</TableCell>
                            <TableCell>Anticipated Indirect</TableCell>
                            <TableCell>Change Direct</TableCell>
                            <TableCell>Change Indirect</TableCell>
                            <TableCell>TNM Document</TableCell>
                            <TableCell>Source Version</TableCell>
                          </TableRow>
                        </TableHead>

                        <TableBody>
                          {amountsQuery.data.map((amount) => {
                            const money = (value: number | null) =>
                              value === null
                                ? "—"
                                : new Intl.NumberFormat("en-US", {
                                    style: "currency",
                                    currency: "USD",
                                  }).format(value);

                            return (
                              <TableRow key={amount.awardAmountInfoId} hover>
                                <TableCell>{amount.sequenceNumber}</TableCell>

                                <TableCell>
                                  {money(amount.obligatedTotalAmount)}
                                </TableCell>

                                <TableCell>
                                  {money(amount.obligatedTotalDirect)}
                                </TableCell>

                                <TableCell>
                                  {money(amount.obligatedTotalIndirect)}
                                </TableCell>

                                <TableCell>
                                  {money(amount.anticipatedTotalAmount)}
                                </TableCell>

                                <TableCell>
                                  {money(amount.anticipatedTotalDirect)}
                                </TableCell>

                                <TableCell>
                                  {money(amount.anticipatedTotalIndirect)}
                                </TableCell>

                                <TableCell>
                                  {money(amount.anticipatedChangeDirect)}
                                </TableCell>

                                <TableCell>
                                  {money(amount.anticipatedChangeIndirect)}
                                </TableCell>

                                <TableCell>
                                  {amount.tnmDocumentNumber ?? "—"}
                                </TableCell>

                                <TableCell>
                                  {amount.sourceVersionNumber ?? "—"}
                                </TableCell>
                              </TableRow>
                            );
                          })}
                        </TableBody>
                      </Table>
                    </Box>
                  )}
                </>
              )}
            </Stack>
          )}

          {activeTab === 5 && (
            <Stack spacing={2}>
              {proposalsQuery.isLoading && (
                <Box
                  sx={{
                    display: "grid",
                    placeItems: "center",
                    py: 8,
                  }}
                >
                  <CircularProgress />
                </Box>
              )}

              {proposalsQuery.isError && (
                <Alert severity="error">Unable to load linked proposals.</Alert>
              )}

              {proposalsQuery.data && (
                <>
                  <Typography variant="h6" sx={{ fontWeight: 800 }}>
                    Linked Proposals
                  </Typography>

                  {proposalsQuery.data.length === 0 ? (
                    <Alert severity="info">
                      No proposals are linked to the current Award.
                    </Alert>
                  ) : (
                    <Table size="small">
                      <TableHead>
                        <TableRow>
                          <TableCell>Proposal ID</TableCell>
                          <TableCell>Status</TableCell>
                          <TableCell>Source Updated</TableCell>
                          <TableCell>Source User</TableCell>
                          <TableCell>Source Version</TableCell>
                        </TableRow>
                      </TableHead>

                      <TableBody>
                        {proposalsQuery.data.map((proposal) => {
                          const active = ["Y", "YES", "TRUE", "1"].includes(
                            proposal.activeFlag?.trim().toUpperCase() ?? "",
                          );

                          return (
                            <TableRow
                              key={proposal.awardFundingProposalId}
                              hover
                            >
                              <TableCell>
                                <Typography sx={{ fontWeight: 800 }}>
                                  {proposal.proposalId}
                                </Typography>
                              </TableCell>

                              <TableCell>
                                <Chip
                                  size="small"
                                  color={active ? "success" : "default"}
                                  label={
                                    active
                                      ? "Active"
                                      : (proposal.activeFlag ?? "Inactive")
                                  }
                                />
                              </TableCell>

                              <TableCell>
                                {proposal.sourceUpdateTimestamp ?? "—"}
                              </TableCell>

                              <TableCell>
                                {proposal.sourceUpdateUser ?? "—"}
                              </TableCell>

                              <TableCell>
                                {proposal.sourceVersionNumber ?? "—"}
                              </TableCell>
                            </TableRow>
                          );
                        })}
                      </TableBody>
                    </Table>
                  )}
                </>
              )}
            </Stack>
          )}

          {activeTab === 6 && (
            <Stack spacing={3}>
              {historyQuery.isLoading && (
                <Box
                  sx={{
                    display: "grid",
                    placeItems: "center",
                    py: 8,
                  }}
                >
                  <CircularProgress />
                </Box>
              )}

              {historyQuery.isError && (
                <Alert severity="error">Unable to load Award history.</Alert>
              )}

              {historyQuery.data && (
                <>
                  <Typography sx={{ fontWeight: 700 }}>
                    {historyQuery.data.totalElements.toLocaleString()} sequences
                  </Typography>

                  <Table>
                    <TableHead>
                      <TableRow>
                        <TableCell>Sequence</TableCell>
                        <TableCell>Status</TableCell>
                        <TableCell>Sequence Status</TableCell>
                        <TableCell>Rows</TableCell>
                        <TableCell>Current</TableCell>
                      </TableRow>
                    </TableHead>

                    <TableBody>
                      {historyQuery.data.content.map((sequence) => (
                        <TableRow
                          key={sequence.sequenceNumber}
                          hover
                          onClick={() =>
                            setSelectedSequence(sequence.sequenceNumber)
                          }
                          sx={{
                            cursor: "pointer",
                          }}
                        >
                          <TableCell>{sequence.sequenceNumber}</TableCell>

                          <TableCell>{sequence.status ?? "—"}</TableCell>

                          <TableCell>
                            {sequence.awardSequenceStatus ?? "—"}
                          </TableCell>

                          <TableCell>{sequence.rowCount}</TableCell>

                          <TableCell>
                            {sequence.currentSequence ? (
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
                      ))}
                    </TableBody>
                  </Table>

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
                      onChange={(_, nextPage) => setHistoryPage(nextPage - 1)}
                      color="primary"
                      showFirstButton
                      showLastButton
                    />
                  </Box>
                </>
              )}
            </Stack>
          )}
        </CardContent>
      </Card>

      <Dialog
        open={selectedSequence !== null}
        onClose={() => setSelectedSequence(null)}
        fullWidth
        maxWidth="lg"
      >
        <DialogTitle>Sequence {selectedSequence}</DialogTitle>

        <DialogContent>
          {sequenceQuery.isLoading && (
            <Box
              sx={{
                display: "grid",
                placeItems: "center",
                py: 8,
              }}
            >
              <CircularProgress />
            </Box>
          )}

          {sequenceQuery.isError && (
            <Alert severity="error">Unable to load sequence details.</Alert>
          )}

          {sequenceQuery.data && (
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Award ID</TableCell>
                  <TableCell>Status</TableCell>
                  <TableCell>Sequence Status</TableCell>
                  <TableCell>Sponsor</TableCell>
                  <TableCell>Prime Sponsor</TableCell>
                  <TableCell>Lead Unit</TableCell>
                  <TableCell>Account</TableCell>
                </TableRow>
              </TableHead>

              <TableBody>
                {sequenceQuery.data.rows.map((row) => (
                  <TableRow key={row.awardId}>
                    <TableCell>{row.awardId}</TableCell>

                    <TableCell>{row.status ?? "—"}</TableCell>

                    <TableCell>{row.awardSequenceStatus}</TableCell>

                    <TableCell>{row.sponsor ?? "—"}</TableCell>

                    <TableCell>{row.primeSponsor ?? "—"}</TableCell>

                    <TableCell>{row.leadUnit ?? "—"}</TableCell>

                    <TableCell>{row.accountNumber ?? "—"}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </DialogContent>
      </Dialog>
    </Stack>
  );
}
