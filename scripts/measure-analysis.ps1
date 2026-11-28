param(
    [Parameter(Mandatory)][string]$ReferenceJar,
    [string]$CandidateJar = 'target/mixin-type-analysis-0.1.0-Snapshot.jar',
    [string]$OutputDirectory = 'target/performance-comparison',
    [int]$Runs = 5,
    [switch]$Profile
)

$ErrorActionPreference = 'Stop'
$workspace = Split-Path $PSScriptRoot -Parent
$output = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $output | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$versions = [ordered]@{ reference = [IO.Path]::GetFullPath($ReferenceJar); candidate = [IO.Path]::GetFullPath($CandidateJar) }
$testmod = Join-Path $workspace 'exp-fabric-testmod/target/exp-fabric-testmod-0.1.0-Snapshot.jar'
$bridge = Join-Path $workspace 'exp-fabric/target'
$classpath = @((Join-Path $bridge 'exp-fabric-0.1.0-Snapshot.jar')) + @(
    Get-ChildItem (Join-Path $bridge 'lib') -Filter '*.jar' | Sort-Object Name | ForEach-Object FullName
)
$logging = Join-Path $output 'logging.properties'
@'
handlers=java.util.logging.ConsoleHandler
.level=INFO
java.util.logging.ConsoleHandler.level=FINE
java.util.logging.ConsoleHandler.formatter=java.util.logging.SimpleFormatter
java.util.logging.SimpleFormatter.format=%5$s%n
love.crysh.mixinta.runtime.level=FINE
'@ | Set-Content -LiteralPath $logging -Encoding utf8NoBOM

$artifacts = foreach ($version in $versions.Keys) {
    $mod = Join-Path $output "$version.jar"
    Copy-Item -LiteralPath $testmod -Destination $mod -Force
    $zip = [IO.Compression.ZipFile]::Open($mod, [IO.Compression.ZipArchiveMode]::Update)
    try {
        $entry = @($zip.Entries | Where-Object FullName -Like 'META-INF/jars/mixin-type-analysis-*.jar')
        if ($entry.Count -ne 1) { throw 'Expected one embedded MixinTA JAR' }
        $name = $entry[0].FullName
        $entry[0].Delete()
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $versions[$version], $name) | Out-Null
    } finally { $zip.Dispose() }
    Get-FileHash -LiteralPath $versions[$version], $mod -Algorithm SHA256
}
$artifacts | Select-Object Path, Hash | ConvertTo-Json | Set-Content (Join-Path $output 'hashes.json')
& java -XshowSettings:vm -version *> (Join-Path $output 'jvm.log')

$samples = @()
foreach ($configuration in @('default', 'fine')) {
    for ($run = 1; $run -le $Runs; $run++) {
        foreach ($version in $versions.Keys) {
            $case = "$configuration-$run-$version"
            $directory = Join-Path $output $case
            $mods = Join-Path $directory 'mods'
            New-Item -ItemType Directory -Force -Path $mods | Out-Null
            Copy-Item (Join-Path $output "$version.jar") (Join-Path $mods 'testmod.jar') -Force
            $arguments = @("-Dexp.fabric.jar=$(Join-Path $workspace 'exp/target/exp-0.1.0-Snapshot.jar')", "-Dexp.fabric.runDir=$directory")
            if ($configuration -eq 'fine') { $arguments += "-Djava.util.logging.config.file=$logging" }
            $arguments += @('-cp', ($classpath -join [IO.Path]::PathSeparator), 'love.crysh.exp.fabric.Launcher')
            $log = Join-Path $output "$case.log"
            $timer = [Diagnostics.Stopwatch]::StartNew()
            & java @arguments *> $log
            $runExit = $LASTEXITCODE
            $timer.Stop()
            $lines = Get-Content -LiteralPath $log
            $metrics = ($lines | Where-Object { $_ -like 'MixinTA metrics classes=*' } | Select-Object -Last 1)
            $sample = [pscustomobject]@{
                Case = $case; WallMs = [math]::Round($timer.Elapsed.TotalMilliseconds)
                Exit = $runExit; Metrics = $metrics; Arguments = $arguments
            }
            $samples += $sample
            $samples | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $output 'measurements.json')
            $sample | Select-Object Case, WallMs, Exit, Metrics | ConvertTo-Json -Compress
            if ($runExit -ne 0 -or '12' -notin $lines -or '16' -notin $lines) { throw "Application failed in $case" }
            if ($configuration -eq 'fine' -and !$metrics) { throw "Missing metrics in $case" }
        }
    }
}

if ($Profile) {
    foreach ($version in $versions.Keys) {
        $directory = Join-Path $output "profile-$version"
        $mods = Join-Path $directory 'mods'
        New-Item -ItemType Directory -Force -Path $mods | Out-Null
        Copy-Item (Join-Path $output "$version.jar") (Join-Path $mods 'testmod.jar') -Force
        $arguments = @(
            '-XX:FlightRecorderOptions=stackdepth=128',
            "-XX:StartFlightRecording=filename=$(Join-Path $output "$version.jfr"),settings=profile,dumponexit=true",
            "-Djava.util.logging.config.file=$logging",
            "-Dexp.fabric.jar=$(Join-Path $workspace 'exp/target/exp-0.1.0-Snapshot.jar')",
            "-Dexp.fabric.runDir=$directory", '-cp', ($classpath -join [IO.Path]::PathSeparator),
            'love.crysh.exp.fabric.Launcher'
        )
        & java @arguments *> (Join-Path $output "profile-$version.log")
        if ($LASTEXITCODE -ne 0) { throw "Profile failed for $version" }
    }
}
