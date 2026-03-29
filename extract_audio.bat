@echo off
"C:\src\ffmpeg\bin\ffmpeg.exe" -i "C:\Users\lapto\Downloads\MPV\SALIM BAHANAN || SURAT AL KAHFI TERBARU.mp4" -vn -acodec copy "C:\Users\lapto\Downloads\MPV\SALIM BAHANAN - SURAT AL KAHFI TERBARU.aac"
echo Exit code: %ERRORLEVEL%
