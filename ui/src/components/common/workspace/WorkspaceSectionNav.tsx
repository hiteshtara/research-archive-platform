import { List, ListItemButton, ListItemText } from "@mui/material";

/*
 * Left section navigation. On mobile it becomes a horizontally
 * scrollable strip with a fade mask at the right edge, which is how the
 * Award workspace has always behaved - see the maskImage below.
 */
export function WorkspaceSectionNav<Key extends string>({
  sections,
  activeSection,
  onSelect,
}: {
  sections: readonly { key: Key; label: string }[];
  activeSection: Key;
  onSelect: (key: Key) => void;
}) {
  return (
    <List
      sx={{
        width: { xs: "100%", md: 220 },
        flexShrink: 0,
        border: "1px solid",
        borderColor: "divider",
        borderRadius: 2,
        p: 1,
        display: { xs: "flex", md: "block" },
        flexDirection: "row",
        flexWrap: "nowrap",
        overflowX: { xs: "auto", md: "visible" },
        gap: { xs: 0.5, md: 0 },
        maskImage: {
          xs: "linear-gradient(to right, black 92%, transparent 100%)",
          md: "none",
        },
        WebkitMaskImage: {
          xs: "linear-gradient(to right, black 92%, transparent 100%)",
          md: "none",
        },
      }}
    >
      {sections.map((section) => (
        <ListItemButton
          key={section.key}
          selected={activeSection === section.key}
          onClick={() => onSelect(section.key)}
          sx={{
            borderRadius: 1.5,
            mb: { xs: 0, md: 0.25 },
            flexShrink: { xs: 0, md: 1 },
            whiteSpace: "nowrap",
            "&.Mui-selected": {
              backgroundColor: "rgba(139, 24, 50, 0.10)",
              color: "primary.main",
            },
          }}
        >
          <ListItemText
            primary={section.label}
            slotProps={{
              primary: { sx: { fontSize: 13, fontWeight: 600 } },
            }}
          />
        </ListItemButton>
      ))}
    </List>
  );
}
