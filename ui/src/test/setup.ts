import { afterEach } from "vitest";
import { cleanup } from "@testing-library/react";

// Every test mounts into a fresh document. Without this a previous test's
// tree stays in the DOM and a query like "no toggle is rendered" could pass
// or fail on the wrong render.
afterEach(() => {
  cleanup();
});
