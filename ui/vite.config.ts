import path from 'node:path'
import { defineConfig, type Plugin } from 'vite'
import react from '@vitejs/plugin-react'

/*
 * "authz-demo" mode only (npm run demo:authz): swaps src/auth.ts for the
 * synthetic-identity demo sign-in. In every other mode - including the
 * deployable production build - this plugin is not installed, so the demo
 * module is never resolved or bundled.
 */
function authzDemoAuth(): Plugin {
  const demoAuth = path.resolve(__dirname, 'src/demo/authz-demo/demoAuth.ts')
  return {
    name: 'authz-demo-auth',
    enforce: 'pre',
    async resolveId(source, importer, options) {
      const resolved = await this.resolve(source, importer, { ...options, skipSelf: true })
      if (resolved && resolved.id === path.resolve(__dirname, 'src/auth.ts')) {
        return demoAuth
      }
      return null
    },
  }
}

/*
 * "identity-lab" mode only (scripts/identity-lab/start.sh): keeps the
 * production src/auth.ts and its Amplify sign-in, adding one line so every
 * Cognito user-pool API call (refresh, sign-out) goes to the lab's simulated
 * Cognito instead of AWS. Fails the build if auth.ts no longer matches.
 */
function identityLabUserPoolEndpoint(): Plugin {
  const authFile = path.resolve(__dirname, 'src/auth.ts')
  return {
    name: 'identity-lab-user-pool-endpoint',
    enforce: 'pre',
    transform(code, id) {
      if (id !== authFile) {
        return null
      }
      const anchor = '      userPoolClientId,\n'
      if (!code.includes(anchor)) {
        throw new Error('identity-lab: src/auth.ts changed; cannot point Amplify at the simulated Cognito')
      }
      return code.replace(anchor, anchor + '      userPoolEndpoint: import.meta.env.VITE_LAB_USER_POOL_ENDPOINT,\n')
    },
  }
}

function modePlugins(mode: string): Plugin[] {
  if (mode === 'authz-demo') return [authzDemoAuth()]
  if (mode === 'identity-lab') return [identityLabUserPoolEndpoint()]
  return []
}

// https://vite.dev/config/
export default defineConfig(({ mode }) => ({
  plugins: [...modePlugins(mode), react()],
}))
