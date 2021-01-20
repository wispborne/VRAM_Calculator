
@echo off
IF NOT EXIST ../../jre/bin/java.exe (
    ECHO Place VRAM-Counter folder in Starsector's mods folder.
    pause
) ELSE (
 "../../jre/bin/java.exe" -Djava.library.path=../../starsector-core/native/windows -jar ./VRAM-Counter.jar
 pause
)

