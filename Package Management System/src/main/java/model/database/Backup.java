package model.database;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Keeps zipped copies of the student records and the email template in a "backups"
 * folder in the program directory, one per program start, keeping the newest few.
 *
 * config.properties is left out on purpose: it holds the email password.
 *
 * To restore, close the program and unzip a backup over the program directory.
 */
class Backup {

	static final int BACKUPS_KEPT = 30;

	private static final Logger logger = Logger.getLogger(Backup.class.getName());

	private static final String PREFIX = "backup-";
	private static final String SUFFIX = ".zip";

	private Backup() { }

	/**
	 * Backs up the program directory. Failure is logged and otherwise ignored - a
	 * backup problem must not stop the mail room from checking packages in.
	 *
	 * @return				The backup written, or null if none was
	 */
	static File backUp(String progDirPath) {
		File progDir = new File(progDirPath);
		File backupDir = new File(progDir, "backups");
		String stamp = PREFIX + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
		File target = new File(backupDir, stamp + SUFFIX);
		// two starts within one second must not overwrite the earlier backup
		for (int n = 1; target.exists(); n++) {
			target = new File(backupDir, stamp + "-" + n + SUFFIX);
		}

		try {
			backupDir.mkdirs();
			// written under a temporary name so an interrupted backup is never mistaken
			// for a complete one
			Path temp = Files.createTempFile(backupDir.toPath(), ".", ".tmp");
			try {
				try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(temp.toFile()))) {
					addTree(zip, progDir, new File(progDir, "packages"));
					addFile(zip, progDir, new File(progDir, "email-template.txt"));
				}
				Files.move(temp, target.toPath());
			} finally {
				Files.deleteIfExists(temp);
			}
			logger.info("Backed up student records to " + target.getPath());
		} catch (IOException e) {
			logger.warning("Could not back up student records: " + e.getMessage());
			return null;
		}

		prune(backupDir);
		return target;
	}

	/*
	 * Deletes all but the newest BACKUPS_KEPT backups. The names sort by date.
	 */
	private static void prune(File backupDir) {
		File[] backups = backupDir.listFiles(
				(dir, fileName) -> fileName.startsWith(PREFIX) && fileName.endsWith(SUFFIX));
		if (backups == null || backups.length <= BACKUPS_KEPT) {
			return;
		}
		Arrays.sort(backups);
		for (int i = 0; i < backups.length - BACKUPS_KEPT; i++) {
			if (!backups[i].delete()) {
				logger.warning("Could not delete old backup " + backups[i].getPath());
			}
		}
	}

	private static void addTree(ZipOutputStream zip, File root, File dir) throws IOException {
		File[] children = dir.listFiles();
		if (children == null) {
			return;
		}
		Arrays.sort(children);
		for (File child : children) {
			if (child.getName().startsWith(".")) {
				continue; // temporary files from a save in progress
			}
			if (child.isDirectory()) {
				addTree(zip, root, child);
			} else {
				addFile(zip, root, child);
			}
		}
	}

	private static void addFile(ZipOutputStream zip, File root, File file) throws IOException {
		if (!file.isFile()) {
			return;
		}
		String entryName = root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/');
		zip.putNextEntry(new ZipEntry(entryName));
		try (FileInputStream in = new FileInputStream(file)) {
			in.transferTo(zip);
		}
		zip.closeEntry();
	}
}
