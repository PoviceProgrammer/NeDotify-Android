param([string]$ProjectRoot = (Split-Path $PSScriptRoot -Parent))
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../tools/SourceSnapshot.ps1')
Test-ReviewSnapshot $ProjectRoot
