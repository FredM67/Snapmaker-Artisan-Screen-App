param(
    [string]$NdkRoot = $env:ANDROID_NDK_HOME
)

$ErrorActionPreference = 'Stop'
$moduleDirectory = $PSScriptRoot
$repositoryDirectory = (Resolve-Path (Join-Path $moduleDirectory '..\..\..')).Path
$outputDirectory = Join-Path $repositoryDirectory 'apps\a400\libs\armeabi-v7a'
$outputFile = Join-Path $outputDirectory 'libobicopeer.so'
$manifestFile = Join-Path $outputDirectory 'libobicopeer.sha256'

if ([string]::IsNullOrWhiteSpace($NdkRoot)) {
    $sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }
    if ([string]::IsNullOrWhiteSpace($sdkRoot)) {
        throw 'Pass -NdkRoot or set ANDROID_NDK_HOME / ANDROID_HOME.'
    }
    $NdkRoot = Join-Path $sdkRoot 'ndk\22.1.7171670'
}
$compiler = Join-Path $NdkRoot 'toolchains\llvm\prebuilt\windows-x86_64\bin\armv7a-linux-androideabi26-clang.cmd'
if (-not (Test-Path -LiteralPath $compiler -PathType Leaf)) {
    throw "Android ARM compiler not found at $compiler"
}
if (-not (Get-Command go -ErrorAction SilentlyContinue)) {
    throw 'Go 1.24 or newer is required to rebuild the Obico WebRTC peer.'
}

Push-Location $moduleDirectory
try {
    & go test ./...
    if ($LASTEXITCODE -ne 0) { throw 'Obico WebRTC peer tests failed.' }

    $env:CGO_ENABLED = '1'
    $env:GOOS = 'android'
    $env:GOARCH = 'arm'
    $env:GOARM = '7'
    $env:CC = $compiler
    New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
    & go build -trimpath -ldflags='-s -w' -o $outputFile .
    if ($LASTEXITCODE -ne 0) { throw 'Obico WebRTC peer Android build failed.' }

    $sourceLines = @(Get-ChildItem -LiteralPath $moduleDirectory -Recurse -File |
        Where-Object { $_.Extension -eq '.go' -or $_.Name -eq 'go.mod' -or $_.Name -eq 'go.sum' } |
        ForEach-Object {
            $relative = $_.FullName.Substring($moduleDirectory.Length + 1).Replace('\', '/')
            "$relative $((Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant())"
        })
    [System.Array]::Sort($sourceLines, [System.StringComparer]::Ordinal)
    $sourceText = ($sourceLines -join "`n") + "`n"
    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    try {
        $sourceDigest = [System.BitConverter]::ToString(
            $sha256.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($sourceText))
        ).Replace('-', '').ToLowerInvariant()
    } finally {
        $sha256.Dispose()
    }
    $binaryDigest = (Get-FileHash -LiteralPath $outputFile -Algorithm SHA256).Hash.ToLowerInvariant()
    [System.IO.File]::WriteAllText(
        $manifestFile,
        "source_sha256=$sourceDigest`nbinary_sha256=$binaryDigest`n",
        [System.Text.UTF8Encoding]::new($false)
    )
    Write-Output "Built $outputFile"
    Write-Output "Source SHA-256: $sourceDigest"
    Write-Output "Binary SHA-256: $binaryDigest"
} finally {
    Pop-Location
}
