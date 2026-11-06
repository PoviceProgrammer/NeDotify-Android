# Publication check — 2026-11-05

Scope: all 310 tracked baseline files at `0b10607ddb2b9cbce215430f0c48b4928f0abdb7`, including historical reports, before making the new repository public.

- No tracked APK/AAB, user DB/SQLite, keystore/P12/PEM, .env or local.properties files.
- Checked text for GitHub credential patterns, private-key headers, Bearer literals, assigned access/refresh/client-secret/password values. Five assigned-token candidates were inspected: all are fixed fake credentials in AuthPkceTest and ProviderConnectionsTest MockWebServer fixtures.
- No live credential found by these checks. This is a bounded publication inspection, not a security certification.
- Historical reports contain workstation paths; they are retained as historical evidence, not treated as current validation.
- Preserve the original NeDotify MIT license (2026 PoviceProgrammer) in the standalone root.
- Public visibility was explicitly authorized by the user after GitHub rejected private-repository protection with HTTP 403. Protection was then applied successfully: required context android/source, strict=true, enforce_admins=true, no force pushes/deletion.

New artifacts must undergo the same check before pushing. Device accounts, actual library files and credentials must never be included in fixtures or reports.
