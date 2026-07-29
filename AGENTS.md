# Research Archive Platform

Instructions for AI coding agents (Codex, ChatGPT, Gemini CLI, etc.) working
on this repository.

For architecture, commands, and coding conventions, see **CLAUDE.md** — that
is the single source of truth for this repo, not this file. See
`PROJECT_MEMORY.md` for the project's longer-term history, data-grain
validation, and deployment incident lessons.

## Before committing

- Run the relevant tests (`cd api && mvn test`; `cd ui && npm run build`).
- Show changed files and, for anything touching counts/grain, the exact
  SQL/logic and test results.
- Do not commit or push unless explicitly requested.
