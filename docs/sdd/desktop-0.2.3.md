# Desktop 0.2.3

## Requirements

- Set the desktop application and Windows installer version to 0.2.3.
- Preserve the Windows TUN stability fixes and automatic QPS filter removal already committed on this branch.
- Build bundled Java and TUN components from the same commit as the desktop application.
- Commit and push the version change; produce a new Windows installer.

## Design

Update `desktop/src-tauri/tauri.conf.json`, `Cargo.toml`, and the desktop package entry in `Cargo.lock` together. Use the existing Release Desktop App branch workflow to rebuild resources and generate signed updater artifacts and installers. A branch build uploads workflow artifacts without publishing a public release or changing the update endpoint.

## Verification plan

- Check that all three version declarations equal 0.2.3.
- Run the desktop Rust tests and JavaScript TUN status regression check.
- Confirm the workflow for the pushed commit produces a Windows 0.2.3 installer.

## Local verification (2026-10-31)

- Desktop Rust tests: 13 passed, 0 failed (compiled as simple-plane-desktop 0.2.3).
- `node desktop/tests/tun-status.cjs`: passed.
- `git diff --check`: passed.

## CI regression investigation

The preceding commit's `tests` run (41081303379) failed four cases of `Http3ManualSwitchIntegrationTest`: remote sessions were asserted to be zero immediately after local switch status changed. The current controller closes the old client rather than waiting for individual DISCONNECT acknowledgements, so remote cleanup is asynchronous. Verify local tunnel closure and bounded remote cleanup separately; retain all cipher variants and the separate-JVM case. A timeout must still fail the test.

Desktop changes must trigger the Java/TUN/dashboard test workflow. The desktop packaging workflow must run all desktop Rust tests, TUN tests, and the TUN status UI check on both supported operating systems, rather than only the node configuration subset.

The bounded cleanup assertion exposed a transport defect in run 41162352384: closing QUIC and its UDP parent concurrently can discard CONNECTION_CLOSE before delivery. Close QUIC first, then UDP, then the event-loop group; do not block an event-loop thread waiting for its own termination. Bind the close listener to its original UDP socket so it cannot close a later reconnect. Verify peer closure well before the 60-second idle timeout, including close initiated on the event loop.

Local targeted verification after the transport fix: 8 HTTP/3 loopback tests, 4 manual-switch variants, and 1 automatic-switch integration test passed (13 total, zero failures). The loopback matrix repeats closure five times from each calling context and requires peer closure within five seconds.
