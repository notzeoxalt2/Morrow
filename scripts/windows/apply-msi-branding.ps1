param(
    [Parameter(Mandatory=$true)][string]$MsiPath,
    [Parameter(Mandatory=$true)][string]$ResourceDirectory
)
$ErrorActionPreference = 'Stop'
$installer = New-Object -ComObject WindowsInstaller.Installer
$database = $installer.GetType().InvokeMember('OpenDatabase', 'InvokeMethod', $null, $installer, @((Resolve-Path -LiteralPath $MsiPath).Path, 1))
try {
    foreach ($asset in @(
        @{Name='WixUI_Bmp_Banner'; File='Morrow-banner.bmp'},
        @{Name='WixUI_Bmp_Dialog'; File='Morrow-background.bmp'}
    )) {
        $record = $installer.GetType().InvokeMember('CreateRecord', 'InvokeMethod', $null, $installer, @(2))
        $imagePath = (Resolve-Path -LiteralPath (Join-Path $ResourceDirectory $asset.File)).Path
        $record.GetType().InvokeMember('SetStream', 'InvokeMethod', $null, $record, @(1, $imagePath)) | Out-Null
        $record.GetType().InvokeMember('StringData', 'SetProperty', $null, $record, @(2, $asset.Name)) | Out-Null
        $view = $database.GetType().InvokeMember('OpenView', 'InvokeMethod', $null, $database, @('UPDATE `Binary` SET `Data` = ? WHERE `Name` = ?'))
        try { $view.GetType().InvokeMember('Execute', 'InvokeMethod', $null, $view, @($record)) | Out-Null }
        finally {
            $view.GetType().InvokeMember('Close', 'InvokeMethod', $null, $view, @()) | Out-Null
            [System.Runtime.InteropServices.Marshal]::FinalReleaseComObject($view) | Out-Null
            [System.Runtime.InteropServices.Marshal]::FinalReleaseComObject($record) | Out-Null
        }
    }
    $database.GetType().InvokeMember('Commit', 'InvokeMethod', $null, $database, @()) | Out-Null
} finally {
    [System.Runtime.InteropServices.Marshal]::FinalReleaseComObject($database) | Out-Null
    [System.Runtime.InteropServices.Marshal]::FinalReleaseComObject($installer) | Out-Null
}
Write-Output 'Morrow installer banner and dialog artwork applied.'
