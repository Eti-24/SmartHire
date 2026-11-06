@echo off
setlocal
pushd "%~dp0" || exit /b 1
java -cp "smarthire.jar;lib/*" smarthire.Main %*
set "smartHireExit=%errorlevel%"
popd
exit /b %smartHireExit%
