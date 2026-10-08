package model.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackupTest {

	@TempDir
	Path tempDir;

	private void write(String relative, String contents) throws Exception {
		Path file = tempDir.resolve(relative);
		Files.createDirectories(file.getParent());
		Files.writeString(file, contents, StandardCharsets.UTF_8);
	}

	@Test
	void backsUpRecordsAndTemplateButNotThePassword() throws Exception {
		write("packages/current/np8", "{}");
		write("packages/archive/cwh1", "{}");
		write("packages/current/.np8.123.tmp", "half a save");
		write("email-template.txt", "NOTIFICATION-BODY:");
		write("props/config.properties", "email.password=secret");

		File backup = Backup.backUp(tempDir.toString());

		assertNotNull(backup);
		List<String> entries = new ArrayList<String>();
		try (ZipFile zip = new ZipFile(backup)) {
			zip.stream().forEach(entry -> entries.add(entry.getName()));
		}
		Collections.sort(entries);
		assertEquals(List.of("email-template.txt", "packages/archive/cwh1", "packages/current/np8"), entries);
	}

	@Test
	void keepsOnlyTheNewestBackups() throws Exception {
		write("packages/current/np8", "{}");
		Path backups = tempDir.resolve("backups");
		Files.createDirectories(backups);
		for (int i = 0; i < 40; i++) {
			Files.writeString(backups.resolve(String.format("backup-20250101-%06d.zip", i)), "");
		}

		File newest = Backup.backUp(tempDir.toString());

		String[] left = backups.toFile().list((dir, name) -> name.endsWith(".zip"));
		assertEquals(Backup.BACKUPS_KEPT, left.length);
		assertTrue(newest.exists(), "the backup just made must be kept");
		assertFalse(backups.resolve("backup-20250101-000000.zip").toFile().exists(), "oldest should go");
	}
}
