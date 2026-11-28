param(
    [Parameter(Mandatory)][string]$ReferenceJar,
    [Parameter(Mandatory)][string]$Workloads,
    [string]$CandidateJar = 'target/mixin-type-analysis-0.1.0-Snapshot.jar',
    [string]$OutputDirectory = 'target/workload-performance',
    [int]$Runs = 5
)

$ErrorActionPreference = 'Stop'
$workspace = Split-Path $PSScriptRoot -Parent
$output = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $output | Out-Null
$cases = @(Get-Content -LiteralPath $Workloads -Raw | ConvertFrom-Json)
$report = Get-ChildItem (Join-Path $workspace 'target/surefire-reports/TEST-*.xml') | Select-Object -First 1
[xml]$xml = Get-Content -LiteralPath $report.FullName -Raw
$testClasspath = ($xml.testsuite.properties.property | Where-Object name -EQ 'java.class.path').value
$excluded = @([IO.Path]::GetFullPath((Join-Path $workspace 'target/classes')), [IO.Path]::GetFullPath((Join-Path $workspace 'target/test-classes')))
$dependencies = @($testClasspath -split [IO.Path]::PathSeparator | Where-Object { $_.TrimEnd('\', '/') -notin $excluded })
$versions = [ordered]@{ reference = [IO.Path]::GetFullPath($ReferenceJar); candidate = [IO.Path]::GetFullPath($CandidateJar) }
$versions.GetEnumerator() | ForEach-Object { Get-FileHash -LiteralPath $_.Value } |
    Select-Object Path, Hash | ConvertTo-Json | Set-Content (Join-Path $output 'hashes.json')
Copy-Item -LiteralPath $Workloads -Destination (Join-Path $output 'workloads.json') -Force
$samples = @()
foreach ($case in $cases) {
    for ($run = 1; $run -le $Runs; $run++) {
        $expected = $null
        foreach ($version in $versions.Keys) {
            $classpath = @($versions[$version], (Join-Path $workspace 'target/test-classes')) + $dependencies
            $log = Join-Path $output "$($case.name)-$run-$version.log"
            & java -Xms128m -Xmx1g -cp ($classpath -join [IO.Path]::PathSeparator) `
                love.crysh.mixinta.testsupport.AnalysisWorkloadProbe $case.kind ([IO.Path]::GetFullPath($case.fixture)) $case.warmup $case.iterations *> $log
            if ($LASTEXITCODE -ne 0) { throw "Workload failed. Read $log" }
            $result = Get-Content -LiteralPath $log | Where-Object { $_ -like '{*' } | Select-Object -Last 1 | ConvertFrom-Json
            if (!$result) { throw "Missing workload result in $log" }
            if ($expected -and $expected -ne $result.outcome) { throw "Workload outcomes differ for $($case.name)" }
            $expected = $result.outcome
            $sample = [pscustomobject]@{ Case = $case.name; Run = $run; Version = $version; Result = $result }
            $samples += $sample
            $samples | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $output 'measurements.json')
            $sample | ConvertTo-Json -Depth 5 -Compress
        }
    }
}
