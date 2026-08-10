import { StarOutlined } from "@mui/icons-material";
import { Box, Card, CardContent, Chip, Stack, Typography } from "@mui/material";
import { useQuery } from "@tanstack/react-query";

import { getAwardPeopleV1 } from "../../api/client";
import {
  formatCreditSplitLabel as formatCredit,
  formatEffortNote as formatEffort,
  hasAnyPeople,
} from "../../features/award/awardSectionsPresentation.mjs";
import { EmptyState } from "../common/EmptyState";
import { ErrorState } from "../common/ErrorState";
import { LoadingState } from "../common/LoadingState";

// People and Units - lazy-loads from GET /api/v1/awards/{awardId}/people,
// grouping each person's units and credit splits under that person
// rather than rendering raw child-table rows.
export function AwardPeopleSection({ awardId }: { awardId: number }) {
  const peopleQuery = useQuery({
    queryKey: ["award-people-v1", awardId],
    queryFn: ({ signal }) => getAwardPeopleV1(awardId, signal),
  });

  if (peopleQuery.isLoading) {
    return <LoadingState mode="skeleton" height={96} count={3} />;
  }

  if (peopleQuery.isError) {
    return <ErrorState message="Unable to load People and Units." />;
  }

  const people = peopleQuery.data ?? [];

  if (!hasAnyPeople(people)) {
    return (
      <EmptyState
        variant="text"
        message="No people are recorded for this Award version."
      />
    );
  }

  return (
    <Stack spacing={1.5}>
      {people.map((person) => {
        const effortNotes = [
          formatEffort("Academic year", person.academicYearEffort),
          formatEffort("Calendar year", person.calendarYearEffort),
          formatEffort("Summer", person.summerEffort),
          formatEffort("Total", person.totalEffort),
        ].filter((note): note is string => note !== null);

        return (
          <Card
            key={person.awardPersonId ?? person.personId}
            variant="outlined"
          >
            <CardContent>
              <Stack
                direction="row"
                spacing={1}
                sx={{ alignItems: "center", flexWrap: "wrap" }}
              >
                <Typography sx={{ fontWeight: 700 }}>
                  {person.fullName ?? "Unnamed person"}
                </Typography>

                {person.leadPrincipalInvestigator && (
                  <Chip
                    size="small"
                    color="primary"
                    icon={<StarOutlined />}
                    label="Lead PI"
                  />
                )}

                {person.contactRoleCode && (
                  <Chip size="small" variant="outlined" label={person.contactRoleCode} />
                )}
              </Stack>

              <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
                {person.keyPersonProjectRole ?? "Role not recorded"}
                {person.personId ? ` · Person ID ${person.personId}` : ""}
              </Typography>

              {effortNotes.length > 0 && (
                <Typography
                  variant="caption"
                  color="text.secondary"
                  sx={{ display: "block", mt: 0.5 }}
                >
                  {effortNotes.join(" · ")}
                </Typography>
              )}

              {person.creditSplits.length > 0 && (
                <Stack direction="row" spacing={0.5} sx={{ mt: 1, flexWrap: "wrap" }}>
                  {person.creditSplits.map((split, index) => (
                    <Chip
                      key={index}
                      size="small"
                      variant="outlined"
                      label={formatCredit(split)}
                    />
                  ))}
                </Stack>
              )}

              {person.units.length > 0 && (
                <Box sx={{ mt: 1.5 }}>
                  <Typography
                    variant="caption"
                    color="text.secondary"
                    sx={{ textTransform: "uppercase", letterSpacing: "0.04em" }}
                  >
                    Associated units
                  </Typography>

                  <Stack spacing={0.75} sx={{ mt: 0.5 }}>
                    {person.units.map((unit, index) => (
                      <Stack
                        key={index}
                        direction="row"
                        spacing={1}
                        sx={{ alignItems: "center", flexWrap: "wrap" }}
                      >
                        <Typography variant="body2">
                          {unit.unitNumber ?? "Unknown unit"}
                        </Typography>
                        {unit.leadUnit && (
                          <Chip size="small" label="Lead unit" />
                        )}
                        {unit.creditSplits.map((split, splitIndex) => (
                          <Chip
                            key={splitIndex}
                            size="small"
                            variant="outlined"
                            label={formatCredit(split)}
                          />
                        ))}
                      </Stack>
                    ))}
                  </Stack>
                </Box>
              )}
            </CardContent>
          </Card>
        );
      })}
    </Stack>
  );
}
