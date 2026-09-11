<#
    Repairs the RemoveExistingProducts sequencing in a jpackage-built MSI.

    jpackage emits the action at sequence 798, which is inside the search phase
    - before CostFinalize and InstallValidate. WiX's own validator rejects that
    (ICE27: "in wrong place. Current: Search, Correct: Execution"), and the
    consequence is real: the previous version gets uninstalled outside the
    install transaction, so a failure part-way through the new install leaves
    the machine with neither version, and the removal is not rolled back.

    Windows Installer documents exactly three valid slots for the action. The
    one used here is between InstallValidate (1400) and InstallInitialize
    (1500), which is the placement WiX's own MajorUpgrade element defaults to.

    Rewriting one integer in the sequence table is far cheaper than carrying a
    custom WiX template through jpackage, and it survives JDK upgrades: if a
    future jpackage sequences the action correctly, this finds nothing to do
    and says so.
#>
param(
    # Directories to sweep, separated by ';' - the debug and release packaging
    # tasks write to different ones, and only some exist on any given build.
    # One delimited string rather than an array because powershell.exe -File
    # binds a single value per named parameter and treats the rest as
    # positional, which silently lands them on -Sequence.
    [Parameter(Mandatory = $true)] [string] $MsiDir,
    [int] $Sequence = 1401
)

$ErrorActionPreference = "Stop"

$found = @($MsiDir.Split(";") |
    Where-Object { $_ } |
    Where-Object { Test-Path $_ } |
    ForEach-Object { Get-ChildItem -Path $_ -Filter *.msi -File -ErrorAction SilentlyContinue })

if ($found.Count -eq 0) {
    Write-Host "fix-msi-upgrade: no .msi under $($MsiDir.Replace(';', ', ')), nothing to do"
    exit 0
}

$installer = New-Object -ComObject WindowsInstaller.Installer

foreach ($msi in $found) {
    # 1 = msiOpenDatabaseModeTransact: changes are held until Commit.
    $db = $installer.GetType().InvokeMember(
        "OpenDatabase", "InvokeMethod", $null, $installer, @($msi.FullName, 1))

    $read = $db.GetType().InvokeMember("OpenView", "InvokeMethod", $null, $db, @(
        "SELECT Sequence FROM InstallExecuteSequence WHERE Action='RemoveExistingProducts'"))
    $read.GetType().InvokeMember("Execute", "InvokeMethod", $null, $read, $null) | Out-Null
    $row = $read.GetType().InvokeMember("Fetch", "InvokeMethod", $null, $read, $null)

    if ($null -eq $row) {
        Write-Host "  $($msi.Name): no RemoveExistingProducts row - skipped"
        continue
    }

    $current = [int] $row.GetType().InvokeMember("IntegerData", "GetProperty", $null, $row, @(1))
    if ($current -eq $Sequence) {
        Write-Host "  $($msi.Name): RemoveExistingProducts already at $Sequence"
        continue
    }

    $write = $db.GetType().InvokeMember("OpenView", "InvokeMethod", $null, $db, @(
        "UPDATE InstallExecuteSequence SET Sequence=$Sequence WHERE Action='RemoveExistingProducts'"))
    $write.GetType().InvokeMember("Execute", "InvokeMethod", $null, $write, $null) | Out-Null
    $write.GetType().InvokeMember("Close", "InvokeMethod", $null, $write, $null) | Out-Null
    $db.GetType().InvokeMember("Commit", "InvokeMethod", $null, $db, $null) | Out-Null

    Write-Host "  $($msi.Name): RemoveExistingProducts moved $current -> $Sequence (after InstallValidate)"
}
