import {
  Alert,
  Box,
  Card,
  CardContent,
  Chip,
  Skeleton,
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

import { getProposalPeopleV1, getProposalUnitsV1 } from "../../api/client";
import type { ProposalPersonV1 } from "../../types/api";

function roleLabel(person: ProposalPersonV1): string {
  if (person.principalInvestigator) {
    return "Principal Investigator";
  }
  switch (person.contactRoleCode) {
    case "MPI":
      return "Multiple PI";
    case "COI":
      return "Co-Investigator";
    case "KP":
      return "Key Person";
    default:
      return person.contactRoleCode ?? "Personnel";
  }
}

// People & Units - fed from GET /api/v1/proposals/{proposalId}/people
// and .../units. Displays, in order: PI, Key Personnel, Associated
// Units, Unit Contacts. Unit Contacts is a genuinely separate sibling
// table from Proposal People, kept in its own section - never merged.
export function ProposalPeopleUnitsSection({
  proposalId,
}: {
  proposalId: number;
}) {
  const peopleQuery = useQuery({
    queryKey: ["proposal-people-v1", proposalId],
    queryFn: ({ signal }) => getProposalPeopleV1(proposalId, signal),
  });

  const unitsQuery = useQuery({
    queryKey: ["proposal-units-v1", proposalId],
    queryFn: ({ signal }) => getProposalUnitsV1(proposalId, signal),
  });

  if (peopleQuery.isLoading || unitsQuery.isLoading) {
    return (
      <Stack spacing={1.5}>
        <Skeleton variant="rounded" height={72} />
        <Skeleton variant="rounded" height={72} />
      </Stack>
    );
  }

  if (peopleQuery.isError || unitsQuery.isError) {
    return <Alert severity="error">Unable to load People and Units.</Alert>;
  }

  const people = peopleQuery.data ?? [];
  const units = unitsQuery.data;

  const principalInvestigators = people.filter(
    (person) => person.principalInvestigator,
  );
  const keyPersonnel = people.filter(
    (person) => !person.principalInvestigator,
  );

  return (
    <Stack spacing={3}>
      <Box>
        <Typography variant="overline" color="text.secondary">
          Principal Investigator
        </Typography>

        {principalInvestigators.length === 0 ? (
          <Typography color="text.secondary" sx={{ mt: 0.5 }}>
            No Principal Investigator is recorded for this Proposal.
          </Typography>
        ) : (
          <Stack spacing={1} sx={{ mt: 0.5 }}>
            {principalInvestigators.map((person) => (
              <Card key={person.proposalPersonId} variant="outlined">
                <CardContent sx={{ "&:last-child": { pb: 2 } }}>
                  <Typography sx={{ fontWeight: 700 }}>
                    {person.fullName ?? "Unnamed"}
                  </Typography>
                  <Typography variant="body2" color="text.secondary">
                    {roleLabel(person)}
                  </Typography>
                </CardContent>
              </Card>
            ))}
          </Stack>
        )}
      </Box>

      <Box>
        <Typography variant="overline" color="text.secondary">
          Key Personnel
        </Typography>

        {keyPersonnel.length === 0 ? (
          <Typography color="text.secondary" sx={{ mt: 0.5 }}>
            No additional Key Personnel are recorded for this Proposal.
          </Typography>
        ) : (
          <TableContainer sx={{ mt: 0.5 }}>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Name</TableCell>
                  <TableCell>Role</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {keyPersonnel.map((person) => (
                  <TableRow key={person.proposalPersonId}>
                    <TableCell>{person.fullName ?? "Unnamed"}</TableCell>
                    <TableCell>{roleLabel(person)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
        )}
      </Box>

      <Box>
        <Typography variant="overline" color="text.secondary">
          Associated Units
        </Typography>

        {!units || units.associatedUnits.length === 0 ? (
          <Typography color="text.secondary" sx={{ mt: 0.5 }}>
            No Associated Units are recorded for this Proposal.
          </Typography>
        ) : (
          <Stack spacing={1} sx={{ mt: 0.5 }}>
            {units.associatedUnits.map((unit) => (
              <Card key={unit.proposalPersonUnitId} variant="outlined">
                <CardContent
                  sx={{
                    display: "flex",
                    justifyContent: "space-between",
                    alignItems: "center",
                    "&:last-child": { pb: 2 },
                  }}
                >
                  <Box>
                    <Typography sx={{ fontWeight: 700 }}>
                      {unit.unitName ?? unit.unitNumber ?? "Unknown unit"}
                    </Typography>
                    <Typography variant="body2" color="text.secondary">
                      {unit.personName ?? "Unknown person"}
                    </Typography>
                  </Box>
                  {unit.leadUnit && (
                    <Chip size="small" color="primary" label="Lead unit" />
                  )}
                </CardContent>
              </Card>
            ))}
          </Stack>
        )}
      </Box>

      <Box>
        <Typography variant="overline" color="text.secondary">
          Unit Contacts
        </Typography>

        {!units || units.unitContacts.length === 0 ? (
          <Typography color="text.secondary" sx={{ mt: 0.5 }}>
            No Unit Contacts are recorded for this Proposal.
          </Typography>
        ) : (
          <Stack spacing={1} sx={{ mt: 0.5 }}>
            {units.unitContacts.map((contact) => (
              <Card key={contact.proposalUnitContactId} variant="outlined">
                <CardContent sx={{ "&:last-child": { pb: 2 } }}>
                  <Typography sx={{ fontWeight: 700 }}>
                    {contact.fullName ?? "Unnamed"}
                  </Typography>
                  <Typography variant="body2" color="text.secondary">
                    {contact.unitAdministratorTypeDescription ??
                      contact.unitAdministratorTypeCode ??
                      "—"}
                  </Typography>
                </CardContent>
              </Card>
            ))}
          </Stack>
        )}
      </Box>
    </Stack>
  );
}
