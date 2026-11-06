@echo off
setlocal
pushd "%~dp0" || exit /b 1
java -Dsmarthire.config=.local/aiven.properties -Dsmarthire.documents=.local/aiven-documents -Dsmarthire.requireCloud=true -cp "smarthire.jar;lib/*" smarthire.Main %*
set "smartHireExit=%errorlevel%"
popd
exit /b %smartHireExit%
