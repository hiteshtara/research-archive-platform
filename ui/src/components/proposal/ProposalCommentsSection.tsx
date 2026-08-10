import { ExpandLessOutlined, ExpandMoreOutlined } from "@mui/icons-material";
import {
  Box,
  Card,
  CardContent,
  Collapse,
  Divider,
  IconButton,
  Stack,
  Typography,
} from "@mui/material";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";

import { getProposalCommentsV1 } from "../../api/client";
import type {
  ProposalCommentCategoryV1,
  ProposalCommentEntryV1,
} from "../../types/api";
import { EmptyState } from "../common/EmptyState";
import { ErrorState } from "../common/ErrorState";
import { LoadingState } from "../common/LoadingState";

function metadataSuffix(entry: ProposalCommentEntryV1): string {
  if (!entry.sourceUpdateTimestamp && !entry.sourceUpdateUser) {
    return "";
  }
  const updated = entry.sourceUpdateTimestamp
    ? `updated ${entry.sourceUpdateTimestamp}`
    : "";
  const by = entry.sourceUpdateUser ? `by ${entry.sourceUpdateUser}` : "";
  return ` (${[updated, by].filter(Boolean).join(" ")})`;
}

function CommentHistoryEntry({ entry }: { entry: ProposalCommentEntryV1 }) {
  return (
    <Box sx={{ py: 1 }}>
      <Typography
        variant="caption"
        color="text.secondary"
        sx={{ display: "block" }}
      >
        Proposal Version {entry.sequenceNumber ?? "—"}
        {entry.sourceUpdateTimestamp
          ? ` · Updated ${entry.sourceUpdateTimestamp}`
          : ""}
        {entry.sourceUpdateUser ? ` by ${entry.sourceUpdateUser}` : ""}
      </Typography>
      <Typography variant="body2" sx={{ mt: 0.5, whiteSpace: "pre-wrap" }}>
        {entry.comments ?? "—"}
      </Typography>
    </Box>
  );
}

function CommentCategoryCard({
  category,
}: {
  category: ProposalCommentCategoryV1;
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
        ) : current.comments === null || current.comments === "" ? (
          <Typography
            color="text.secondary"
            sx={{ mt: 0.75, fontStyle: "italic" }}
          >
            No comment text recorded for Proposal Version{" "}
            {current.sequenceNumber ?? "—"}
            {metadataSuffix(current)}
          </Typography>
        ) : (
          <>
            <Typography
              variant="body2"
              sx={{ mt: 0.75, whiteSpace: "pre-wrap" }}
            >
              {current.comments}
            </Typography>

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
                        key={entry.proposalCommentId ?? entry.sequenceNumber}
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

// Comments - lazy-loads from GET /api/v1/proposals/{proposalId}/comments.
// Shows only "Proposal Comments" and "Proposal IP Review Comments"
// (codes 12/13), family-wide (every version), with history behavior
// matching Award's own Comments screen.
export function ProposalCommentsSection({
  proposalId,
}: {
  proposalId: number;
}) {
  const commentsQuery = useQuery({
    queryKey: ["proposal-comments-v1", proposalId],
    queryFn: ({ signal }) => getProposalCommentsV1(proposalId, signal),
  });

  if (commentsQuery.isLoading) {
    return <LoadingState mode="skeleton" height={80} count={2} />;
  }

  if (commentsQuery.isError) {
    return <ErrorState message="Unable to load Comments." />;
  }

  const categories = commentsQuery.data?.commentCategories ?? [];

  if (categories.length === 0) {
    return (
      <EmptyState
        variant="text"
        message="No comments are recorded for this Proposal."
      />
    );
  }

  return (
    <Stack spacing={1}>
      {categories.map((category) => (
        <CommentCategoryCard
          key={category.commentTypeCode}
          category={category}
        />
      ))}
    </Stack>
  );
}
