
# VRAM-Counter 1.8.0

## Use

Place folder in Starsector's /mods folder, then launch.
Windows: Open .bat file
Not-Windows: Use .sh file

![screenshot](screenshot.png)

## How Much VRAM Do I Have?

Win10: Please read the relevant section in <https://fractalsoftworks.com/forum/index.php?topic=19122.0>.

MacOS: The information is under Apple menu (top-left) -> About This Mac -> More Info -> Hardware -> Graphics/Displays.

Linux: The console command you need changes based on your distro, GPU type, GPU driver, GPU driver version, the year, moon phase, barometric pressure, and the current hormonal makeup of your kernel. Try StackOverflow, and good luck.

## Changelog

1.8.0

- Sort mods by their VRAM impact in descending order.
- Show total at bottom of each detailed breakdown.
- Slight text cleanup.
- Fixed the detailed breakdown not getting added to the file output.
- Added section to Readme about finding your GPU's VRAM.

1.7.0

- Mod totals are now included in the output text file.

1.6.0

- Prompt for GraphicsLib settings on each run.

1.5.0

- Make it clearer what it and isn't counted when user only copy/pastes a single line from the output.
- Add all enabled mods to the summary view.
- Copy the summary to the clipboard so it may be easily pasted into chat.

1.4.0

- Total estimated use no longer counts images with the same relative path and name multiple times.
  - So if Mods A and B both have /graphics/image.png, both will have the size counted in the per-mod display, but it will be only counted once in the total.

1.3.0

- Now prints out estimated usage of *enabled mods*, in addition to all found mods.
- For readability, only the currently chosen GraphicsLib settings (in 'config.properties') are shown.
- Fixed the # images count incorrectly counting all files.
- Now shows mod name, version, and id instead of mod folder name.

1.2.0

- Image channels are now accurately detected for all known cases, improving accuracy (now on par with original Python script).
- Files with '_CURRENTLY_UNUSED' in the name are ignored.
- Added configuration file for display printouts and script performance data.
- Converted to Kotlin, added .bat and .sh launchers.
- Greatly increased performance by multithreading file opening.
- Now shows with GraphicsLib (default) and with specific GraphicsLib maps disabled (configurable)

1.1.0

- Backgrounds are now only counted if larger than vanilla size and only by their increase over vanilla.

1.0.0

- Original release of Wisp's version.

Original script by Dark Revenant. Transcoded to Kotlin and edited to show more info by Wisp.
Source: [https://github.com/davidwhitman/VRAM_Calculator]
