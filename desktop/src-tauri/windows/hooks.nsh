; Scope cleanup to this installation; never kill an entire parent tree.
!define SIMPLEPLANE_UPDATE_SCRIPT "${__FILEDIR__}\prepare-update.ps1"
!macro NSIS_HOOK_PREINSTALL
  InitPluginsDir
  File /oname=$PLUGINSDIR\simpleplane-prepare-update.ps1 "${SIMPLEPLANE_UPDATE_SCRIPT}"
  nsExec::ExecToStack '"$SYSDIR\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "$PLUGINSDIR\simpleplane-prepare-update.ps1" -InstallDir "$INSTDIR"'
  Pop $0
  Pop $1
  ${If} $0 != 0
    DetailPrint "$1"
    MessageBox MB_OK|MB_ICONSTOP "Cannot stop the SimplePlane runtime. No new files have been installed. Close SimplePlane and retry.$\r$\n$1" /SD IDOK
    Abort
  ${EndIf}
!macroend
