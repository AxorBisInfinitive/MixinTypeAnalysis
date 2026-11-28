param(
    [Parameter(Mandatory)][string]$ReferenceJar,
    [string]$CandidateJar = 'target/mixin-type-analysis-0.1.0-Snapshot.jar',
    [string]$OutputDirectory = 'target/container-performance',
    [int]$Runs = 5,
    [switch]$Heap
)

$ErrorActionPreference = 'Stop'
$workspace = Split-Path $PSScriptRoot -Parent
$output = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $output | Out-Null
$report = Get-ChildItem (Join-Path $workspace 'target/surefire-reports/TEST-*.xml') | Select-Object -First 1
[xml]$xml = Get-Content -LiteralPath $report.FullName -Raw
$testClasspath = ($xml.testsuite.properties.property | Where-Object name -EQ 'java.class.path').value
$excluded = @([IO.Path]::GetFullPath((Join-Path $workspace 'target/classes')), [IO.Path]::GetFullPath((Join-Path $workspace 'target/test-classes')))
$dependencies = @($testClasspath -split [IO.Path]::PathSeparator | Where-Object { $_.TrimEnd('\', '/') -notin $excluded })
$helper = Join-Path $workspace 'target/test-classes'
if ($Heap) {
    $manifest = Join-Path $output 'agent.mf'
    "Premain-Class: love.crysh.mixinta.testsupport.AnalysisCacheFootprint`n" | Set-Content $manifest -Encoding ascii
    $agent = Join-Path $output 'cache-agent.jar'
    & jar --create --file $agent --manifest $manifest -C $helper love/crysh/mixinta/testsupport/AnalysisCacheFootprint.class
    if ($LASTEXITCODE -ne 0) { throw 'Cannot create cache measurement agent' }
}
$fixtures = Join-Path $output 'fixtures'
$classpath = @([IO.Path]::GetFullPath($CandidateJar), $helper) + $dependencies
& java -cp ($classpath -join [IO.Path]::PathSeparator) love.crysh.mixinta.testsupport.ContainerPerformanceProbe generate $fixtures
if ($LASTEXITCODE -ne 0) { throw 'Cannot generate scaling fixtures' }

$versions = [ordered]@{ reference = [IO.Path]::GetFullPath($ReferenceJar); candidate = [IO.Path]::GetFullPath($CandidateJar) }
for ($run = 1; $run -le $Runs; $run++) {
    foreach ($version in $versions.Keys) {
        $directory = Join-Path $output "$run-$version"
        $classpath = @($versions[$version], $helper) + $dependencies
        $arguments = @('-Xms128m', '-Xmx1g')
        if ($Heap) { $arguments += @('-Dmixinta.performance.heap=true', "-javaagent:$agent", '--add-opens=java.base/java.util=ALL-UNNAMED') }
        $arguments += @('-cp', ($classpath -join [IO.Path]::PathSeparator), 'love.crysh.mixinta.testsupport.ContainerPerformanceProbe', 'run', $fixtures, $directory)
        & java @arguments *> (Join-Path $output "$run-$version.log")
        if ($LASTEXITCODE -ne 0) { throw "Scaling run failed for $run-$version" }
    }
    $reference = Join-Path $output "$run-reference"
    $candidate = Join-Path $output "$run-candidate"
    foreach ($file in Get-ChildItem $reference -Filter '*.facts') {
        if ((Get-FileHash $file.FullName).Hash -ne (Get-FileHash (Join-Path $candidate $file.Name)).Hash) {
            throw "Container facts differ in $run/$($file.Name)"
        }
    }
    Write-Output "Scaling comparison $run passed"
}
