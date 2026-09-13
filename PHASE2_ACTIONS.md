Phase 2 actions and next steps

This file was created by the assistant to record the next actions for Phase 2 (Library & Scanner Pro).

Planned immediate work:
1. Implement feature/sources-management (Source entity + DAO + Compose UI + persistent UriPermissions + Re-scan per source). Commit to feature/sources-management branch.
2. Implement jobs infra (ScanJob entity + WorkManager wrappers).
3. Implement incremental scanner core with partial hashing and resume tokens.
4. Implement scan dashboard UI and thumbnail cache.

I will open PRs and attach debug APK artifacts for testing. If you prefer to review patches locally, run the scaffold script and apply commits as described in the repo README.
