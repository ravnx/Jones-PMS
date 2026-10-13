## What's new in 2.3.0

**Check out many old packages at once.**
Packages that were picked up but never scanned out keep sending reminder emails. They
can now be checked out together, in Admin -> Packages:

- **Select several packages** - Shift-click selects a range, Cmd-click adds one at a
  time - then right-click the selection and choose **Check Out N Packages**.
- **Check Out Old Packages...** - choose a day, and every package checked in before
  that day that is still waiting is checked out.

Both show how many packages will be checked out and ask before doing it. No emails are
sent, and reminders stop for these packages. Their check-out date is the day you do
this, and each one's comment says "Checked out in bulk by admin". The packages stay in
the history: untick "Active Packages Only" to see them.

**The program now updates itself** (new in 2.2.0).
Each time it starts, it checks whether a newer version is out. If there is one, it
shows what changed and offers **Update now**, **Later** or **Skip this version**.
Students, packages and settings are kept. Without an internet connection the program
just starts as normal.

## Upgrading

If you have 2.2.0, the program offers this update when it starts.

Otherwise, install this version by hand once. After that, new versions arrive on their
own.

1. Download `package-manager-2.3.0-jar-with-dependencies.jar` below.
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
