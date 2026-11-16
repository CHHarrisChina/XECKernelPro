# OMY toolbox integration validation

Implemented in the OMY worktree without discarding its prior dirty changes:

- Toolbox standalone TEE diagnostics/software restore and separately confirmed vendor TEE writeback using existing checks.
- Independent restricted local OMY WebUI; configuration backup/atomic save and fixed restart markers.
- Selectable keybox pool: two existing fixed upstream feeds, maximum ten distinct candidates, local XML/key/chain/date checks, actual count and timestamps. No guaranteed ten candidates and no assertion of online attestation acceptance. Global user count is unknown.
- Applying a keybox uses the existing validated import and backup command, pauses keybox auto replacement, and restores the prior enabled setting when import fails.
- SUSFS release/version editor with optional boot persistence; late-load status takes priority over LKM display.
- Own-XEC JNI identity only, exact named owned driver descriptors, recoverable Rust descriptor cache, route-tagged root shells, persisted confirmed XEC provider to prevent late-load fallback to foreign PATH su. First-time Mount path requires a Magisk version response.

Validation completed:

- Android arm64 cargo ndk check, clippy, cargo fmt, release build: passed. Existing manifest unused dependency/profile warnings remain.
- Manager clean assembleRelease: passed including C++ and Kotlin. Existing Kotlin/resource/Gradle warnings remain.
- git diff --check: passed (existing CRLF notices).
- APK contains assets/omy/index.html. Staged libxeccli.so matches this build's xudc byte-for-byte; packaged libxeccli.so matches Gradle's stripped output byte-for-byte.
- Local APK SHA256: 297b32d2e26a60b85b91f008a56264fe07c1edff9df3866a56dec6496254f767.
- APK is local debug-fallback signed, version code 31430 from this branch; it is not the official 31432 release and has not been published.

Remaining runtime validation:

- Both live fixed upstream fetch attempts timed out from this machine; no current candidate count or remote certificate health is claimed.
- No physical Android device test of TEE restoration, keybox import, SUSFS persistence, or reboot provider recovery was performed.
- Certificates being current and matching their private keys do not establish revocation status, root trust, or acceptance by attestation services.
- Persisted provider selection deliberately retains XEC after its driver was seen; changing to another root installation requires clearing this provider preference/app data in this implementation.
