# Desktop updater 0.2.4

The updater previously read snake_case progress fields even though Rust sent
camelCase. Its action container's flex styling also overrode the HTML hidden
attribute, and neither the handler nor the backend excluded duplicate installs.
Windows' updater exits directly, skipping the window-close service cleanup;
an orphaned bundled JVM can therefore keep java.dll/jvm.dll mapped during upgrade.

## Changes

- Show bytes, percentage, speed, estimated remaining time and explicit network,
  signature verification, service cleanup and installer handoff stages. Unknown
  lengths use an indeterminate bar; network silence shows a waiting message.
- Disable duplicate clicks and checks while installing; a nonblocking backend
  mutex rejects duplicate IPC requests, while an OS file lock excludes other
  instances for this user. Errors release ownership for an explicit retry.
- Download and verify before stopping networking. Await owned Java/TUN exit and
  proxy restoration before handing the verified bytes to the installer. Refuse
  service reconnection during installation. Close requests cannot interrupt it.
- Replace installer-wide taskkill /T with scoped preparation: ask the installed
  desktop to close normally, stop remaining Java only from the selected install
  directory, and check exclusive access to java.dll/jvm.dll before copying files.
  Preparation failure aborts with a reason. Unrelated JVMs remain untouched.
- Restart from Rust; the frontend no longer calls an unregistered process plugin.

## Verification

- `node desktop/tests/updater-ui.cjs`: real event handler with mocked IPC;
  camelCase progress, unknown lengths, stalled downloads, duplicate clicks,
  installation stages, error retry and locked successful handoff.
- `node desktop/tests/updater-installer.cjs`: real isolated Windows processes;
  owned Java termination, unrelated Java survival, locked DLL refusal and retry.
- Desktop Rust suite includes nonblocking ownership and independent file-lock
  regression cases; packaging CI runs both updater scripts on supported systems.

## Delivery constraint

Version 0.2.3 is already published and immutable. These changes require a newly
built and signed 0.2.4 installer. A 0.2.3 client keeps its existing progress UI
until upgraded; the new installer's scoped Java cleanup also covers that client.
An end-to-end upgrade on a real installed desktop is still required after
packaging; fixture checks do not certify installer UI or UAC behavior.
