Package-Management-System
=========================
Author: Navin Pathak
Currently Maintained by: Michael Palmer <michaelp@gmail.com>

Package management utility for the Jones College Mail Room.

Installation instructions
-------------------------

The mail room runs this on a Mac. These steps are done once; after that the program
updates itself (see [Updates](#updates)).

1. [Install Java 17 or newer](https://adoptium.net/) (the macOS `.pkg` installer).
2. Download `package-manager-<version>-jar-with-dependencies.jar` from the
   [newest release](https://github.com/ravnx/Jones-PMS/releases/latest).
3. In Finder, make the folder `Documents/package_management_system/app` in the mail room
   user's home folder. Move the jar into it and rename it `PackageManager.jar`. Keeping it
   there means updates never need an administrator password.
4. Right-click `PackageManager.jar`, choose Make Alias, and move the alias to the Desktop.
   Delete any older jar and its old desktop shortcut.
5. Open the Package Management System from the desktop alias.
6. Setup email with a [Gmail](mail.google.com) account.
    - Name - the name that will be sending all of the emails. Example - Jones College Mail Room, Mail Room, etc.
    - Email - your Gmail email address
    - Password - a Gmail [App Password](https://myaccount.google.com/apppasswords), not the
      normal account password. Google requires 2-Step Verification to be on to create one.
    - Mail server settings (advanced) - leave these alone unless told otherwise. They
      default to Gmail (`smtp.gmail.com`, port 587, STARTTLS). To send through a different
      server, open Admin -> Email and Printer -> Change Email Account and expand the section.
7. Select a printer from the dropdown menu. Make sure that the drivers are installed.
8. Import a CSV file containing student information (Admin -> Student Information -> Import).
   See [CSV file format](#csv-file-format) below.

CSV file format
---------------

The file must have a header row. Each following line is one student. Columns are found
by their header name, so **the order of the columns does not matter** and any columns the
program does not recognise are ignored.

| Column      | Required | Header is matched by                  |
| ----------- | -------- | ------------------------------------- |
| Last name   | yes      | `last`, `surname`, `family`           |
| First name  | yes      | `first`, `given`                      |
| NetID       | yes      | `netid`, `username`, or `id` as a word |
| Email       | no       | `mail`                                |

If the email column is missing or a cell is blank, the address is generated as
`netid@rice.edu`.

All of these headers work:

    Last Name,First Name,NetID,Email Address
    Last Name,First Name,Email Address,ID
    netid,first,last,email
    First Name,Last Name,NetID

A value containing a comma must be quoted, for example `"Smith, Jr."`. Blank lines are
skipped. Rows that cannot be used - no NetID, no name, or a NetID that appears twice -
are listed for you and skipped; the rest of the file still imports.

The import shows you what it will do and asks for confirmation before it changes
anything. If the file cannot be read at all, no students are changed.

CSV files can be generated from [Microsoft Excel](http://office.microsoft.com/en-us/excel-help/import-or-export-text-txt-or-csv-files-HP010099725.aspx) 
or any [other spreadsheet program] (http://www.computerhope.com/issues/ch001356.htm).

Building from source
--------------------

Requires a JDK (17 or newer) and Maven. From the `Package Management System` directory:

    mvn clean package

This produces `target/package-manager-<version>-jar-with-dependencies.jar`, which is the
runnable jar. The version is set in `pom.xml` and shown in the window title. `mvn test` runs the test suite on its own.

Data and backups
----------------

Everything the program stores is in `Documents/package_management_system` in the user's
home folder, not next to the jar, so replacing the jar keeps all data:

- `packages/current` and `packages/archive` - one file per student, with their packages
- `packages/damaged` - any student file that could not be read, set aside to recover by hand
- `props/config.properties` - email account, printer and reminder settings
- `email-template.txt` - the email wording
- `backups` - a zip of the student records and email template, made each time the program
  starts. The newest 30 are kept. The email password is not included.

To restore a backup, close the program and unzip the backup into
`Documents/package_management_system`, replacing the files there.

Version 2.0 saves student files in a new format, converting each one the first time it is
saved. Older versions cannot read the new format, so to go back to an older version,
restore a backup made before upgrading.

Updates
-------

Each time it starts, the program asks GitHub whether a newer release is out. If there
is one, it shows what changed and offers three choices:

- **Update now** - downloads the new version, checks it, and restarts the program in it.
  Students, packages and settings are kept.
- **Later** - starts as normal and asks again next time.
- **Skip this version** - starts as normal and does not ask about this version again.
  A newer version is still offered.

Without an internet connection the program just starts as normal.

The version that was replaced is kept next to the new one as
`PackageManager.jar.previous`. To go back to it, close the program, delete
`PackageManager.jar`, and rename `PackageManager.jar.previous` to `PackageManager.jar`.
If the newer version changed the file format, also restore a backup made before the
update (see [Data and backups](#data-and-backups)). The old version will offer the
update again; choose **Skip this version**.

Releasing
---------

1. Set the new version in `pom.xml`, commit, and push `main`.
2. Write the release notes in a file. The mail room sees them in the update prompt.
3. From the repository root, run `./release.sh notes.md`.

The script refuses to publish from anything but a clean `main` that matches GitHub, or a
version that already has a tag. It builds the jar, checks the version inside it, and asks
before publishing. `--dry-run` runs every check and the build without publishing.
`--prerelease` publishes a release that the mail room is not offered.

Usage instructions
------------------

### Start up
1. Open the Package Management System.
2. Wait for the pick up screen to appear. This may take a couple of minutes every day, 
   as the system sends out reminder emails upon startup.

### Check In
For each package you want to check in,

1. Select the tab labelled "Check In" on the left hand menu.
2. Begin typing the student's first or last name in the student box  
**Make sure that you have already imported all students (see step 4 of installation).**
3. Select the student from the dropdown menu that appears.
4. (Optional) Enter any comments you have for the package in the comment menu.
5. Push the submit button.
6. Attach the printed barcode to a package.


### Check Out
For each package you want to check out,

1. Select the tab labelled "Pick Up" on the left hand menu.
2. Scan a barcode or type in the barcode into the text box and press enter.
3. Confirm that you are the person indicated in the popup.

Admin Operations
----------------

### Package Operations
#### Check Out Package
If someone has accidentally picked up a package and forgotten to check it out, they will continue
to recieve notifications about the package. To check out this package,

1. Go to the Packages tab (Admin -> Packages)
2. (Optional) Search with the search bar or sort by clicking on any of the column headers. 
   Separate the search terms with a space.3. Right click on the package to be checked out.
4. Click on "Check Out Package" in the popup menu.
5. Confirm that you want to check out the package.

#### Check Out Many Packages
If many packages were picked up but never scanned out, their students keep getting reminder
emails. To check them out together,

1. Go to the Packages tab (Admin -> Packages)
2. Either:
    - select the packages - Shift-click selects a range, Cmd-click adds one at a time -
      then right click on the selection and click on "Check Out N Packages", or
    - click "Check Out Old Packages..." and choose a day. Every package checked in before
      that day and still waiting is checked out.
3. Read the count and confirm.

No emails are sent, and reminders stop for these packages. Their check-out date is the day
you do this, and each one's comment says "Checked out in bulk by admin". Packages already
checked out are left as they are. This cannot be undone.

#### Reprint Label
If the printer runs out of ink or there's any printer issue, you may want to reprint the label. To do so,

1. Go to the Packages tab (Admin -> Packages)
2. (Optional) Search with the search bar or sort by clicking on any of the column headers. 
   Separate the search terms with a space.3. Right click on the package for which the label will be printed.
4. Click on "Reprint Label" in the popup menu.
5. Confirm that you want to reprint the label.

#### Resend Package Notification
If the internet was disconnected when a package was checked out, a notification will not be sent.
To resend a notification,

1. Go to the Packages tab (Admin -> Packages)
2. (Optional) Search with the search bar or sort by clicking on any of the column headers. 
   Separate the search terms with a space.
3. Right click on the package for which the notification will be resent.
4. Click on "Resend Notification" in the popup menu.
5. Confirm that you want to send a notification.

### Student Operations

#### Import New Students
At the start of the new semester, or upon opening the program, you will want to import
all new students instead of adding them individually. To do so,

1. Go to the Student Information tab (Admin -> Student Information)
2. Click on the Import button at the lower left hand corner.
3. Navigate to the CSV file that you have prepared. See [CSV file format](#csv-file-format).
4. Read the summary that pops up. It tells you how many students will be added, updated
   and archived, and lists any rows it could not read. Nothing changes until you confirm.

Note: This will archive all students not in the CSV file. Archived students keep their
package history - to bring one back, use the Add button on the same tab and their packages
return with them. Students already on the roster keep their packages and have their name
and email updated from the file.

#### Add New Student
If a student was not in the CSV file, you can add them:

1. Go to the Student Information tab (Admin -> Student Information)
2. Click on the Add button at the lower right hand corner.
3. Enter all of the student's information.
4. Press submit.


#### Edit Student
If a student wants to change their name or email,

1. Go to the Student Information tab (Admin -> Student Information)
2. (Optional) Search with the search bar or sort by clicking on any of the column headers. 
   Separate the search terms with a space.
3. Right click on the student to be edited.
4. Select "Edit Student" from the popup menu.
5. Enter all of the student's information. Note that the netID cannot be changed.
6. Press submit.

#### Delete Student
If a student was added by mistake, they can be easily removed,

1. Go to the Student Information tab (Admin -> Student Information)
2. (Optional) Search with the search bar or sort by clicking on any of the column headers. 
   Separate the search terms with a space.
3. Right click on the student to be deleted.
4. Select "Delete Student" from the popup menu.
5. Confirm that you want to delete the student.

### About
Admin -> About shows which version is running and who to contact with questions or
problems. Clicking the email address starts an email that already names the version.

Acknowledgements
================
First and foremost, I'd like to thank Christopher Henderson for all of the advice that he gave throughout the project.
Without him, this project would not have been possible.

Second, I would like to thank the members of Jones 3rd South who contributed to the testing of this project:
Ambi Bobmanuel  
Christopher Henderson  
Keiko Kaplan  
Avinash Shivakumar  s
Lucas Shumaker  
Mitch Torczon  
Blane Townsend

Last, but not least, I would like to thank Michelle Bennack for being the most awesome coordinator Jones has ever seen!
