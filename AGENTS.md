# Listing Guard agent instructions

Read [CLAUDE.md](CLAUDE.md) before making code changes. It is the canonical source for current project architecture, scope, ADR status, commands, and conventions. Do not maintain a copied project-status snapshot here.

- Follow the repository conventions and verification requirements in `CLAUDE.md`.
- When changing an MCP tool name, parameters, or description, update `skills/listing-guard/` and its ToolCatalog-backed test.
- When the user types `/graphify`, use the installed graphify skill or instructions at `~/.Codex/skills/graphify/SKILL.md` before doing anything else.
