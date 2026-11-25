@echo off
setlocal

set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.13.10002-hotspot"
set "PATH=%JAVA_HOME%\bin;%PATH%"

echo ==========================================================
echo    STARTING ORVIX MICROSERVICES CLUSTER
echo ==========================================================

echo Starting Guardrail Service on Port 8081...
start "Orvix - Guardrail Service (8081)" cmd /k "title Guardrail-8081 && java -jar guardrail-service\target\guardrail-service-1.0.0-SNAPSHOT.jar"

timeout /t 5 /nobreak >nul

echo Starting Semantic Cache Service on Port 8082...
start "Orvix - Semantic Cache Service (8082)" cmd /k "title Cache-8082 && java -jar cache-service\target\cache-service-1.0.0-SNAPSHOT.jar"

timeout /t 5 /nobreak >nul

echo Starting Billing Service on Port 8083...
start "Orvix - FinOps Billing Service (8083)" cmd /k "title Billing-8083 && java -jar billing-service\target\billing-service-1.0.0-SNAPSHOT.jar"

timeout /t 5 /nobreak >nul

echo Starting Gateway Service on Port 8080...
start "Orvix - Gateway Service (8080)" cmd /k "title Gateway-8080 && java -jar gateway-service\target\gateway-service-1.0.0-SNAPSHOT.jar"

echo.
echo ==========================================================
echo All 4 microservices have been launched in separate windows!
echo Once they show 'Started [ApplicationName] in ... seconds',
echo they are ready to accept traffic on http://localhost:8080
echo ==========================================================
pause
