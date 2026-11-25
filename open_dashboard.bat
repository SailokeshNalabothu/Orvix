@echo off
setlocal
echo =====================================================================
echo  ORVIX // AI Control Plane & FinOps Live Console
echo =====================================================================
echo.
echo Launching the Orvix Interactive Console in your default browser...
echo.

:: Open the unified Gateway Developer Platform dashboard
start http://localhost:8080/dashboard

echo Dashboard launched!
echo - Developer Console: http://localhost:8080/dashboard
echo - Jaeger UI:          http://localhost:16686/
echo - Prometheus:         http://localhost:9090/
echo.
echo Note: If services are not running, run 'run_services.bat' in another terminal.
echo =====================================================================
