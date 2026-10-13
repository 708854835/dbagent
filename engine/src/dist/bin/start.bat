@echo off
rem DbAgent ?????Windows??
rem   ???? JAVA_OPTS ??????? -Xms256m -Xmx1g??
rem   ???? DBAGENT_MASTER_KEY ??????? hmac: ??? Token?????
setlocal

set "SCRIPT_DIR=%~dp0"
set "BASE_DIR=%SCRIPT_DIR%.."

if "%JAVA_OPTS%"=="" set "JAVA_OPTS=-Xms256m -Xmx1g"

set "ARGS=serve --config "%BASE_DIR%\config" --data "%BASE_DIR%\data""
if not "%DBAGENT_MASTER_KEY%"=="" set "ARGS=%ARGS% --master-key "%DBAGENT_MASTER_KEY%""

java %JAVA_OPTS% -jar "%BASE_DIR%\lib\engine.jar" %ARGS%

endlocal
