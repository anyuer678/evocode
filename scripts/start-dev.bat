@echo off
setlocal enabledelayedexpansion
title EvoCode Dev Launcher
cd /d "%~dp0.."

echo ================================================
echo   EvoCode one-click launcher
echo   backend :18080  analyzer :8081  frontend :5173
echo ================================================

rem ---------- 0. dependency check ----------
where docker >nul 2>&1
if errorlevel 1 goto :fail_docker_missing
if not exist backend\mvnw.cmd goto :fail_mvnw
if not exist analyzer\.venv\Scripts\python.exe goto :fail_venv
where npm >nul 2>&1
if errorlevel 1 echo [note] npm not found, frontend will be skipped

rem ---------- 1. infra ----------
echo.
echo [1/5] starting postgres/redis (docker compose) ...
docker compose -f docker-compose.yml up -d
if errorlevel 1 goto :fail_docker

set /a tries=0
:waitpg
set /a tries+=1
if %tries% gtr 30 goto :fail_pg
docker exec evocode-postgres pg_isready -U evocode -d evocode >nul 2>&1
if not errorlevel 1 goto :pgok
timeout /t 2 /nobreak >nul
goto :waitpg
:pgok
echo       postgres ready

rem ---------- 2. migration (idempotent) ----------
echo.
echo [2/5] db migration (idempotent) ...
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\init-db.ps1"
if errorlevel 1 goto :fail_migrate

rem ---------- 3. analyzer ----------
echo.
echo [3/5] starting analyzer :8081 (own window, close to stop) ...
start "EvoCode-analyzer" cmd /k "cd /d %~dp0..\analyzer & .venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8081"

rem ---------- 4. backend ----------
echo.
echo [4/5] packaging + starting backend :18080 (own window, close to stop) ...
pushd backend
call mvnw.cmd -q -DskipTests package
if errorlevel 1 popd & goto :fail_build
popd
if not exist backend\target\evocode-backend-0.1.0-SNAPSHOT.jar goto :fail_jar
start "EvoCode-backend" cmd /k "set BACKEND_PORT=18080 & cd /d %~dp0..\backend & java -jar target\evocode-backend-0.1.0-SNAPSHOT.jar"

rem ---------- 5. frontend ----------
where npm >nul 2>&1
if not errorlevel 1 (
    echo.
    echo [5/5] starting frontend :5173 (own window, close to stop) ...
    start "EvoCode-frontend" cmd /k "cd /d %~dp0..\frontend & npm run dev"
) else (
    echo.
    echo [5/5] npm not found, skipping frontend
)

rem ---------- 6. health check ----------
echo.
echo health check (backend up to 90s) ...
set /a tries=0
:waitbe
set /a tries+=1
if %tries% gtr 45 goto :fail_health
curl -s -o nul http://127.0.0.1:18080/api/v1/health 2>nul
if not errorlevel 1 goto :beok
timeout /t 2 /nobreak >nul
goto :waitbe
:beok
echo   [backend ] OK  http://127.0.0.1:18080/api/v1/health
curl -s -o nul http://127.0.0.1:8081/health 2>nul
if not errorlevel 1 (echo   [analyzer] OK  http://127.0.0.1:8081/health) else (echo   [analyzer] not ready, check the EvoCode-analyzer window)

echo.
echo ================================================
echo   Done. Open frontend at http://localhost:5173
echo   Each service runs in its own window; close it to stop.
echo   Stop all: double-click scripts\dev-down.bat
echo ================================================
pause
exit /b 0

:fail_docker_missing
echo [error] docker not found. Install and start Docker Desktop first.
goto :end
:fail_mvnw
echo [error] backend\mvnw.cmd missing.
goto :end
:fail_venv
echo [error] analyzer\.venv missing. Run: cd analyzer ^&^& python -m venv .venv ^&^& .venv\Scripts\pip install -r requirements.txt
goto :end
:fail_docker
echo [error] docker compose failed. Is Docker Desktop engine running?
goto :end
:fail_pg
echo [error] postgres not ready in 60s (container evocode-postgres).
goto :end
:fail_migrate
echo [error] db migration failed, see output above.
goto :end
:fail_build
echo [error] backend build failed (mvn package).
goto :end
:fail_jar
echo [error] backend\target\evocode-backend-0.1.0-SNAPSHOT.jar not found.
goto :end
:fail_health
echo [error] backend not ready in 90s, check the EvoCode-backend window.
goto :end

:end
echo.
pause
exit /b 1
