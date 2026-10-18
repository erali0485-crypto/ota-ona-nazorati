$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

# Must run inside the Windows interactive user session for LockWorkStation to work.
$Root = 'C:\ParentControl'
$IngestEndpoint = 'https://dhquvmipvfvvlisfhfaz.supabase.co/functions/v1/device-ingest'
$CommandsEndpoint = 'https://dhquvmipvfvvlisfhfaz.supabase.co/functions/v1/device-commands'
$TokenFile = Join-Path $Root 'device-token.local.txt'
$KeyFile = Join-Path $Root 'publishable-key.local.txt'
$LogFile = Join-Path $Root 'device-agent.log'

if (!(Test-Path $TokenFile) -or !(Test-Path $KeyFile)) {
  throw 'Missing device-token.local.txt or publishable-key.local.txt. Do not put secrets in the public repository.'
}
$DeviceToken = (Get-Content $TokenFile -Raw).Trim()
$PublishableKey = (Get-Content $KeyFile -Raw).Trim()
if ($DeviceToken.Length -lt 32 -or !$PublishableKey.StartsWith('sb_publishable_')) {
  throw 'Device token or publishable key format is invalid.'
}
if (-not ('OtaOnaSessionLock' -as [type])) {
  Add-Type -TypeDefinition @'
using System.Runtime.InteropServices;
public static class OtaOnaSessionLock {
  [DllImport("user32.dll", SetLastError=true)]
  public static extern bool LockWorkStation();
}
'@
}
$Headers = @{
  'Authorization' = "Bearer $DeviceToken"
  'apikey' = $PublishableKey
}
$NextHeartbeat = [DateTime]::MinValue
Add-Content -LiteralPath $LogFile -Value ("{0} START command-polling agent" -f (Get-Date -Format s))

while ($true) {
  try {
    if ((Get-Date) -ge $NextHeartbeat) {
      $health = @{
        kind = 'heartbeat'
        app_version = '1.1.0'
        device_name = $env:COMPUTERNAME
        os_version = [System.Environment]::OSVersion.VersionString
      } | ConvertTo-Json -Compress
      $hb = Invoke-RestMethod -Method Post -Uri $IngestEndpoint -Headers $Headers -ContentType 'application/json' -Body $health -TimeoutSec 15
      Add-Content -LiteralPath $LogFile -Value ("{0} HEARTBEAT OK device_id={1}" -f (Get-Date -Format s), $hb.device_id)
      $NextHeartbeat = (Get-Date).AddSeconds(60)
    }

    $pollBody = @{ kind = 'poll' } | ConvertTo-Json -Compress
    $result = Invoke-RestMethod -Method Post -Uri $CommandsEndpoint -Headers $Headers -ContentType 'application/json' -Body $pollBody -TimeoutSec 15
    if ($result.command -and $result.command.action -eq 'lock_workstation') {
      $commandId = [string]$result.command.id
      $locked = [OtaOnaSessionLock]::LockWorkStation()
      if ($locked) {
        $ackBody = @{
          kind = 'ack'
          command_id = $commandId
          result = 'acknowledged'
          summary = 'Windows LockWorkStation accepted the remote lock request.'
        } | ConvertTo-Json -Compress
        Add-Content -LiteralPath $LogFile -Value ("{0} LOCK REQUEST ACCEPTED command_id={1}" -f (Get-Date -Format s), $commandId)
      } else {
        $ackBody = @{
          kind = 'ack'
          command_id = $commandId
          result = 'failed'
          summary = 'Windows LockWorkStation returned false. Check that agent runs in the interactive user session.'
        } | ConvertTo-Json -Compress
        Add-Content -LiteralPath $LogFile -Value ("{0} LOCK FAILED command_id={1}" -f (Get-Date -Format s), $commandId)
      }
      $ack = Invoke-RestMethod -Method Post -Uri $CommandsEndpoint -Headers $Headers -ContentType 'application/json' -Body $ackBody -TimeoutSec 15
      Add-Content -LiteralPath $LogFile -Value ("{0} COMMAND RESULT status={1}" -f (Get-Date -Format s), $ack.command.status)
    } elseif ($result.command -and $result.command.action -eq 'show_notification') {
      $commandId = [string]$result.command.id
      $message = [string]$result.command.message
      try {
        $shell = New-Object -ComObject WScript.Shell
        [void]$shell.Popup($message, 15, 'Ота-онадан огоҳлантириш', 64)
        $ackBody = @{ kind='ack'; command_id=$commandId; result='acknowledged'; summary='Windows notification displayed to the signed-in user.' } | ConvertTo-Json -Compress
        Add-Content -LiteralPath $LogFile -Value ("{0} NOTIFICATION DISPLAYED command_id={1}" -f (Get-Date -Format s), $commandId)
      } catch {
        $ackBody = @{ kind='ack'; command_id=$commandId; result='failed'; summary='Could not display Windows notification: '+$_.Exception.Message } | ConvertTo-Json -Compress
      }
      $ack = Invoke-RestMethod -Method Post -Uri $CommandsEndpoint -Headers $Headers -ContentType 'application/json' -Body $ackBody -TimeoutSec 15
    }
  } catch {
    Add-Content -LiteralPath $LogFile -Value ("{0} ERROR {1}" -f (Get-Date -Format s), $_.Exception.Message)
  }
  Start-Sleep -Seconds 5
}
