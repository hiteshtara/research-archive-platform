import type { SxProps, Theme } from "@mui/material";
import type { ReactNode } from "react";
import { Link as RouterLink } from "react-router-dom";

import { ResultSurface } from "./ResultSurface";

/**
 * A navigational archive result: the shared ResultSurface plus a real
 * link.
 *
 * The whole card is an anchor, not a div with an onClick, so Cmd-click,
 * Ctrl-click, middle-click, "Open in new tab" and "Copy link address"
 * all work. Link styling is reset to inherit, so this is visually
 * identical to the click-handler card it replaced.
 *
 * Results whose action is not navigation - a file download - use
 * ResultSurface directly with their own action rather than being given
 * an href that would be wrong to copy.
 */
export function ResultCard({
  to,
  identifier,
  secondaryIdentifier,
  status,
  title,
  metadata,
  rightSlot,
  emphasized = false,
  banner,
  sx,
}: {
  /**
   * Route this card links to. Omitted when a result has no reachable
   * record (Global Search can return one), in which case the card
   * renders identically but is not a link and is not clickable.
   */
  to?: string;
  identifier: ReactNode;
  secondaryIdentifier?: ReactNode;
  status?: ReactNode;
  title?: ReactNode;
  metadata?: ReactNode;
  rightSlot?: ReactNode;
  emphasized?: boolean;
  banner?: ReactNode;
  sx?: SxProps<Theme>;
}) {
  return (
    <ResultSurface
      cardProps={to ? { component: RouterLink, to } : undefined}
      interactive={Boolean(to)}
      identifier={identifier}
      secondaryIdentifier={secondaryIdentifier}
      status={status}
      title={title}
      metadata={metadata}
      rightSlot={rightSlot}
      emphasized={emphasized}
      banner={banner}
      sx={sx}
    />
  );
}
