# Contributing

Start with [AGENTS.md](AGENTS.md) and the [architecture map](docs/ARCHITECTURE.md).
The same instructions apply to human and AI-assisted changes.

1. Describe the user-visible problem and expected behavior in an issue or PR.
2. Keep changes focused; reuse the existing native controls and storage helpers.
3. Run `bash scripts/checks.sh` and the relevant emulator suites from
   [TESTING.md](docs/TESTING.md). Use a disposable emulator, not a personal phone.
4. For UI changes include real screenshots in light/dark, narrow/wide windows,
   and the relevant large-font or keyboard case. Use original demonstration stories.
5. Explain validation and any remaining limitation in the PR description.

Never add signing keys, private books, machine-specific paths or generated builds.
The application ID, signing certificate, book IDs and text offsets are compatibility boundaries.

Releases are made from `main` using a version tag after CI succeeds. See
[RELEASING.md](docs/RELEASING.md). A public repository does not yet imply an
open-source license: no redistribution/modification license has been chosen.
