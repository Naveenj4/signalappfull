@echo off
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
set "PATH=C:\Program Files\Android\Android Studio\jbr\bin;%PATH%"
set "GRADLE_USER_HOME=D:\signalappppp\signal-android\.gradle-home"
cd /d D:\signalappppp\signal-android
call gradlew.bat --stop
