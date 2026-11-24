param(
    [string]$ExpJar = (Join-Path (Split-Path $PSScriptRoot -Parent) 'exp/target/exp-0.1.0-Snapshot.jar'),
    [string]$RunDirectory = (Join-Path $PSScriptRoot 'run'),
    [int]$Port = 0,
    [string[]]$AppArgs = @()
)

$launcherJar = Join-Path $PSScriptRoot 'target/exp-fabric-0.1.0-Snapshot.jar'
$testModJar = Join-Path (Split-Path $PSScriptRoot -Parent) 'exp-fabric-testmod/target/exp-fabric-testmod-0.1.0-Snapshot.jar'
$libraryDirectory = Join-Path $PSScriptRoot 'target/lib'

foreach ($requiredFile in @($ExpJar, $launcherJar, $testModJar)) {
    if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) {
        throw "Required artifact not found: $requiredFile"
    }
}

$RunDirectory = [System.IO.Path]::GetFullPath($RunDirectory)
$modsDirectory = Join-Path $RunDirectory 'mods'
New-Item -ItemType Directory -Force -Path $modsDirectory | Out-Null
Copy-Item -LiteralPath $testModJar -Destination $modsDirectory -Force

$libraries = Get-ChildItem -LiteralPath $libraryDirectory -Filter '*.jar' -File | Sort-Object Name
if (-not $libraries) {
    throw "Runtime dependencies are missing. Build exp-fabric first."
}

$classPathEntries = @($launcherJar) + @($libraries | ForEach-Object FullName)
$classPath = $classPathEntries -join [System.IO.Path]::PathSeparator
$javaArguments = @(
    "-Dexp.fabric.jar=$([System.IO.Path]::GetFullPath($ExpJar))",
    "-Dexp.fabric.runDir=$RunDirectory",
    '-cp',
    $classPath,
    'love.crysh.exp.fabric.Launcher'
)
if ($Port -gt 0) {
    $javaArguments = @("-Dexp.port=$Port") + $javaArguments
}
$javaArguments += $AppArgs

& java @javaArguments
exit $LASTEXITCODE
