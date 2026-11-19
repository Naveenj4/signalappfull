@echo off
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
set "PATH=C:\Program Files\Android\Android Studio\jbr\bin;%PATH%"
set "GRADLE_USER_HOME=%~dp0.gradle-home"
set "TMP=%~dp0.tmp"
set "TEMP=%~dp0.tmp"
call .\gradlew.bat :Signal-Android:assembleFossProdDebug --offline
