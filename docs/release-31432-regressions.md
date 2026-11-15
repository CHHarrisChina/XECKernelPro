# 31432 regression record

Base: official 31431, commit af7cca837acb689f1b01498a3efc5aad81a36770.
The 31429 local test build is superseded; do not use it as a release base.

## Root granted but direct installation unavailable

Keep the 31431 fix in KsuCli.createRootShell: libsu newJob does not collect stdout by default. Attach .to(stdout, stderr) before exec, then require UID 0 and a live root shell. An empty uncollected Result.out must never be interpreted as proof that a root grant failed.

Keep InstallScreen's rootReady/probeSettled subscriptions. In 31432 also refresh partitions, default partition, A/B status and slot suffix on those signals: an early non-root probe may cache an empty partition list or a guessed default. Preserve the OTA selection key. Direct installation still requires actual root and a supported kernel; do not bypass those checks.

## Module startup race

The post-fs-data script deadline previously started before extraction, module updates and policy setup. If preparation consumed the 35-second budget, scripts were launched without waiting and could race property loading and mounting. Give common scripts their own budget and start the module budget after preparation, preserving module promotion and script/mount order.

This fixes a source-confirmed race that can explain a second-reboot symptom. Device boot logs have not verified that it is the only cause on the reporting device.

## Scoped integrations

- KeyMint ATTEST_KEY exclusivity and INCOMPATIBLE_PURPOSE; retain old keys until replacement succeeds; preserve explicit service errors and identity/rollback failures.
- Leave SOTER with the vendor service rather than reporting placeholder successful signatures.
- Root-ready TEE startup check, retaining official31431 external KernelSU recognition.
- Independent global image preferences/storage and native-page rendering; local/card customization remains independent.

No claim is made that every DuckDetector or Chunqiu result is cleared. AES timing, the unidentified TEE forgery9 result and device hardware capability require device validation.
