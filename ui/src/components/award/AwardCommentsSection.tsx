import {
  Alert,
  Box,
  Card,
  CardContent,
  Skeleton,
  Stack,
  Typography,
} from "@mui/material";
import { useQuery } from "@tanstack/react-query";

import { getAwardCommentsV1 } from "../../api/client";
import { hasAnyComments } from "../../features/award/awardSectionsPresentation.mjs";

// Comments and Notepad - lazy-loads from GET
// /api/v1/awards/{awardId}/comments. Kept as two separate groups:
// award_comment is scoped to this specific version, award_notepad has
// no sequence_number and is scoped to the whole Award family instead.
export function AwardCommentsSection({ awardId }: { awardId: number }) {
  const commentsQuery = useQuery({
    queryKey: ["award-comments-v1", awardId],
    queryFn: ({ signal }) => getAwardCommentsV1(awardId, signal),
  });

  if (commentsQuery.isLoading) {
    return (
      <Stack spacing={1.5}>
        <Skeleton variant="rounded" height={80} />
        <Skeleton variant="rounded" height={80} />
      </Stack>
    );
  }

  if (commentsQuery.isError) {
    return <Alert severity="error">Unable to load Comments and Notepad.</Alert>;
  }

  const data = commentsQuery.data;

  if (!data) {
    return null;
  }

  if (!hasAnyComments(data.comments, data.notepadEntries)) {
    return (
      <Typography color="text.secondary">
        No comments or notepad entries are recorded for this Award.
      </Typography>
    );
  }

  return (
    <Stack spacing={3}>
      <Box>
        <Typography variant="overline" color="text.secondary">
          Comments
        </Typography>

        {data.comments.length === 0 ? (
          <Typography color="text.secondary" sx={{ mt: 0.5 }}>
            No comments recorded for this version.
          </Typography>
        ) : (
          <Stack spacing={1} sx={{ mt: 0.5 }}>
            {data.comments.map((comment) => (
              <Card key={comment.awardCommentId} variant="outlined">
                <CardContent>
                  <Typography
                    variant="caption"
                    color="text.secondary"
                    sx={{ textTransform: "uppercase", letterSpacing: "0.04em" }}
                  >
                    {comment.commentTypeCode ?? "Comment"}
                    {comment.sourceUpdateTimestamp
                      ? ` · ${comment.sourceUpdateTimestamp}`
                      : ""}
                    {comment.sourceUpdateUser
                      ? ` · ${comment.sourceUpdateUser}`
                      : ""}
                  </Typography>

                  <Typography
                    variant="body2"
                    sx={{ mt: 0.75, whiteSpace: "pre-wrap" }}
                  >
                    {comment.comments ?? "—"}
                  </Typography>
                </CardContent>
              </Card>
            ))}
          </Stack>
        )}
      </Box>

      <Box>
        <Typography variant="overline" color="text.secondary">
          Notepad
        </Typography>

        {data.notepadEntries.length === 0 ? (
          <Typography color="text.secondary" sx={{ mt: 0.5 }}>
            No notepad entries recorded for this Award.
          </Typography>
        ) : (
          <Stack spacing={1} sx={{ mt: 0.5 }}>
            {data.notepadEntries.map((entry) => (
              <Card key={entry.awardNotepadId} variant="outlined">
                <CardContent>
                  <Typography sx={{ fontWeight: 700 }}>
                    {entry.noteTopic ?? "Untitled note"}
                  </Typography>

                  <Typography
                    variant="caption"
                    color="text.secondary"
                    sx={{ display: "block" }}
                  >
                    {entry.sourceCreateTimestamp
                      ? `Created ${entry.sourceCreateTimestamp}`
                      : ""}
                    {entry.sourceCreateUser ? ` by ${entry.sourceCreateUser}` : ""}
                  </Typography>

                  <Typography
                    variant="body2"
                    sx={{ mt: 0.75, whiteSpace: "pre-wrap" }}
                  >
                    {entry.comments ?? "—"}
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
