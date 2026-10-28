# Contributing

- **Branches:** branch from an up-to-date `main`; use a short lowercase name such as
  `feature/<change>`, `fix/<problem>`, `docs/<topic>`, or `chore/<topic>`.
- **Commits:** use small, focused Conventional Commits, for example `fix: validate listing input`.
- **Pull requests:** open a PR against `main`, describe the change and its scope, and include relevant
  test results. Keep unrelated work separate and wait for CI to pass before requesting merge.
- **Validation:** run `mvnw -B verify` before opening or updating a PR. On Windows, use
  `mvnw.cmd -B verify`.

Do not merge your own change into `main`, force-push shared branches, or commit secrets.
