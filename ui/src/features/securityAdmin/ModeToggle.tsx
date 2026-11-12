import InventoryOutlined from "@mui/icons-material/Inventory2Outlined";
import ShieldOutlined from "@mui/icons-material/GppGoodOutlined";
import ToggleButton from "@mui/material/ToggleButton";
import ToggleButtonGroup from "@mui/material/ToggleButtonGroup";
import { useLocation, useNavigate } from "react-router-dom";

import { showsModeToggle } from "./securityAdminPresentation.mjs";
import type { Capabilities } from "./securityAdminPresentation.d.mts";

/*
 * Research Archive / Security Admin, in the header beside the signed-in user.
 *
 * Rendered ONLY for a verified active administrator. Absent - not disabled -
 * for everyone else, so a non-administrator sees exactly today's header with
 * no hint the mode exists.
 *
 * Visibility is NOT access control. It keys on `administrator` alone, so an
 * administrator keeps the toggle while enforcement is off; selecting it then
 * reaches the unavailable screen. Every administrator endpoint enforces
 * authorization independently regardless of what this renders.
 */
export function ModeToggle({ capabilities }: { capabilities: Capabilities }) {
  const navigate = useNavigate();
  const location = useLocation();

  if (!showsModeToggle(capabilities)) {
    return null;
  }

  const inAdmin = location.pathname.startsWith("/admin");

  return (
    <ToggleButtonGroup
      exclusive
      size="small"
      value={inAdmin ? "securityAdmin" : "archive"}
      aria-label="Application mode"
      sx={{
        display: { xs: "none", md: "inline-flex" },
        backgroundColor: "#f4f6fa",
        borderRadius: 2,
        p: 0.4,
        "& .MuiToggleButton-root": {
          border: 0,
          borderRadius: 1.5,
          px: 1.5,
          py: 0.5,
          textTransform: "none",
          fontWeight: 600,
          fontSize: "0.8125rem",
          color: "#4a5568",
          gap: 0.75,
        },
        "& .Mui-selected": {
          backgroundColor: "#1a3a6b !important",
          color: "#ffffff !important",
          boxShadow: "0 1px 2px rgba(16,24,40,0.12)",
        },
      }}
      onChange={(_event, next) => {
        if (!next) return;
        navigate(next === "securityAdmin" ? "/admin/overview" : "/");
      }}
    >
      <ToggleButton value="archive" aria-label="Research Archive">
        <InventoryOutlined sx={{ fontSize: 18 }} />
        Research Archive
      </ToggleButton>
      <ToggleButton value="securityAdmin" aria-label="Security Admin">
        <ShieldOutlined sx={{ fontSize: 18 }} />
        Security Admin
      </ToggleButton>
    </ToggleButtonGroup>
  );
}
