param(
    [string] $RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
)

$ErrorActionPreference = 'Stop'
$markdownFiles = @((Join-Path $RepositoryRoot 'README.md')) + @(
    Get-ChildItem -LiteralPath (Join-Path $RepositoryRoot 'docs') -Recurse -File -Filter '*.md' |
        ForEach-Object { $_.FullName }
)
$failures = [System.Collections.Generic.List[string]]::new()
$linkPattern = '\]\((?<target>[^)\s]+)(?:\s+[^)]*)?\)'

foreach ($markdownFile in $markdownFiles) {
    $content = [System.IO.File]::ReadAllText($markdownFile)
    foreach ($match in [regex]::Matches($content, $linkPattern)) {
        $target = $match.Groups['target'].Value.Trim('<', '>')
        if ($target -match '^(?:[a-z][a-z0-9+.-]*:|//)' -or $target.StartsWith('#')) {
            continue
        }

        $pathPart = [uri]::UnescapeDataString(($target -split '#', 2)[0])
        if ([string]::IsNullOrWhiteSpace($pathPart)) {
            continue
        }
        $resolved = [System.IO.Path]::GetFullPath((Join-Path (Split-Path -Parent $markdownFile) $pathPart))
        if (-not (Test-Path -LiteralPath $resolved)) {
            $relativeMarkdown = [System.IO.Path]::GetRelativePath($RepositoryRoot, $markdownFile)
            $failures.Add("${relativeMarkdown}: missing link target '$pathPart'")
        }
    }
}

$xmlSettings = [System.Xml.XmlReaderSettings]::new()
$xmlSettings.DtdProcessing = [System.Xml.DtdProcessing]::Prohibit
$xmlSettings.XmlResolver = $null
foreach ($svgName in @('architecture-overview.svg', 'request-flow-and-data.svg', 'agent-integration.svg')) {
    $svgPath = Join-Path $RepositoryRoot "docs/images/$svgName"
    try {
        $reader = [System.Xml.XmlReader]::Create($svgPath, $xmlSettings)
        $document = [System.Xml.XmlDocument]::new()
        $document.XmlResolver = $null
        $document.Load($reader)
        $reader.Dispose()
    }
    catch {
        $failures.Add("docs/images/$svgName`: invalid XML: $($_.Exception.Message)")
    }
}

if ($failures.Count -gt 0) {
    $failures | ForEach-Object { Write-Error $_ }
    exit 1
}

Write-Output "Checked $($markdownFiles.Count) Markdown files and 3 SVG documents; no missing relative links or XML errors."
