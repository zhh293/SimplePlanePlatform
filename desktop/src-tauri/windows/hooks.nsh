; Close the running desktop app before NSIS replaces bundled JRE files.
; /T also terminates proxy-local/tun-adapter children started by the app.
!macro NSIS_HOOK_PREINSTALL
  nsExec::ExecToLog 'taskkill /F /T /IM simple-plane-desktop.exe'
  nsExec::ExecToLog 'taskkill /F /T /IM SimplePlane.exe'
  Sleep 1500
!macroend
