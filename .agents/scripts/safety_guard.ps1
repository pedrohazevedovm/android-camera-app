$ErrorActionPreference = "Stop"

$inputJson = [Console]::In.ReadToEnd()
if ([string]::IsNullOrWhiteSpace($inputJson)) {
    Write-Output '{"decision": "allow"}'
    exit 0
}

$payload = $inputJson | ConvertFrom-Json
$commandLine = $payload.toolCall.args.CommandLine

$blockedPatterns = @(
    "adb\s+reboot\s+bootloader",
    "adb\s+reboot\s+fastboot",
    "fastboot\s+flashing",
    "fastboot\s+erase",
    "adb\s+shell\s+recovery"
)

foreach ($pattern in $blockedPatterns) {
    if ($commandLine -match $pattern) {
        $response = @{
            decision = "deny"
            reason   = "Blocked potentially destructive device command matching '$pattern'."
        } | ConvertTo-Json -Compress
        Write-Output $response
        exit 0
    }
}

Write-Output '{"decision": "allow"}'
