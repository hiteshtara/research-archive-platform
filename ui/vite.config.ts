import { execSync } from 'node:child_process'
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

/*
 * The commit this bundle was built from, so a page can say which source it
 * is showing rather than leaving a reader to guess.
 *
 * This exists because the QA status page is a static snapshot compiled into
 * the bundle: on 2026-11-06 the deployed page announced 51 cases and no
 * known issues while the repository held 54 cases and three, and nothing
 * on the page could distinguish "the data is wrong" from "the build is
 * old". It was the second.
 *
 * AWS_COMMIT_ID is what Amplify sets; git is the local-dev fallback. A
 * build must never fail over this, so any error resolves to "unknown".
 */
function buildCommit(): string {
  if (process.env.AWS_COMMIT_ID) {
    return process.env.AWS_COMMIT_ID.slice(0, 12)
  }
  try {
    return execSync('git rev-parse --short=12 HEAD', {
      stdio: ['ignore', 'pipe', 'ignore'],
    })
      .toString()
      .trim()
  } catch {
    return 'unknown'
  }
}

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  define: {
    __QA_BUILD_COMMIT__: JSON.stringify(buildCommit()),
  },
})
