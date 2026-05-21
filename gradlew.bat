@rem
@rem Copyright 2015 the original author or authors.
@rem
@rem Licensed under the Apache License, Version 2.0 (the "License");
@rem you may not use this file except in compliance with the License.
@rem You may obtain a copy of the License at
@rem
@rem      https://www.apache.org/licenses/LICENSE-2.0
@rem
@rem Unless required by applicable law or agreed to in writing, software
@rem distributed under the License is distributed on an "AS IS" BASIS,
@rem WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
@rem See the License for the specific language governing permissions and
@rem limitations under the License.
@rem
@rem SPDX-License-Identifier: Apache-2.0
@rem

@if "%DEBUG%"=="" @echo off
@rem ##########################################################################
@rem
@rem  Gradle startup script for Windows
@rem
@rem ##########################################################################

@rem Set local scope for the variables with windows NT shell
if "%OS%"=="Windows_NT" setlocal

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
@rem This is normally unused
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%

@rem Resolve any "." and ".." in APP_HOME to make it shorter.
for %%i in ("%APP_HOME%") do set APP_HOME=%%~fi

@rem Add default JVM options here. You can also use JAVA_OPTS and GRADLE_OPTS to pass JVM options to this script.
set DEFAULT_JVM_OPTS=-Dfile.encoding=UTF-8 "-Xmx64m" "-Xms64m"

@rem Find java.exe
if defined JAVA_HOME goto findJavaFromJavaHome

set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto execute

echo. 1>&2
echo ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH. 1>&2
echo. 1>&2
echo Please set the JAVA_HOME variable in your environment to match the 1>&2
echo location of your Java installation. 1>&2

goto fail

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%/bin/java.exe

if exist "%JAVA_EXE%" goto execute

echo. 1>&2
echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME% 1>&2
echo. 1>&2
echo Please set the JAVA_HOME variable in your environment to match the 1>&2
echo location of your Java installation. 1>&2

goto fail

:execute
@rem Setup the command line

set CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar

@rem -- wBooks AF_UNIX + OneDrive workaround --
@rem Two constraints stacked:
@rem  1) AF_UNIX connect() fails under %LOCALAPPDATA% (AppContainer/sandbox SIDs),
@rem     so the JDK's java.io.tmpdir cannot live there or NIO pipe init blows up.
@rem  2) OneDrive grabs files inside the project tree (including .gradle\tmp) and
@rem     holds them open long enough to break Gradle's file ops.
@rem Redirect to a local-disk scratch path that satisfies both: outside %LOCALAPPDATA%
@rem and outside any OneDrive-synced root. %USERNAME% keeps this portable across
@rem accounts on this machine.
set "WBOOKS_TMP=C:\GradleTmp\%USERNAME%\wbooks-build\gradle-tmp"
set "WBOOKS_GRADLE_USER_HOME=C:\GradleTmp\%USERNAME%\wbooks-build\gradle-user-home"
set "WBOOKS_PROJECT_CACHE=C:\GradleTmp\%USERNAME%\wbooks-build\project-cache"
if not exist "%WBOOKS_TMP%" mkdir "%WBOOKS_TMP%" >NUL 2>&1
if not exist "%WBOOKS_GRADLE_USER_HOME%" mkdir "%WBOOKS_GRADLE_USER_HOME%" >NUL 2>&1
if not exist "%WBOOKS_PROJECT_CACHE%" mkdir "%WBOOKS_PROJECT_CACHE%" >NUL 2>&1
set "TEMP=%WBOOKS_TMP%"
set "TMP=%WBOOKS_TMP%"
set "GRADLE_USER_HOME=%WBOOKS_GRADLE_USER_HOME%"
set "DEFAULT_JVM_OPTS=%DEFAULT_JVM_OPTS% "-Djava.io.tmpdir=%WBOOKS_TMP%""
@rem -- end workaround --

@rem Execute Gradle
"%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=%APP_BASE_NAME%" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain --project-cache-dir "%WBOOKS_PROJECT_CACHE%" %*

:end
@rem End local scope for the variables with windows NT shell
if %ERRORLEVEL% equ 0 goto mainEnd

:fail
rem Set variable GRADLE_EXIT_CONSOLE if you need the _script_ return code instead of
rem the _cmd.exe /c_ return code!
set EXIT_CODE=%ERRORLEVEL%
if %EXIT_CODE% equ 0 set EXIT_CODE=1
if not ""=="%GRADLE_EXIT_CONSOLE%" exit %EXIT_CODE%
exit /b %EXIT_CODE%

:mainEnd
if "%OS%"=="Windows_NT" endlocal

:omega
