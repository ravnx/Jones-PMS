## What's new in 2.2.0

**The program now updates itself.**
Each time it starts, it checks whether a newer version is out. If there is one, it
shows what changed and offers three choices:

- **Update now** - downloads the new version, checks it, and restarts the program in
  it. Students, packages and settings are kept.
- **Later** - starts as normal and asks again next time.
- **Skip this version** - starts as normal and does not ask about this version again.
  A newer version is still offered.

Without an internet connection the program just starts as normal.

## Upgrading

This version is installed by hand once. After that, new versions arrive on their own.

1. Download `package-manager-2.2.0-jar-with-dependencies.jar` below.
2. In Finder, make the folder `Documents/package_management_system/app` in the mail
   room user's home folder. Move the jar into it and rename it `PackageManager.jar`.
3. Right-click `PackageManager.jar`, choose Make Alias, and move the alias to the
   Desktop. Delete the old jar and its old desktop shortcut.
4. Open the Package Management System from the desktop alias.

Your settings and student data carry over.

If you're upgrading from a version **before 2.0.0**, read the
[2.0.0 release notes](https://github.com/ravnx/Jones-PMS/releases/tag/v2.0.0) first.
2.0.0 changed the student file format, and older versions can't read it after the
upgrade. A backup is saved in `backups/` when the program starts.

## Going back

After an update, the version it replaced is kept next to the new one as
`PackageManager.jar.previous`. To go back, close the program, delete
`PackageManager.jar`, and rename `PackageManager.jar.previous` to `PackageManager.jar`.
