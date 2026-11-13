import GppMaybeOutlined from "@mui/icons-material/GppMaybeOutlined";
import Box from "@mui/material/Box";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";

import { unavailableNotice } from "./securityAdminPresentation.mjs";
import type { Capabilities } from "./securityAdminPresentation.d.mts";

/*
 * Shown to an administrator when record enforcement is disabled.
 *
 * It renders NO search box, NO management control and NO status figure, and
 * the surrounding route issues no administrator API request at all - the
 * capability is known before any call is attempted, so a 404 is never
 * provoked and never has to be explained away.
 *
 * It is deliberately not an error. Enforcement being off is a configuration
 * state, and telling an administrator "not found" for a feature that exists
 * and is merely switched off would be wrong.
 */
export function SecurityAdminUnavailable({ capabilities }: { capabilities: Capabilities }) {
  const notice = unavailableNotice(capabilities);

  return (
    <Box sx={{ maxWidth: 720 }}>
      <Paper
        variant="outlined"
        sx={{ p: { xs: 2.5, sm: 4 }, borderRadius: 3, borderColor: "#e7e9ee" }}
      >
        <Stack sx={{ gap: 2.5 }}>
          <Stack sx={{ flexDirection: "row", gap: 2, alignItems: "flex-start" }}>
            <Box
              sx={{
                flexShrink: 0,
                width: 48,
                height: 48,
                borderRadius: 2,
                display: "grid",
                placeItems: "center",
                backgroundColor: "#fff4e5",
                color: "#b54708",
              }}
            >
              <GppMaybeOutlined />
            </Box>
            <Box>
              <Typography component="h1" variant="h5" sx={{ fontWeight: 700 }}>
                {notice.title}
              </Typography>
              <Typography variant="body1" color="text.secondary" sx={{ mt: 0.75 }}>
                {notice.reason}
              </Typography>
            </Box>
          </Stack>

          <Typography variant="body2" color="text.secondary">
            {notice.clarification}
          </Typography>

          <Box
            sx={{
              backgroundColor: "#f7f8fa",
              borderRadius: 2,
              px: 2,
              py: 1.5,
            }}
          >
            <Typography variant="overline" color="text.secondary" sx={{ letterSpacing: "0.08em" }}>
              Current state
            </Typography>
            <Typography variant="body2" sx={{ mt: 0.5 }}>
              Authorization enforcement:{" "}
              <Box component="span" sx={{ fontWeight: 700 }}>
                {notice.enforcementEnabled ? "ON" : "OFF"}
              </Box>
            </Typography>
          </Box>
        </Stack>
      </Paper>
    </Box>
  );
}
