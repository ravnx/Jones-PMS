package update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdaterTest {

	@TempDir
	Path tempDir;

	private static ReleaseInfo latest(String version) {
		return new ReleaseInfo(Version.parse(version), "", "x-jar-with-dependencies.jar", 1,
				URI.create("https://example.com/x.jar"), null);
	}

	@Test
	void offersANewerRelease() {
		assertTrue(Updater.shouldOffer(Version.parse("2.1.0"), latest("v2.2.0"), null));
	}

	@Test
	void doesNotOfferTheRunningVersion() {
		assertFalse(Updater.shouldOffer(Version.parse("2.2.0"), latest("v2.2.0"), null));
	}

	@Test
	void doesNotOfferAnOlderRelease() {
		assertFalse(Updater.shouldOffer(Version.parse("2.3.0"), latest("v2.2.0"), null));
	}

	@Test
	void doesNotOfferASkippedVersion() {
		assertFalse(Updater.shouldOffer(Version.parse("2.1.0"), latest("v2.2.0"), "2.2.0"));
	}

	@Test
	void offersAReleaseNewerThanTheSkippedOne() {
		assertTrue(Updater.shouldOffer(Version.parse("2.1.0"), latest("v2.3.0"), "2.2.0"));
	}

	@Test
	void ignoresAGarbledSkipSetting() {
		assertTrue(Updater.shouldOffer(Version.parse("2.1.0"), latest("v2.2.0"), "two point two"));
		assertTrue(Updater.shouldOffer(Version.parse("2.1.0"), latest("v2.2.0"), ""));
	}

	@Test
	void aDevelopmentBuildIsNotUpdated() throws Exception {
		assertNotNull(Updater.eligibilityProblem(null, jarIn(tempDir)));
	}

	@Test
	void anUnusualVersionIsNotUpdated() throws Exception {
		assertNotNull(Updater.eligibilityProblem("2.2.0-SNAPSHOT", jarIn(tempDir)));
	}

	@Test
	void classesOutsideAJarAreNotUpdated() {
		assertNotNull(Updater.eligibilityProblem("2.1.0", null));
	}

	@Test
	void aJarInAFolderThatCannotBeWrittenIsNotUpdated() throws Exception {
		Path folder = Files.createDirectory(tempDir.resolve("locked"));
		Path jar = jarIn(folder);
		Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("r-xr-xr-x"));
		try {
			assumeFalse(Files.isWritable(folder), "running as a user who can write anywhere");
			assertNotNull(Updater.eligibilityProblem("2.1.0", jar));
		} finally {
			Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwxr-xr-x"));
		}
	}

	@Test
	void aReleasedJarInAWritableFolderIsUpdated() throws Exception {
		assertNull(Updater.eligibilityProblem("2.1.0", jarIn(tempDir)));
	}

	@Test
	void findsAJarInAFolderWithSpaces() throws Exception {
		Path jar = jarIn(Files.createDirectories(tempDir.resolve("Package Management System/app")));

		Path found = Updater.jarFrom(jar.toUri().toURL());

		assertTrue(jar.toUri().toURL().toString().contains("%20"));
		assertEquals(jar, found);
	}

	@Test
	void aFolderOfClassesIsNotAJar() throws Exception {
		assertNull(Updater.jarFrom(tempDir.toUri().toURL()));
		assertNull(Updater.jarFrom(null));
	}

	private static Path jarIn(Path folder) throws Exception {
		return Files.write(folder.resolve("PackageManager.jar"), UpdateInstallerTest.jarBytes("2.1.0"));
	}
}
