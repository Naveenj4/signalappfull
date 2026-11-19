@echo off
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
set "PATH=C:\Program Files\Android\Android Studio\jbr\bin;%PATH%"

REM Force ALL caches and temp to D drive
set "GRADLE_USER_HOME=D:\signalappppp\signal-android\.gradle-home"
set "TMP=D:\signalappppp\signal-android\.tmp"
set "TEMP=D:\signalappppp\signal-android\.tmp"
set "TMPDIR=D:\signalappppp\signal-android\.tmp"
set "USERPROFILE_BACKUP=%USERPROFILE%"

REM Force JVM temp directory to D drive
set "GRADLE_OPTS=-Djava.io.tmpdir=D:\signalappppp\signal-android\.tmp -Xss512k -XX:MaxMetaspaceSize=1024m -Xms512m -Xmx4096m"
set "JAVA_OPTS=-Djava.io.tmpdir=D:\signalappppp\signal-android\.tmp"
set "_JAVA_OPTIONS=-Djava.io.tmpdir=D:\signalappppp\signal-android\.tmp"

REM Kotlin daemon workspace on D drive
set "KOTLIN_DAEMON_OPTS=-Djava.io.tmpdir=D:\signalappppp\signal-android\.tmp"

REM Create dirs
mkdir "D:\signalappppp\signal-android\.tmp" 2>nul
mkdir "D:\signalappppp\signal-android\.gradle-home" 2>nul

cd /d D:\signalappppp\signal-android
call gradlew.bat :Signal-Android:assemblePlayProdDebug --offline --no-daemon
