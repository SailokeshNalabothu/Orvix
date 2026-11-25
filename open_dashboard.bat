@echo off
setlocal
echo =====================================================================
echo  ORVIX // AI Control Plane & FinOps Live Console
echo =====================================================================
echo.
echo Launching the Orvix Interactive Console in your default browser...
echo.

:: Try opening the Gateway-hosted URL first; fallback to local file
start http://localhost:8080/
start "" "%~dp0dashboard\index.html"

echo Dashboard launched!
echo - Gateway UI:  http://localhost:8080/
echo - Local File:  file:///%~dp0dashboard\index.html
echo - Jaeger UI:   http://localhost:16686/
echo - Prometheus:  http://localhost:9090/
echo.
echo Note: If services are not running, run 'run_services.bat' in another terminal.
echo =====================================================================
