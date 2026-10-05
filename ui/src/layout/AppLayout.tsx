import {
  AccountCircleOutlined,
  ArchiveOutlined,
  FactCheckOutlined,
  CloseOutlined,
  DashboardOutlined,
  DescriptionOutlined,
  FindInPageOutlined,
  GavelOutlined,
  HandshakeOutlined,
  HistoryOutlined,
  LogoutOutlined,
  MenuOutlined,
  SearchOutlined,
} from "@mui/icons-material";
import {
  AppBar,
  Box,
  Button,
  Chip,
  Drawer,
  IconButton,
  List,
  ListItemButton,
  ListItemIcon,
  ListItemText,
  Stack,
  Toolbar,
  Typography,
  useMediaQuery,
} from "@mui/material";
import { useTheme } from "@mui/material/styles";
import { useEffect, useState } from "react";
import { NavLink, Outlet, useLocation } from "react-router-dom";

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
const NAVIGATION_ID = "app-navigation";

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
  qaStatus: <FactCheckOutlined />,
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

  // Below md the navigation is a temporary drawer opened from the menu
  // button, so the page gets the full width on phones and small tablets.
  // At md and up it stays the permanent sidebar it has always been.
  const theme = useTheme();
  // noSsr: read the real viewport on the first render, so a desktop
  // never briefly mounts the temporary drawer before switching.
  const isDesktop = useMediaQuery(theme.breakpoints.up("md"), { noSsr: true });
  const [mobileOpen, setMobileOpen] = useState(false);
  const location = useLocation();

  useEffect(() => {
    setMobileOpen(false);
  }, [location.pathname]);

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

  // One navigation list, rendered by whichever drawer is in use. The
  // temporary drawer adds its own close button: on phones the header sits
  // under the drawer's backdrop, so the menu button is not reachable.
  const renderNavigationContent = (onClose?: () => void) => (
    <>
      <Stack
        sx={{
          flexDirection: "row",
          alignItems: "center",
          justifyContent: "space-between",
          px: 3,
          pt: onClose ? 1 : 2,
          pr: onClose ? 1 : 3,
        }}
      >
        <Typography variant="overline" color="text.secondary">
          Navigation
        </Typography>

        {onClose && (
          <IconButton aria-label="Close navigation menu" onClick={onClose}>
            <CloseOutlined />
          </IconButton>
        )}
      </Stack>

      <List sx={{ px: 1.5 }}>
        {visibleNavigation.map((item) => (
          <ListItemButton
            key={item.path}
            component={NavLink}
            to={item.path}
            onClick={() => setMobileOpen(false)}
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
    </>
  );

  return (
    <Box sx={{ display: "flex", minHeight: "100vh" }}>
      <AppBar
        position="fixed"
        sx={{
          // Above the permanent sidebar on desktop only. Below md the
          // temporary drawer is a modal: it and its backdrop must cover
          // the header, which the modal also hides from assistive tech.
          zIndex: { md: theme.zIndex.drawer + 1 },
          backgroundColor: "#ffffff",
          color: "#172033",
          borderBottom: "1px solid #e7e9ee",
          boxShadow: "none",
        }}
      >
        <Toolbar>
          <IconButton
            edge="start"
            aria-label="Open navigation menu"
            aria-controls={NAVIGATION_ID}
            aria-expanded={mobileOpen}
            onClick={() => setMobileOpen(true)}
            sx={{ mr: 1, display: { xs: "inline-flex", md: "none" } }}
          >
            <MenuOutlined />
          </IconButton>

          <Box
            sx={{
              flexShrink: 0,
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
              // Take the space left after the menu button and logo, and
              // allow shrinking, so the title truncates instead of pushing
              // Sign out off the screen on phones.
              flex: 1,
              minWidth: 0,
              flexDirection: "row",
              alignItems: "center",
              justifyContent: "space-between",
              gap: 2,
            }}
          >
            <Box sx={{ minWidth: 0 }}>
              <Typography
                variant="h6"
                noWrap
                sx={{ fontSize: { xs: "1rem", sm: "1.25rem" } }}
              >
                Boston University Research Data Hub
              </Typography>

              <Typography
                variant="caption"
                color="text.secondary"
                sx={{ display: { xs: "none", sm: "block" } }}
              >
                Legacy research administration archive
              </Typography>
            </Box>

            <Stack
              sx={{
                flexShrink: 0,
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
                sx={{ display: { xs: "none", sm: "inline-flex" } }}
              >
                {signingOut ? "Signing out..." : "Sign out"}
              </Button>

              {/* Phones: the same action as an icon, so the header fits. */}
              <IconButton
                aria-label={signingOut ? "Signing out" : "Sign out"}
                disabled={signingOut}
                onClick={() => void handleSignOut()}
                sx={{ display: { xs: "inline-flex", sm: "none" } }}
              >
                <LogoutOutlined />
              </IconButton>
            </Stack>
          </Stack>
        </Toolbar>
      </AppBar>

      {/* A landmark only when it holds the permanent sidebar; the
          temporary drawer's paper is its own "Primary" nav. */}
      <Box
        component={isDesktop ? "nav" : "div"}
        aria-label={isDesktop ? "Primary" : undefined}
        sx={{ width: { md: drawerWidth }, flexShrink: { md: 0 } }}
      >
        {isDesktop ? (
          <Drawer
            id={NAVIGATION_ID}
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
            {renderNavigationContent()}
          </Drawer>
        ) : (
          <Drawer
            id={NAVIGATION_ID}
            variant="temporary"
            open={mobileOpen}
            onClose={() => setMobileOpen(false)}
            ModalProps={{ keepMounted: true }}
            // The modal renders in a portal outside the <nav> above, so
            // the paper carries the navigation landmark itself.
            slotProps={{
              paper: { component: "nav", "aria-label": "Primary" },
            }}
            sx={{
              "& .MuiDrawer-paper": {
                width: drawerWidth,
                maxWidth: "85vw",
                boxSizing: "border-box",
              },
            }}
          >
            {renderNavigationContent(() => setMobileOpen(false))}
          </Drawer>
        )}
      </Box>

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
