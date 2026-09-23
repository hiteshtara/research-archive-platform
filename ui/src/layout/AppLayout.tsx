import {
  AccountCircleOutlined,
  ArchiveOutlined,
  DashboardOutlined,
  DescriptionOutlined,
  FindInPageOutlined,
  GavelOutlined,
  HandshakeOutlined,
  HistoryOutlined,
  LogoutOutlined,
  SearchOutlined,
} from "@mui/icons-material";
import {
  AppBar,
  Box,
  Button,
  Chip,
  Drawer,
  List,
  ListItemButton,
  ListItemIcon,
  ListItemText,
  Stack,
  Toolbar,
  Typography,
} from "@mui/material";
import { useEffect, useState } from "react";
import { NavLink, Outlet } from "react-router-dom";

import { currentUser, logout } from "../auth";
import { sidebarNavigationItems } from "../features/navigation/navigationPresentation.mjs";
import { useAttachmentAccess } from "../hooks/useAttachmentAccess";

// The only sidebar entry gated on Cognito group membership (rather than
// a build-time flag like EXPLORER_ENABLED below) - see
// AttachmentAuthorizationService and
// docs/architecture/NEGOTIATION_ATTACHMENT_ACCESS_DESIGN.md. Hiding
// this is a UX convenience only: every attachment endpoint re-checks
// the real group server-side regardless of whether this link is shown.
const ATTACHMENT_GATED_PATH = "/archived-files";

const drawerWidth = 250;


// Icons are JSX and can't live in the plain-data presentation-helper
// module, so each item's icon is looked up here by key instead - same
// pattern DashboardPage.tsx uses for its dashboard cards.
const NAV_ICONS: Record<string, React.ReactNode> = {
  dashboard: <DashboardOutlined />,
  awards: <ArchiveOutlined />,
  historicalAwards: <HistoryOutlined />,
  proposals: <DescriptionOutlined />,
  negotiations: <HandshakeOutlined />,
  subawards: <GavelOutlined />,
  archivedFiles: <FindInPageOutlined />,
  globalSearch: <SearchOutlined />,
};

type NavigationEntry = {
  label: string;
  icon: React.ReactNode;
  path: string;
  badge?: string;
};

// The primary navigation is exactly the archive's domains, nothing else.
//
// The Archive Explorer and Proposal Explorer developer tools used to
// appear here behind VITE_EXPLORER_ENABLED with a "Dev" badge. Their
// routes (/explorer, /explorer/proposals), pages and their own
// getExplorer* endpoints are all untouched and still gated by that same
// flag - they were removed from the sidebar only, so the finished
// application does not advertise developer tooling.
const navigation: NavigationEntry[] = sidebarNavigationItems.map((item) => ({
  label: item.label,
  icon: NAV_ICONS[item.key],
  path: item.path,
}));

export function AppLayout() {
  const [signedInUser, setSignedInUser] = useState("Signed in");
  const [signingOut, setSigningOut] = useState(false);
  const attachmentAccess = useAttachmentAccess();

  useEffect(() => {
    let active = true;

    async function loadUser() {
      try {
        const user = await currentUser();

        if (!active) {
          return;
        }

        const displayName =
          user.signInDetails?.loginId ??
          user.username ??
          "Signed in";

        setSignedInUser(displayName);
      } catch {
        if (active) {
          setSignedInUser("Signed in");
        }
      }
    }

    void loadUser();

    return () => {
      active = false;
    };
  }, []);

  const visibleNavigation = attachmentAccess
    ? navigation
    : navigation.filter((item) => item.path !== ATTACHMENT_GATED_PATH);

  async function handleSignOut() {
    try {
      setSigningOut(true);
      await logout();
    } finally {
      setSigningOut(false);
    }
  }

  return (
    <Box sx={{ display: "flex", minHeight: "100vh" }}>
      <AppBar
        position="fixed"
        sx={{
          zIndex: (theme) => theme.zIndex.drawer + 1,
          backgroundColor: "#ffffff",
          color: "#172033",
          borderBottom: "1px solid #e7e9ee",
          boxShadow: "none",
        }}
      >
        <Toolbar>
          <Box
            sx={{
              width: 38,
              height: 38,
              borderRadius: 2,
              display: "grid",
              placeItems: "center",
              backgroundColor: "primary.main",
              color: "white",
              fontWeight: 900,
              mr: 1.5,
            }}
          >
            BU
          </Box>

          <Stack
            sx={{
              width: "100%",
              flexDirection: "row",
              alignItems: "center",
              justifyContent: "space-between",
              gap: 2,
            }}
          >
            <Box>
              <Typography variant="h6">
                Boston University Research Data Hub
              </Typography>

              <Typography variant="caption" color="text.secondary">
                Legacy research administration archive
              </Typography>
            </Box>

            <Stack
              sx={{
                flexDirection: "row",
                alignItems: "center",
                gap: 1.5,
              }}
            >
              <Chip
                label="Development"
                size="small"
                variant="outlined"
                sx={{ display: { xs: "none", md: "flex" } }}
              />

              <Stack
                sx={{
                  display: { xs: "none", lg: "flex" },
                  flexDirection: "row",
                  alignItems: "center",
                  gap: 0.75,
                  maxWidth: 260,
                }}
              >
                <AccountCircleOutlined color="action" />

                <Typography
                  variant="body2"
                  noWrap
                  title={signedInUser}
                >
                  {signedInUser}
                </Typography>
              </Stack>

              <Button
                variant="outlined"
                size="small"
                startIcon={<LogoutOutlined />}
                disabled={signingOut}
                onClick={() => void handleSignOut()}
              >
                {signingOut ? "Signing out..." : "Sign out"}
              </Button>
            </Stack>
          </Stack>
        </Toolbar>
      </AppBar>

      <Drawer
        variant="permanent"
        sx={{
          width: drawerWidth,
          flexShrink: 0,
          "& .MuiDrawer-paper": {
            width: drawerWidth,
            boxSizing: "border-box",
            pt: 9,
            borderRight: "1px solid #e7e9ee",
          },
        }}
      >
        <Typography
          variant="overline"
          color="text.secondary"
          sx={{ px: 3, pt: 2 }}
        >
          Navigation
        </Typography>

        <List sx={{ px: 1.5 }}>
          {visibleNavigation.map((item) => (
            <ListItemButton
              key={item.path}
              component={NavLink}
              to={item.path}
              sx={{
                my: 0.4,
                borderRadius: 2,
                "&.active": {
                  backgroundColor: "rgba(139, 24, 50, 0.10)",
                  color: "primary.main",
                  "& .MuiListItemIcon-root": {
                    color: "primary.main",
                  },
                },
              }}
            >
              <ListItemIcon sx={{ minWidth: 42 }}>
                {item.icon}
              </ListItemIcon>

              <ListItemText primary={item.label} />

              {item.badge && (
                <Chip label={item.badge} size="small" variant="outlined" />
              )}
            </ListItemButton>
          ))}
        </List>

      </Drawer>

      <Box
        component="main"
        sx={{
          flexGrow: 1,
          p: { xs: 2, md: 4 },
          mt: 8,
          minWidth: 0,
        }}
      >
        <Outlet />
      </Box>
    </Box>
  );
}
