# Starts Docker Desktop on Windows after clearing the Unix socket files a crash or a forced quit leaves behind.
# Docker Desktop listens on AF_UNIX sockets under %LOCALAPPDATA% (Docker\run\dockerInference,
# docker-secrets-engine\engine.sock). A stale one cannot be removed on some Windows builds ("The file cannot be accessed
# by the system"), the backend then crashes at start, and every crash leaves another one: the folders are moved aside
# instead (moving works where deleting does not). Run: powershell -File scripts/docker-desktop-start.ps1
$ErrorActionPreference = 'Stop'
Get-Process 'Docker Desktop', 'com.docker*' -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Seconds 3
$stamp = Get-Date -Format 'yyyyMMddHHmmss'
foreach ($dir in "$env:LOCALAPPDATA\Docker\run", "$env:LOCALAPPDATA\docker-secrets-engine") {
    if (Test-Path $dir) { Rename-Item $dir "$(Split-Path $dir -Leaf).stale-$stamp" }
}
# Old moved-aside folders: removed when Windows allows it, left otherwise.
Get-ChildItem $env:LOCALAPPDATA, "$env:LOCALAPPDATA\Docker" -Directory -Filter '*.stale-*' -ErrorAction SilentlyContinue |
    ForEach-Object { Remove-Item $_.FullName -Recurse -Force -ErrorAction SilentlyContinue }
Start-Process 'C:\Program Files\Docker\Docker\Docker Desktop.exe'
# docker writes to stderr while the engine starts: not an error here
$ErrorActionPreference = 'Continue'
for ($i = 0; $i -lt 90; $i++) {
    Start-Sleep -Seconds 5
    docker info *> $null
    if ($LASTEXITCODE -eq 0) { Write-Output 'docker-desktop-start: engine up'; exit 0 }
}
Write-Output 'docker-desktop-start: engine not up after 7.5 min; see %LOCALAPPDATA%\Docker\log\host\com.docker.backend.exe.log'
exit 1
