param([Parameter(Mandatory = $true)][string]$InstallDir)
$ErrorActionPreference = 'Stop'

function Get-SimplePlaneProcesses([string]$Directory) {
    $root = [IO.Path]::GetFullPath($Directory).TrimEnd('\')
    $paths = @('simple-plane-desktop.exe', 'SimplePlane.exe',
        'resources\jre\bin\java.exe', 'resources\jre\bin\javaw.exe') |
        ForEach-Object { [IO.Path]::Combine($root, $_) }
    @(Get-CimInstance Win32_Process -Filter "Name='simple-plane-desktop.exe' OR Name='SimplePlane.exe' OR Name='java.exe' OR Name='javaw.exe'" |
        Where-Object { $_.ExecutablePath -and $paths -contains $_.ExecutablePath })
}

try {
    # Give interactive upgrades the desktop's normal asynchronous cleanup.
    $processes = Get-SimplePlaneProcesses $InstallDir
    foreach ($item in $processes | Where-Object { $_.Name -notmatch '^javaw?\.exe$' }) {
        $process = Get-Process -Id $item.ProcessId -ErrorAction SilentlyContinue
        if ($process) {
            if (-not $process.CloseMainWindow()) { throw 'Cannot request desktop shutdown. Close SimplePlane and retry.' }
            if (-not $process.WaitForExit(15000)) { throw 'Desktop cleanup did not finish. Close SimplePlane and retry.' }
        }
    }
    # Old updaters exit without cleaning up Java. Re-query after desktop exit.
    foreach ($item in Get-SimplePlaneProcesses $InstallDir) {
        if ($item.Name -notmatch '^javaw?\.exe$') { throw 'SimplePlane is still running.' }
        $process = Get-Process -Id $item.ProcessId -ErrorAction SilentlyContinue
        if ($process -and -not $process.HasExited) {
            $current = Get-CimInstance Win32_Process -Filter "ProcessId=$($item.ProcessId)"
            if ($current.ExecutablePath -ne $item.ExecutablePath) { throw 'Runtime process changed; retry.' }
            Stop-Process -Id $item.ProcessId -Force
            if (-not $process.WaitForExit(10000)) { throw 'Java runtime did not exit.' }
        }
    }
    foreach ($relative in @('resources\jre\bin\java.dll', 'resources\jre\bin\server\jvm.dll')) {
        $file = Join-Path $InstallDir $relative
        if (Test-Path -LiteralPath $file) {
            $handle = [IO.File]::Open($file, [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
            $handle.Dispose()
        }
    }
    exit 0
} catch {
    Write-Output "Installation preparation failed: $($_.Exception.Message)"
    exit 1
}
