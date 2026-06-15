@echo off
echo ============================================
echo   ОСОБНЯК — первый запуск
echo ============================================
echo.
echo Шаг 1: скачиваю gradle-wrapper.jar...
echo (нужен один раз, потом Gradle сам подтянет остальное)
echo.

if exist "gradle\wrapper\gradle-wrapper.jar" (
    echo gradle-wrapper.jar уже есть, пропускаю.
    goto build
)

powershell -NoProfile -Command ^
  "try { Invoke-WebRequest -Uri 'https://raw.githubusercontent.com/gradle/gradle/v8.5.0/gradle/wrapper/gradle-wrapper.jar' -OutFile 'gradle\wrapper\gradle-wrapper.jar'; Write-Host 'OK' } catch { Write-Host 'FAIL'; exit 1 }"

if %errorlevel% neq 0 (
    echo.
    echo Не получилось — попробуй второй способ:
    echo   1. Поставь Chocolatey: https://chocolatey.org/install
    echo   2. Запусти: choco install gradle
    echo   3. Запусти: gradle wrapper --gradle-version 8.5
    echo   4. Снова запусти: gradlew.bat android:assembleDebug
    pause
    exit /b 1
)

:build
echo.
echo ============================================
echo   Шаг 2: первая сборка APK
echo   (Gradle качает зависимости ~200MB, один раз)
echo ============================================
echo.
call gradlew.bat android:assembleDebug

if %errorlevel% neq 0 (
    echo.
    echo Сборка упала. Читай ошибку выше — там написано что не так.
    echo Чаще всего: нет Android SDK. Поставь Android Studio
    echo и он сам пропишет sdk.dir в local.properties.
    pause
    exit /b 1
)

echo.
echo ============================================
echo   APK готов:
echo   android\build\outputs\apk\debug\android-debug.apk
echo ============================================
echo.
echo Чтобы кинуть на телефон (включи "Отладка по USB"):
echo   gradlew.bat android:installDebug
echo.
pause
