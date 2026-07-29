# Research Archive Platform — UI

React + TypeScript + Vite frontend for the Research Archive Platform. See the
[repository root README](../README.md) for the overall system and data flow.

## Development

```
npm install
npm run dev      # start the Vite dev server
npm run build     # type-check and build for production
npm run test      # run presentation-helper unit tests
npm run lint      # oxlint
```

Configuration is read from `VITE_*` environment variables in `.env.local` /
`.env.development` (see `src/api/client.ts` and `src/pages/AwardHistoryPage.tsx`
for the variables currently in use).
