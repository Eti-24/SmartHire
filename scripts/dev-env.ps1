# Dot-source this file to use the project-local development tools.
$smartHireRoot = Split-Path $PSScriptRoot -Parent
$smartHireTools = Join-Path $smartHireRoot '.tools'
$smartHireJdk = Get-ChildItem -LiteralPath $smartHireTools -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue |
    Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin/javac.exe') } |
    Sort-Object Name -Descending | Select-Object -First 1
if (-not $smartHireJdk) { throw 'JDK 21 is not installed. Run scripts/install-tools.ps1 first.' }
$env:JAVA_HOME = $smartHireJdk.FullName
$env:MAVEN_HOME = Join-Path $smartHireTools 'apache-maven-3.10.0'
$env:MAVEN_USER_HOME = Join-Path $smartHireTools 'maven-user'
$env:MAVEN_OPTS = "-Xmx512m `"-Dmaven.repo.local=$smartHireTools/maven-repository`""
$env:Path = "$env:JAVA_HOME\bin;$env:MAVEN_HOME\bin;$smartHireTools\mysql-8.4.13-winx64\bin;$env:Path"
