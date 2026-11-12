import ArrowForward from "@mui/icons-material/ArrowForwardOutlined";
import FactCheckOutlined from "@mui/icons-material/FactCheckOutlined";
import GppGoodOutlined from "@mui/icons-material/GppGoodOutlined";
import InfoOutlined from "@mui/icons-material/InfoOutlined";
import AccountTreeOutlined from "@mui/icons-material/AccountTreeOutlined";
import PeopleAltOutlined from "@mui/icons-material/PeopleAltOutlined";
import ShieldOutlined from "@mui/icons-material/AdminPanelSettingsOutlined";
import Box from "@mui/material/Box";
import Chip from "@mui/material/Chip";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import { Link as RouterLink } from "react-router-dom";

import { systemStatusRows } from "../../features/securityAdmin/securityAdminPresentation.mjs";
import type { Capabilities } from "../../features/securityAdmin/securityAdminPresentation.d.mts";

/*
 * Security Administration landing page.
 *
 * Every figure here comes from verified API data or is shown as Unavailable.
 * Nothing is hardcoded - in particular the active-grant count, which cannot be
 * read while the administrator API is unreachable and is never defaulted to a
 * number, because a hardcoded count is indistinguishable from a measured one.
 */
const AREAS = [
  { key: "identities", title: "Identities", path: "/admin/identities",
    description: "Find users, inspect linked identities and account status",
    Icon: PeopleAltOutlined, tint: "#eef4ff", ink: "#1a3a6b" },
  { key: "administrators", title: "Administrators", path: "/admin/administrators",
    description: "Review who holds ARCHIVE_ADMIN and how it was granted",
    Icon: ShieldOutlined, tint: "#eef9f1", ink: "#1b6b3a" },
  { key: "permissions", title: "Permissions", path: "/admin/units",
    description: "Unit hierarchy and how access is inherited",
    Icon: AccountTreeOutlined, tint: "#f3effb", ink: "#5b3a9b" },
  { key: "audit", title: "Audit Log", path: "/admin/audit",
    description: "Every recorded access and administrative change",
    Icon: FactCheckOutlined, tint: "#fff4e5", ink: "#b54708" },
];

export function SecurityAdminOverviewPage({
  capabilities,
  activeGrants,
}: {
  capabilities: Capabilities;
  activeGrants?: number;
}) {
  const status = systemStatusRows(capabilities, { activeGrants });

  return (
    <Stack sx={{ gap: 3 }}>
      <Stack sx={{ flexDirection: "row", gap: 2, alignItems: "center" }}>
        <Box
          sx={{
            width: 52, height: 52, borderRadius: 2.5, flexShrink: 0,
            display: "grid", placeItems: "center",
            backgroundColor: "#eef4ff", color: "#1a3a6b",
          }}
        >
          <GppGoodOutlined sx={{ fontSize: 30 }} />
        </Box>
        <Box>
          <Typography component="h1" variant="h4" sx={{ fontWeight: 700 }}>
            Security Administration
          </Typography>
          <Typography variant="body1" color="text.secondary">
            Manage user access, permissions and audit history for the Research Archive.
          </Typography>
        </Box>
      </Stack>

      <Box
        sx={{
          display: "grid",
          gap: 2,
          gridTemplateColumns: { xs: "1fr", sm: "1fr 1fr", lg: "repeat(4, 1fr)" },
        }}
      >
        {AREAS.map(({ key, title, path, description, Icon, tint, ink }) => (
          <Paper
            key={key}
            variant="outlined"
            component={RouterLink}
            to={path}
            sx={{
              p: 2.5, borderRadius: 3, borderColor: "#e7e9ee",
              textDecoration: "none", color: "inherit", display: "block",
              transition: "box-shadow 120ms, border-color 120ms",
              "&:hover": { borderColor: "#c9d2e3", boxShadow: "0 2px 10px rgba(16,24,40,0.07)" },
            }}
          >
            <Stack sx={{ flexDirection: "row", justifyContent: "space-between", alignItems: "flex-start" }}>
              <Box
                sx={{
                  width: 40, height: 40, borderRadius: 2,
                  display: "grid", placeItems: "center",
                  backgroundColor: tint, color: ink, mb: 1.5,
                }}
              >
                <Icon />
              </Box>
              <ArrowForward sx={{ fontSize: 18, color: "text.disabled" }} />
            </Stack>
            <Typography variant="h6" sx={{ fontWeight: 700, fontSize: "1.0625rem" }}>
              {title}
            </Typography>
            <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
              {description}
            </Typography>
          </Paper>
        ))}
      </Box>

      <Paper variant="outlined" sx={{ p: 2, borderRadius: 3, borderColor: "#e7e9ee" }}>
        <Stack
          sx={{
            flexDirection: { xs: "column", md: "row" },
            gap: { xs: 1.5, md: 2 },
            alignItems: { xs: "flex-start", md: "center" },
            flexWrap: "wrap",
          }}
        >
          <Stack sx={{ flexDirection: "row", gap: 1, alignItems: "center", pr: 1 }}>
            <InfoOutlined sx={{ fontSize: 20, color: "#1a3a6b" }} />
            <Typography variant="subtitle2" sx={{ fontWeight: 700 }}>
              System Status
            </Typography>
          </Stack>

          {status.map((row) => (
            <Chip
              key={row.key}
              size="small"
              variant={row.tone === "active" ? "filled" : "outlined"}
              color={row.tone === "active" ? "success" : "default"}
              label={`${row.label}: ${row.value}`}
              sx={{ fontWeight: 600 }}
            />
          ))}
        </Stack>
      </Paper>
    </Stack>
  );
}
