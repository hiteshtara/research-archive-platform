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

// https://vite.dev/config/
export default defineConfig(({ mode }) => ({
  plugins: mode === 'authz-demo' ? [authzDemoAuth(), react()] : [react()],
}))
