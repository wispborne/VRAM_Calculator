
@echo off
IF NOT EXIST ../../jre/bin/java.exe (
    ECHO Place VRAM-Counter folder in Starsector's mods folder.
    pause
) ELSE (
 "../../jre/bin/java.exe" -jar ./VRAM-Counter.jar
 pause
)

