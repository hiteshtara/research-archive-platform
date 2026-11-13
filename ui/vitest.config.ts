import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

/*
 * Component tests only.
 *
 * The presentation-helper tests in this repo are plain `node:test` files
 * (`*.test.mjs`) and keep running under `node --test`; vitest deliberately
 * does NOT pick them up, because they import from `node:test` and would
 * fail under a different runner. `npm run test` runs both suites in turn.
 *
 * jsdom rather than a real browser: these assert what a component renders
 * and which requests it issues, neither of which needs a browser engine,
 * and CI then needs no browser download.
 */
export default defineConfig({
  plugins: [react()],
  test: {
    include: ["src/**/*.test.tsx"],
    environment: "jsdom",
    globals: false,
    setupFiles: ["./src/test/setup.ts"],
    /*
     * src/auth.ts validates its Cognito configuration at module load and
     * throws when anything is missing, so mounting the real App needs these
     * present. They are deliberately obvious fakes: no test authenticates,
     * and the pool id must share the region's prefix or auth.ts rejects the
     * pair as swapped.
     */
    env: {
      VITE_API_BASE_URL: "https://api.test.invalid",
      VITE_AWS_REGION: "us-east-1",
      VITE_COGNITO_USER_POOL_ID: "us-east-1_TESTPOOL",
      VITE_COGNITO_CLIENT_ID: "test-client-id",
      VITE_COGNITO_DOMAIN: "test.auth.us-east-1.amazoncognito.com",
      VITE_COGNITO_REDIRECT_URL: "https://ui.test.invalid/",
      VITE_COGNITO_LOGOUT_URL: "https://ui.test.invalid/logout",
    },
    restoreMocks: true,
  },
});
