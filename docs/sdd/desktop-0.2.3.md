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
