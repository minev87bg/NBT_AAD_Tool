# NBT AAD Tool

**Android app to install, configure and troubleshoot [NBT-AAD](https://github.com/Sq00ky/Supra-iDrive6-Android-Auto)
(native Android Auto) on a BMW NBT Evo / iDrive 6 head unit, such as the Toyota Supra J29's, from your phone.**
No laptop, no USB stick: the app talks to the head unit over WiFi (SSH).

| | |
|---|---|
| **Install / update** | Put a release from Sq00ky's Releases page (or your own files) on the head unit, with the previous version kept and one button to go back. |
| **Change a setting** | Every documented `nbt-aad.env` setting as a list with choices and explanations, read from and saved to the head unit, with backups. |
| **Community** | Browse the project's issues and pull requests, and prepare the logs a bug report needs (VIN, WiFi password and GPS positions are hidden). |
| **Car connection** | Finds the head unit, joins the car's WiFi, and tells you what to do when the connection is lost. |
| **Updates itself** | Checks GitHub for a newer version at start-up. |

The app was built and tested with Android Auto installed by hand (`/opt/aa`, as in the project's guide). Android Auto
installed by [NBTApps](https://github.com/tom-pryor/nbtapps) is recognised too (settings, logs and the version check;
new, and not yet confirmed on a car). The head unit has two computers: NBTApps keeps its files on hu-omap, which only one
of the two addresses reaches directly. On the other address the app asks hu-omap itself (the way NBTApps' guide does,
`on -f /net/hu-omap/ /bin/sh`; read only, with one small file in RAM that is removed again) and then shows the version
from the daemon's log; the settings of such an install are left to NBTApps. Installing and going back are not done over an
NBTApps install: update it in NBTApps.

**Uninstall** takes an install made by this app (or by hand in `/opt/aa`) off the head unit again, so that another
installer finds it clean. It lists exactly what it would remove and changes nothing until you confirm: the daemon and its
leftovers, the settings file (unless you keep it) and the flag files go; the plugin folder is moved aside as
`/opt/aa/NbtAad.old`, not deleted. Restart the head unit afterwards. An install made by NBTApps is not touched (remove it in
NBTApps). Android Auto installed in some other layout is not managed, for safety: the app says where it found it, and
advises removing it with the tool that installed it. As a last resort Uninstall can remove it from there after you type
`I am sure`; it then removes only the daemon file of that folder and its known leftovers, and moves the plugin aside.

Status: **beta**. Written and tested by one person on one car; reports and logs are welcome in
[Issues](https://github.com/minev87bg/NBT_AAD_Tool/issues). This is an unofficial tool: it is not made by or
affiliated with Sq00ky, BMW, Toyota or Google. It changes software on your car's head unit; you use it at your own
risk. License: GPL-3.0.

## Get the APK
Every successful build is published as a release (Releases page, newest on top, `NBT-AAD-Tool-v1.0.N.apk`).
Download the APK on the phone, install it and allow "install unknown apps". The same file is also the artifact
`NbtAadEditor-debug-apk` of the "Build APK" run (Actions tab).

You can also open the folder in Android Studio (Ladybug or newer) and use Build > Build APK(s).

## Updates
At start-up the app asks GitHub for the latest release. When it is newer than the installed one, the first screen shows
"New version available", the release notes and an UPDATE button. UPDATE downloads the APK, checks its size, its
checksum and that it is signed with the same key as the installed app, and hands it to Android's installer, which asks
for one confirmation (Android does not allow an app to install itself silently). The app closes while it is replaced:
the banner says so, and after opening it again the first screen says "Updated to version X". The first time, Android also asks
to allow "install unknown apps" for this app.

How it fits together: the build number is the GitHub run number (version `1.0.N`, versionCode `N`), the workflow
signs every build with the fixed key (a repository secret, see `signing/README.md`) and publishes release `v1.0.N`, and the app compares `N` with its own
versionCode. The updater needs internet, like the settings sync; with none it stays quiet.

Builds made before the updater (and signed with a random key) cannot be updated in place: uninstall the old app
once, then install a release. From then on updates install over the existing app and keep its data.

## Use
1. Open the app with internet (mobile data, or a WiFi repeater with internet). It checks the project's
   README for new settings and says "Available settings are synchronized"; the list is kept on the phone, and a
   copy is built into the app. Internet is also needed for the app's own updates and for the releases list and
   downloads of the installer.
2. Phone hotspot on, WiFi dongle connected to it (or Ethernet with OTG), car on (ignition / key on).
3. DETECT CAR. If the dot is red, use "Reset head unit" (no SSH needed) and wait for green. Every reset button
   (here and in the result windows) first looks for the car again, as DETECT CAR does, so the reset is sent to the
   gateway as it is now: the phone's hotspot may give the car's parts new addresses between two sessions.
   A tiny **Skip** under the DETECT CAR button goes on to the rest of the app without a car, to look at the releases or
   to try things away from the car. What needs the head unit (reading, saving, installing, going back, CHECK AGAIN,
   TOOLS CHECK) looks for the car itself when none is known ("No car known yet. Looking for the car…"), so nothing sends
   you back to the first screen; if no car is found it says why (for example "Car found, but SSH is closed").
   **port probe** (bottom left, next to "manual IPs") is for finding out what the head unit offers on the car's own
   WiFi while Android Auto runs, when SSH is not there. Start Android Auto first, then QUICK (ports up to 10000) or FULL
   (every port): it only tries to open connections and closes them again, and writes what answered, what was refused and
   what was dropped without an answer to the app log (tag "Probe").
4. NEXT opens the page where you choose what to do, three tiles: **Install / update** (big green tile, NBT Android
   Auto), **Change a setting** (edit nbt-aad.env) and **Browse community for more** (big amber tile: the project's
   issues and pull requests, and **PREPARE LOGS** on each page to take part in a test). The (i) on each tile says what
   it is for.

If the SSH connection to the head unit cannot be made (an old address, the WiFi changed), Install, Go back, the status
check, TOOLS CHECK, reading the options and SAVE look for the car again and try once more at the address they find. A
failure after the connection was made is not repeated.

While it works with the car the app is tied to the car's WiFi, so that traffic does not leave over mobile data. When
that WiFi disappears (you stop the hotspot, the phone moves on) the tie is dead and Android refuses every new
connection with `ENONET` ("Machine is not on the network"), the internet included, although mobile data is fine. The
app therefore looks at the networks again before every internet request (and before every action with the car), and a
request that still dies of it lets go of the tie and goes out over the phone's own routing. If the log shows
`ENONET` in `W/Net` lines, that is this situation.

The first screen also has a small "Check for updates" button, under the update message at the top, so the result shows
where the eyes already are. The app and the settings list are checked at every start as well, but people often only
minimise an app, so the button does it on request. It only talks to the internet (GitHub): when there is none it says
so. Errors of internet requests are never worded as car or SSH problems.

**Theme** (top right of every screen): *Theme Auto* follows the phone, *Theme White* is the light theme, *Theme Dark* is
the dark theme in the colours of GitHub's dark theme (background `#0d1117`, not black, which is easier on the eyes).

### Browse community for more
The page has the amber of its tile as its accent (chips, buttons, links, progress), and a page that shares files has a thick amber outline, a faint amber tint and a solid FILES stamp, so it is found at a glance. The Install / update tile on the choice page is a soft sage green, easy on the eye.

Issues and pull requests of Sq00ky's project, open and closed, read from GitHub without signing in (60 requests an hour;
the page says how many are left). Chips switch between issues and pull requests, and between **Open**, **Closed** and
**★ Following** (the pages marked with the star, issues and pull requests together, open or closed, close together on
the list). **Sort** orders the list by recent activity or by the day a page was opened. **REFRESH** loads the list in
that order and then looks through the first posts and the newest 300 comments (six requests in all) for links to files;
the pages that share some get a FILES pill. The star (on the list and on a page) is kept on the phone.

**PREPARE LOGS** (a button that stays at the bottom of an open page) asks for **USE WIZARD** or **MANUAL**. The wizard:
which version (CURRENT, or DIFFERENT which opens the Install page), the settings (read from the head unit, CHANGE opens
the settings page; both pages show BACK TO WIZARD only when they were opened from the wizard), the test (restart the
head unit, join the car's WiFi, try the problem, leave Android Auto running), the way back to the dongle's WiFi (a tick
that says the logs are lost if Android Auto restarts, CHOOSE WIFI opens Android's own WiFi list over the app, a green
dot shows when SSH answers, and the wizard moves on by itself), and the logs (tick them, pack them: the WiFi password
and the VIN are hidden, the zip is saved in Downloads and **OPEN IN BROWSER** opens the page at its comment box, where
the zip is attached). MANUAL is the page where the files are ticked by hand.

A page shows its text and comments formatted like on GitHub: headings, **bold**, *italic*, `inline code`, code blocks
(coloured, scrolling sideways), tables (scrolling sideways), numbered, bulleted and task lists, quotes, rules and
links (they open in the browser); pictures are shown as a tappable "[picture]" link. Long comments are collapsed with
**Show more**. **OPEN ON GITHUB** opens the page in the browser.
The pictures of a post (markdown or HTML pictures and bare picture links) are not drawn inside its text but as a grid of
small squares under that post. A tap opens a full-screen gallery that holds only the pictures of that post, so you
always know which post they belong to: swipe left and right for the previous and next one, pinch or double-tap to zoom,
drag while zoomed; the top bar shows "2 / 5", the post (first post, or the comment and who wrote it) and OPEN IN BROWSER.
Pictures are loaded into memory only and nothing is saved on the phone (if a picture cannot be decoded, for example a
video, the tile says so and the browser button opens it).

**Files.** The card **Files shared on this page** is the quick list: a file's name and the buttons that fit it, nothing
more. **VIEW POST** scrolls the page to the post that shared the file (the first post, or that comment) and tints it for a
moment. The same buttons stand under the line that links the file in the post itself, with more room between several
files one after another: **VIEW** for a readable text, **GET FOR INSTALL** for a build file, **OPEN IN BROWSER** for a file
nothing else can be done with. A file is readable when its name says so (a long list: logs, notes, json, xml, yaml, ini,
conf, scripts and source code such as .sh .py .c .cpp .h .kt .java .js, patches and so on, also rotated logs such as
`app.log.1`) or, when the name says nothing (no extension, an unusual one), when the app has looked at the first 8 KB of
it ("looking into the file…", a small request, nothing is saved): no zero bytes, hardly any control characters, no web
page and no known archive or picture header. Archives, apps, libraries and pictures are never offered. **VIEW** opens a
small window in the middle of the screen (70% of the width and height at most) with small monospace text. Lines are
**not wrapped**, so a log keeps its formatting: the text scrolls down and sideways, each way with a scrollbar you can
drag (or touch to jump to a place in the file), and **COPY** at the top right copies the whole text to paste it into an
AI chat. UTF-8, UTF-16 and old 8-bit texts are read; the first 800 KB are shown, nothing is saved on the phone. **QUICK SUMMARY** gives a short digest made on the
phone from the words of the page (who wrote it, the first lines, the latest comment, the settings and files it names,
its links); it is not an AI summary. **SUMMARIZE WITH AI** has Claude (`claude-haiku-5-5`, Anthropic) write the summary
from the first post and the comments, with the links, files and settings of the page. It needs your own Anthropic API
key (**Set AI key**: a password field; the dialog explains where to get one and what it costs): the key is stored only
in the app's private storage, sent only to `api.anthropic.com` and never written to the app log. The text of the page,
which is public on GitHub, is sent to Anthropic. A 401 (wrong key), no credit, a limit or a busy service are explained in
plain words. **REMOVE KEY** deletes it. A shared zip or build file has
**GET FOR INSTALL**: the app downloads it, looks into it like any file from the phone and takes it to the installer
page, with the warning that it is not from the Releases page and the box to confirm that you trust it. Files that GitHub
shows only to signed-in people (Actions artifacts) are opened in the browser. Setting names such as `NBT_SOMETHING` that
a page mentions can be added with ADD to "Additional options" of the settings page.

### The app log
The app writes down what it does on the phone (detection steps, every SSH command with its answer, what the screens
said, every error with where it happened, crashes), about 800 KB at most, with VINs hidden (`WBA…3076`). "app log"
(bottom right of the first screen, and at the bottom of the choice page) shows its size and has **Save to file**
(the phone asks where; send that file along when something goes wrong) and **Clear**.

### Change a setting
Every documented setting is listed. READ OPTIONS marks the ones the head unit applies right now
(green, "Currently applied"). Change values with the dropdowns (Custom... for any other value), then SAVE.
A setting that the list does not have yet (from a newer or experimental build) is added with
"+ Add an option": a name and, if wanted, a value. It is kept on the phone under "Additional options" (first in the
list), whatever the settings sync brings, and "Remove from list" takes it out again.
Then restart the Android Auto Daemon from the head unit (Media/Radio > Android Auto > Force Stop).

### Install / update NBT Android Auto
Installs NBT-AAD files on the head unit from the phone, over the SSH login the app already has (the app does not
open SSH itself). It does by program what Sq00ky's `updating.md` does by hand with a USB stick:

- The source is a release from Sq00ky's Releases page (the app lists them, pre-releases marked), or any file or
  files on the phone: a zip, `nbt-aad`, `Releases.zip`, single plugin files. Zip files are opened, also a zip in a zip.
  The 7 names of a build are `nbt-aad`, `pf_aa` and the five plugin files; 80% of them (6 of 7, which is what a
  release has) make it "a build". A lone `nbt-aad` is a daemon test build. Every program is checked to be a
  32-bit ARM file, text files are checked to be text, and the version inside the daemon is read. Zip entries with odd
  paths are skipped and sizes are capped. Files from the phone get a clear warning and need an extra confirmation.
- INSTALL looks whether Android Auto still runs and stops it itself (`kill`; if that does not work it stops with a clear
  message asking for Force Stop), so there is no box to tick. The confirmation shows only what is replaced and what needs
  a decision (a file from the phone, an older backup that gets replaced); the rest is behind an (i). It makes the partition writable,
  uploads each file under a temporary name (`nbt-aad.new`, `NbtAad.new/…`) and checks it arrived intact (by its
  size, else a read-back). Only then the old file is renamed to `nbt-aad.old` (the plugin folder to
  `/opt/aa/NbtAad.old`, never left inside `Plugins`), and the new one takes its place. Nothing is overwritten while it
  is in use. `nbt-aad.env` is not touched. `pf_aa` is not installed: the firewall rules are built into `nbt-aad`.
- The result window is short: what was installed, a note that restarting the head unit is recommended, and a
  "Reset HeadUnit" button (the same reset as on the first screen: it looks for the car first, then needs no SSH). After
  a plugin was installed the note says to restart the head unit to load it (Sq00ky's update guide ends with a reboot for
  that); otherwise a restart is only recommended. "Show log" opens the full log in a
  small console. The log carries the time of each step, so a slow step can be seen.
- Speed: every SSH command costs the head unit a good part of a second, so an install is only a handful of them (look,
  prepare, one upload per file, one check, one replace). Uploads are checked by size (SSH protects the transfer itself);
  a file whose size the head unit cannot confirm is read back and compared.
- "Go back to the previous version" swaps the daemon and/or the plugin with the `.old` ones (you tick which);
  pressing it again goes forward. To return to an official version, install it from the releases list.
- The SSH login of the head unit has only a few commands (`cat`, `cp`, `mv`, `rm`, `mkdir` and the shell itself).
  The scripts use only those: the new daemon is made by copying a program that is already executable and writing the
  file into the copy, so no `chmod` is needed, and the swap is refused if the new file is not executable. If an
  `nbt-aad` cannot be run (an install that stopped halfway), the card says so in red and "Go back" repairs it.
  "TOOLS CHECK" shows which commands the login has and can be copied to be sent along with a bug report.

`nbt-aad.env` is optional and only overrides the daemon's defaults, so the app writes only the settings you
switch on. Limits of the loader on the head unit, checked before every upload: at most 15 settings, at most
159 characters per line.

## Where the settings list comes from
The README's "Configuration (`nbt-aad.env`)" section, parsed by `env/ConfigParser.kt` (the three tables, rows with
several settings, `KEY=a|b|c` forms, `NBT_KEY_<SLOT>`). `env/ConfigOverrides.kt` refines the settings whose
choices are only described in prose; a setting that is new in the README still appears with its README text.
The bundled copy is `app/src/main/assets/nbt-aad-config.md`; unit tests run against it in the build.

Files per car are kept on the phone in app-private storage under `files/cars/<VIN>/`. On the head unit the app keeps
`nbt-aad.env.orig` (the first version it saw) and `nbt-aad.env.bak`, `.bak2`, `.bak3` (the last three versions).
