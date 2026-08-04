import { ExpandLessOutlined, ExpandMoreOutlined } from "@mui/icons-material";
import {
  Alert,
  Box,
  Card,
  CardContent,
  Collapse,
  Divider,
  IconButton,
  Skeleton,
  Stack,
  Typography,
} from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";

import { getAwardCommentsV1 } from "../../api/client";
import { hasAnyComments } from "../../features/award/awardSectionsPresentation.mjs";
import type {
  AwardCommentCategoryV1,
  AwardCommentEntryV1,
} from "../../types/api";

function blankCommentMetadataSuffix(entry: AwardCommentEntryV1): string {
  if (!entry.updateTimestamp && !entry.updateUser) {
    return "";
  }
  const updated = entry.updateTimestamp ? `updated ${entry.updateTimestamp}` : "";
  const by = entry.updateUser ? `by ${entry.updateUser}` : "";
  return ` (${[updated, by].filter(Boolean).join(" ")})`;
}

function CommentHistoryEntry({ entry }: { entry: AwardCommentEntryV1 }) {
  return (
    <Box sx={{ py: 1 }}>
      <Typography
        variant="caption"
        color="text.secondary"
        sx={{ display: "block" }}
      >
        Award Version {entry.sequenceNumber ?? "—"}
        {entry.workflowDocumentNumber
          ? ` · Workflow Document ${entry.workflowDocumentNumber}`
          : ""}
        {entry.updateTimestamp ? ` · Updated ${entry.updateTimestamp}` : ""}
        {entry.updateUser ? ` by ${entry.updateUser}` : ""}
      </Typography>
      <Typography variant="body2" sx={{ mt: 0.5, whiteSpace: "pre-wrap" }}>
        {entry.commentText ?? "—"}
      </Typography>
    </Box>
  );
}

function CommentCategoryCard({
  category,
}: {
  category: AwardCommentCategoryV1;
}) {
  const [historyOpen, setHistoryOpen] = useState(false);
  const { current, history } = category;

  return (
    <Card variant="outlined">
      <CardContent>
        <Typography
          variant="caption"
          color="text.secondary"
          sx={{ textTransform: "uppercase", letterSpacing: "0.04em" }}
        >
          {category.commentTypeDescription ?? category.commentTypeCode}
        </Typography>

        {current === null ? (
          <Typography color="text.secondary" sx={{ mt: 0.75 }}>
            No comment recorded
          </Typography>
        ) : (
          <>
            {current.commentText === null || current.commentText === "" ? (
              // A real award_comment row exists for this version - it was
              // just saved with blank text. Distinct from "no comment
              // recorded" (current === null, no row at all): this is real
              // archived data, so it gets its own metadata line rather
              // than an unexplained dash.
              <Typography
                color="text.secondary"
                sx={{ mt: 0.75, fontStyle: "italic" }}
              >
                No comment text recorded for Award Version{" "}
                {current.sequenceNumber ?? "—"}
                {blankCommentMetadataSuffix(current)}
              </Typography>
            ) : (
              <Typography
                variant="body2"
                sx={{ mt: 0.75, whiteSpace: "pre-wrap" }}
              >
                {current.commentText}
              </Typography>
            )}

            {history.length > 1 && (
              <Box sx={{ mt: 1 }}>
                <Stack
                  direction="row"
                  spacing={0.5}
                  onClick={() => setHistoryOpen((open) => !open)}
                  sx={{
                    alignItems: "center",
                    cursor: "pointer",
                    width: "fit-content",
                  }}
                >
                  <Typography
                    variant="button"
                    color="primary"
                    sx={{ textTransform: "none" }}
                  >
                    View History ({history.length})
                  </Typography>
                  <IconButton
                    size="small"
                    aria-label={
                      historyOpen ? "Collapse history" : "Expand history"
                    }
                  >
                    {historyOpen ? (
                      <ExpandLessOutlined fontSize="small" />
                    ) : (
                      <ExpandMoreOutlined fontSize="small" />
                    )}
                  </IconButton>
                </Stack>

                <Collapse in={historyOpen}>
                  <Stack divider={<Divider />} sx={{ mt: 0.5 }}>
                    {history.map((entry) => (
                      <CommentHistoryEntry
                        key={entry.awardCommentId ?? entry.sequenceNumber}
                        entry={entry}
                      />
                    ))}
                  </Stack>
                </Collapse>
              </Box>
            )}
          </>
        )}
      </CardContent>
    </Card>
  );
}

// Comments and Notepad - lazy-loads from GET
// /api/v1/awards/{awardId}/comments. Comments are grouped by
// human-readable category and cover the whole Award number family
// (every version), matching Kuali's own Award Comments screen -
// award_notepad is a separate group with no sequence_number at all,
// also family-wide.
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

  if (!hasAnyComments(data.commentCategories, data.notepadEntries)) {
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

        <Stack spacing={1} sx={{ mt: 0.5 }}>
          {data.commentCategories.map((category) => (
            <CommentCategoryCard
              key={category.commentTypeCode}
              category={category}
            />
          ))}
        </Stack>
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
