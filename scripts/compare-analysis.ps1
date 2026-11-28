param(
    [Parameter(Mandatory)][string]$ReferenceJar,
    [string]$CandidateJar = 'target/mixin-type-analysis-0.1.0-Snapshot.jar',
    [string]$Fixtures = 'target/performance-fixtures',
    [string]$OutputDirectory = 'target/analysis-comparison'
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
$results = @{}
foreach ($version in @('reference', 'candidate')) {
    $jar = if ($version -eq 'reference') { [IO.Path]::GetFullPath($ReferenceJar) } else { [IO.Path]::GetFullPath($CandidateJar) }
    $directory = Join-Path $output $version
    $classpath = @($jar, (Join-Path $workspace 'target/test-classes')) + $dependencies
    & java -cp ($classpath -join [IO.Path]::PathSeparator) love.crysh.mixinta.testsupport.AnalysisComparison ([IO.Path]::GetFullPath($Fixtures)) $directory *> (Join-Path $output "$version.log")
    if ($LASTEXITCODE -ne 0) { throw "Comparison process failed for $version. Read $output/$version.log" }
    $hashes = @{}
    Get-ChildItem $directory -Filter '*.txt' | ForEach-Object { $hashes[$_.Name] = (Get-FileHash $_.FullName).Hash }
    $results[$version] = $hashes
}
$differences = @(@($results.reference.Keys) + @($results.candidate.Keys) | Sort-Object -Unique | Where-Object { $results.reference[$_] -ne $results.candidate[$_] })
$summary = [pscustomobject]@{ Fixtures = $results.reference.Count; Differences = $differences; Reference = (Get-FileHash $ReferenceJar).Hash; Candidate = (Get-FileHash $CandidateJar).Hash }
$summary | ConvertTo-Json -Depth 3 | Set-Content (Join-Path $output 'summary.json')
$summary | ConvertTo-Json -Depth 3
if ($differences.Count -ne 0) { throw 'Analysis outputs differ' }
