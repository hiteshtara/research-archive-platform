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
  getAwardSequenceDetail,
  getAwardSequencePage,
  getAwardWorkspace,
} from "../api/client";

const tabs = [
  "General",
  "People",
  "Funding",
  "Amounts",
  "Proposals",
  "History",
];

export function AwardHistoryPage() {
  const { awardNumber } = useParams();

  const [activeTab, setActiveTab] = useState(0);
  const [historyPage, setHistoryPage] = useState(0);
  const [selectedSequence, setSelectedSequence] =
    useState<number | null>(null);

  const workspaceQuery = useQuery({
    queryKey: [
      "award-workspace",
      awardNumber,
    ],

    enabled: !!awardNumber,

    queryFn: () =>
      getAwardWorkspace(
        awardNumber!,
      ),
  });

  const historyQuery = useQuery({
    queryKey: [
      "award-sequence-page",
      awardNumber,
      historyPage,
    ],

    enabled:
      !!awardNumber &&
      activeTab === 5,

    queryFn: () =>
      getAwardSequencePage(
        awardNumber!,
        {
          page: historyPage,
          size: 10,
        },
      ),
  });

  const sequenceQuery = useQuery({
    queryKey: [
      "award-sequence-detail",
      awardNumber,
      selectedSequence,
    ],

    enabled:
      !!awardNumber &&
      selectedSequence !== null,

    queryFn: () =>
      getAwardSequenceDetail(
        awardNumber!,
        selectedSequence!,
      ),
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

  if (
    workspaceQuery.isError ||
    !workspaceQuery.data
  ) {
    return (
      <Alert severity="error">
        Unable to load Award workspace.
      </Alert>
    );
  }

  const current =
    workspaceQuery.data.current;

  return (
    <Stack spacing={3}>

      <Card>
        <CardContent>

          <Typography variant="h4">
            Award {current.awardNumber}
          </Typography>

          <Typography
            variant="h6"
            sx={{ mt: 1 }}
          >
            {current.title}
          </Typography>

          <Typography
            color="text.secondary"
            sx={{ mt: 1 }}
          >
            {current.sponsor ?? "Unknown sponsor"}
          </Typography>

          <Typography color="text.secondary">
            {current.leadUnit ?? "Unknown lead unit"}
          </Typography>

          <Box sx={{ mt: 3 }}>
            <Table size="small">
              <TableBody>

                <TableRow>
                  <TableCell sx={{ width: 240 }}>
                    Status
                  </TableCell>
                  <TableCell>
                    {current.status ?? "Unknown"}
                  </TableCell>
                </TableRow>

                <TableRow>
                  <TableCell>
                    Current Sequence
                  </TableCell>
                  <TableCell>
                    <Chip
                      color="success"
                      size="small"
                      label={current.sequenceNumber}
                    />
                  </TableCell>
                </TableRow>

                <TableRow>
                  <TableCell>
                    Award ID
                  </TableCell>
                  <TableCell>
                    {current.awardId}
                  </TableCell>
                </TableRow>

                <TableRow>
                  <TableCell>
                    Sequence Status
                  </TableCell>
                  <TableCell>
                    {current.awardSequenceStatus}
                  </TableCell>
                </TableRow>

                <TableRow>
                  <TableCell>
                    Sponsor Award Number
                  </TableCell>
                  <TableCell>
                    {current.sponsorAwardNumber ?? "—"}
                  </TableCell>
                </TableRow>

                <TableRow>
                  <TableCell>
                    Account Number
                  </TableCell>
                  <TableCell>
                    {current.accountNumber ?? "—"}
                  </TableCell>
                </TableRow>

              </TableBody>
            </Table>
          </Box>

        </CardContent>
      </Card>

      <Card>
        <Tabs
          value={activeTab}
          onChange={(_, nextTab) =>
            setActiveTab(nextTab)
          }
          variant="scrollable"
          scrollButtons="auto"
          sx={{
            px: 2,
            borderBottom: "1px solid",
            borderColor: "divider",
          }}
        >
          {tabs.map((tab) => (
            <Tab
              key={tab}
              label={tab}
            />
          ))}
        </Tabs>

        <CardContent>

          {activeTab === 0 && (
            <Table size="small">
              <TableBody>

                <TableRow>
                  <TableCell sx={{ width: 240 }}>
                    Award Number
                  </TableCell>
                  <TableCell>
                    {current.awardNumber}
                  </TableCell>
                </TableRow>

                <TableRow>
                  <TableCell>
                    Award ID
                  </TableCell>
                  <TableCell>
                    {current.awardId}
                  </TableCell>
                </TableRow>

                <TableRow>
                  <TableCell>
                    Sequence Number
                  </TableCell>
                  <TableCell>
                    {current.sequenceNumber}
                  </TableCell>
                </TableRow>

                <TableRow>
                  <TableCell>
                    Title
                  </TableCell>
                  <TableCell>
                    {current.title}
                  </TableCell>
                </TableRow>

                <TableRow>
                  <TableCell>
                    Status
                  </TableCell>
                  <TableCell>
                    {current.status ?? "—"}
                  </TableCell>
                </TableRow>

                <TableRow>
                  <TableCell>
                    Sequence Status
                  </TableCell>
                  <TableCell>
                    {current.awardSequenceStatus}
                  </TableCell>
                </TableRow>

                <TableRow>
                  <TableCell>
                    Sponsor
                  </TableCell>
                  <TableCell>
                    {current.sponsor ?? "—"}
                  </TableCell>
                </TableRow>

                <TableRow>
                  <TableCell>
                    Prime Sponsor
                  </TableCell>
                  <TableCell>
                    {current.primeSponsor ?? "—"}
                  </TableCell>
                </TableRow>

                <TableRow>
                  <TableCell>
                    Lead Unit
                  </TableCell>
                  <TableCell>
                    {current.leadUnit ?? "—"}
                  </TableCell>
                </TableRow>

                <TableRow>
                  <TableCell>
                    Account Number
                  </TableCell>
                  <TableCell>
                    {current.accountNumber ?? "—"}
                  </TableCell>
                </TableRow>

                <TableRow>
                  <TableCell>
                    Sponsor Award Number
                  </TableCell>
                  <TableCell>
                    {current.sponsorAwardNumber ?? "—"}
                  </TableCell>
                </TableRow>

                <TableRow>
                  <TableCell>
                    Begin Date
                  </TableCell>
                  <TableCell>
                    {current.beginDate ?? "—"}
                  </TableCell>
                </TableRow>

                <TableRow>
                  <TableCell>
                    Closeout Date
                  </TableCell>
                  <TableCell>
                    {current.closeoutDate ?? "—"}
                  </TableCell>
                </TableRow>

              </TableBody>
            </Table>
          )}

          {activeTab >= 1 && activeTab <= 4 && (
            <Box
              sx={{
                py: 6,
                textAlign: "center",
              }}
            >
              <Typography variant="h6">
                {tabs[activeTab]}
              </Typography>

              <Typography
                color="text.secondary"
                sx={{ mt: 1 }}
              >
                This section will be connected next.
              </Typography>
            </Box>
          )}

          {activeTab === 5 && (
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
                <Alert severity="error">
                  Unable to load Award history.
                </Alert>
              )}

              {historyQuery.data && (
                <>

                  <Typography sx={{ fontWeight: 700 }}>
                    {historyQuery.data.totalElements.toLocaleString()} sequences
                  </Typography>

                  <Table>

                    <TableHead>
                      <TableRow>
                        <TableCell>
                          Sequence
                        </TableCell>
                        <TableCell>
                          Status
                        </TableCell>
                        <TableCell>
                          Sequence Status
                        </TableCell>
                        <TableCell>
                          Rows
                        </TableCell>
                        <TableCell>
                          Current
                        </TableCell>
                      </TableRow>
                    </TableHead>

                    <TableBody>
                      {historyQuery.data.content.map(
                        (sequence) => (
                          <TableRow
                            key={
                              sequence.sequenceNumber
                            }
                            hover
                            onClick={() =>
                              setSelectedSequence(
                                sequence.sequenceNumber,
                              )
                            }
                            sx={{
                              cursor: "pointer",
                            }}
                          >
                            <TableCell>
                              {sequence.sequenceNumber}
                            </TableCell>

                            <TableCell>
                              {sequence.status ?? "—"}
                            </TableCell>

                            <TableCell>
                              {sequence.awardSequenceStatus ?? "—"}
                            </TableCell>

                            <TableCell>
                              {sequence.rowCount}
                            </TableCell>

                            <TableCell>
                              {sequence.currentSequence
                                ? (
                                  <Chip
                                    color="success"
                                    size="small"
                                    label="Current"
                                  />
                                )
                                : "—"}
                            </TableCell>
                          </TableRow>
                        ),
                      )}
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
                      count={
                        historyQuery.data.totalPages
                      }
                      onChange={(_, nextPage) =>
                        setHistoryPage(
                          nextPage - 1,
                        )
                      }
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
        onClose={() =>
          setSelectedSequence(null)
        }
        fullWidth
        maxWidth="lg"
      >

        <DialogTitle>
          Sequence {selectedSequence}
        </DialogTitle>

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
            <Alert severity="error">
              Unable to load sequence details.
            </Alert>
          )}

          {sequenceQuery.data && (
            <Table size="small">

              <TableHead>
                <TableRow>
                  <TableCell>
                    Award ID
                  </TableCell>
                  <TableCell>
                    Status
                  </TableCell>
                  <TableCell>
                    Sequence Status
                  </TableCell>
                  <TableCell>
                    Sponsor
                  </TableCell>
                  <TableCell>
                    Prime Sponsor
                  </TableCell>
                  <TableCell>
                    Lead Unit
                  </TableCell>
                  <TableCell>
                    Account
                  </TableCell>
                </TableRow>
              </TableHead>

              <TableBody>
                {sequenceQuery.data.rows.map(
                  (row) => (
                    <TableRow key={row.awardId}>
                      <TableCell>
                        {row.awardId}
                      </TableCell>

                      <TableCell>
                        {row.status ?? "—"}
                      </TableCell>

                      <TableCell>
                        {row.awardSequenceStatus}
                      </TableCell>

                      <TableCell>
                        {row.sponsor ?? "—"}
                      </TableCell>

                      <TableCell>
                        {row.primeSponsor ?? "—"}
                      </TableCell>

                      <TableCell>
                        {row.leadUnit ?? "—"}
                      </TableCell>

                      <TableCell>
                        {row.accountNumber ?? "—"}
                      </TableCell>
                    </TableRow>
                  ),
                )}
              </TableBody>

            </Table>
          )}

        </DialogContent>
      </Dialog>

    </Stack>
  );
}
