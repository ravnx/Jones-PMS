package update;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

class UpdateInstallerTest {

	private static final UpdateInstaller.Progress NO_PROGRESS = new UpdateInstaller.Progress() {
		public void downloaded(long bytes, long total) { }
		public boolean isCancelled() { return false; }
	};

	@TempDir
	Path tempDir;

	private HttpServer server;
	private Path jar;
	private byte[] runningBytes;
	private UpdateInstaller installer;

	@BeforeEach
	void setUp() throws Exception {
		// A folder with spaces, like the real "Package Management System" checkout
		jar = tempDir.resolve("My Apps/PackageManager.jar");
		Files.createDirectories(jar.getParent());
		runningBytes = jarBytes("2.0.0");
		Files.write(jar, runningBytes);
		installer = new UpdateInstaller(jar, Duration.ofSeconds(5));

		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.setExecutor(Executors.newCachedThreadPool());
		server.start();
	}

	@AfterEach
	void stopServer() {
		server.stop(0);
	}

	/** A small jar whose manifest gives the version, or none when version is null */
	static byte[] jarBytes(String version) throws IOException {
		Manifest manifest = new Manifest();
		manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
		manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "controller.Controller");
		if (version != null) {
			manifest.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, version);
		}
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (JarOutputStream out = new JarOutputStream(bytes, manifest)) {
			out.putNextEntry(new JarEntry("controller/Controller.class"));
			out.write(new byte[] { (byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE });
			out.closeEntry();
		}
		return bytes.toByteArray();
	}

	static String sha256(byte[] bytes) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
	}

	private URI url(String path) {
		return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
	}

	/** Serves the bytes at the path and returns their URL */
	private URI serve(String path, byte[] body) {
		server.createContext(path, exchange -> {
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		return url(path);
	}

	/** A release of the version whose download is the bytes, served at /download/new.jar */
	private ReleaseInfo released(String version, byte[] body) throws Exception {
		return new ReleaseInfo(Version.parse(version), "notes",
				"package-manager-" + version + "-jar-with-dependencies.jar",
				body.length, serve("/download/new.jar", body), sha256(body));
	}

	private void assertRejected(ReleaseInfo release) throws Exception {
		assertThrows(IOException.class, () -> installer.install(release, NO_PROGRESS));
		assertUntouched();
	}

	private void assertUntouched() throws Exception {
		assertArrayEquals(runningBytes, Files.readAllBytes(jar));
		assertFalse(Files.exists(installer.newJar()), "partial download left behind");
		assertFalse(Files.exists(installer.previousJar()), "previous jar written for a failed update");
	}

	@Test
	void namesTheDownloadAndBackupAfterTheJar() {
		assertEquals(tempDir.resolve("My Apps/PackageManager.jar.new"), installer.newJar());
		assertEquals(tempDir.resolve("My Apps/PackageManager.jar.previous"), installer.previousJar());
	}

	@Test
	void installsAVerifiedRelease() throws Exception {
		byte[] update = jarBytes("2.1.0");
		ReleaseInfo release = released("2.1.0", update);
		List<Long> reported = new ArrayList<Long>();
		List<Long> totals = new ArrayList<Long>();

		installer.install(release, new UpdateInstaller.Progress() {
			public void downloaded(long bytes, long total) {
				reported.add(bytes);
				totals.add(total);
			}
			public boolean isCancelled() { return false; }
		});

		assertArrayEquals(update, Files.readAllBytes(jar));
		assertArrayEquals(runningBytes, Files.readAllBytes(installer.previousJar()));
		assertFalse(Files.exists(installer.newJar()));
		assertEquals((long) update.length, reported.get(reported.size() - 1).longValue());
		assertEquals((long) update.length, totals.get(0).longValue());
	}

	@Test
	void followsRedirects() throws Exception {
		byte[] update = jarBytes("2.1.0");
		serve("/storage/new.jar", update);
		server.createContext("/download/redirect.jar", exchange -> {
			exchange.getResponseHeaders().add("Location", url("/storage/new.jar").toString());
			exchange.sendResponseHeaders(302, -1);
			exchange.close();
		});
		ReleaseInfo release = new ReleaseInfo(Version.parse("2.1.0"), "", "x-jar-with-dependencies.jar",
				update.length, url("/download/redirect.jar"), sha256(update));

		installer.install(release, NO_PROGRESS);

		assertArrayEquals(update, Files.readAllBytes(jar));
	}

	@Test
	void installsWithoutAChecksum() throws Exception {
		byte[] update = jarBytes("2.1.0");
		ReleaseInfo release = new ReleaseInfo(Version.parse("2.1.0"), "", "x-jar-with-dependencies.jar",
				update.length, serve("/download/new.jar", update), null);

		installer.install(release, NO_PROGRESS);

		assertArrayEquals(update, Files.readAllBytes(jar));
	}

	@Test
	void replacesALeftoverDownload() throws Exception {
		Files.write(installer.newJar(), new byte[1024 * 1024]);
		byte[] update = jarBytes("2.1.0");

		installer.install(released("2.1.0", update), NO_PROGRESS);

		assertArrayEquals(update, Files.readAllBytes(jar));
		assertFalse(Files.exists(installer.newJar()));
	}

	@Test
	void rejectsAChecksumMismatch() throws Exception {
		byte[] update = jarBytes("2.1.0");
		assertRejected(new ReleaseInfo(Version.parse("2.1.0"), "", "x-jar-with-dependencies.jar",
				update.length, serve("/download/new.jar", update), sha256(jarBytes("9.9.9"))));
	}

	@Test
	void rejectsADownloadSmallerThanTheRelease() throws Exception {
		byte[] update = jarBytes("2.1.0");
		assertRejected(new ReleaseInfo(Version.parse("2.1.0"), "", "x-jar-with-dependencies.jar",
				update.length + 1, serve("/download/new.jar", update), null));
	}

	@Test
	void rejectsADownloadLargerThanTheRelease() throws Exception {
		byte[] update = jarBytes("2.1.0");
		assertRejected(new ReleaseInfo(Version.parse("2.1.0"), "", "x-jar-with-dependencies.jar",
				update.length - 1, serve("/download/new.jar", update), null));
	}

	@Test
	void rejectsAConnectionThatDropsMidDownload() throws Exception {
		byte[] update = jarBytes("2.1.0");
		server.createContext("/download/cut.jar", exchange -> {
			exchange.sendResponseHeaders(200, update.length);
			exchange.getResponseBody().write(update, 0, update.length / 2);
			exchange.getResponseBody().flush();
			// Closing short of the promised length drops the connection
			exchange.close();
		});
		assertRejected(new ReleaseInfo(Version.parse("2.1.0"), "", "x-jar-with-dependencies.jar",
				update.length, url("/download/cut.jar"), sha256(update)));
	}

	@Test
	void rejectsSomethingThatIsNotAJar() throws Exception {
		assertRejected(released("2.1.0", "<html>Not Found</html>".getBytes(StandardCharsets.UTF_8)));
	}

	@Test
	void rejectsAJarOfAnotherVersion() throws Exception {
		assertRejected(released("2.1.0", jarBytes("2.0.5")));
	}

	@Test
	void rejectsAJarWithoutAVersion() throws Exception {
		assertRejected(released("2.1.0", jarBytes(null)));
	}

	@Test
	void reportsAServerError() throws Exception {
		server.createContext("/download/missing.jar", exchange -> {
			byte[] body = "Not Found".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(404, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		byte[] update = jarBytes("2.1.0");
		assertRejected(new ReleaseInfo(Version.parse("2.1.0"), "", "x-jar-with-dependencies.jar",
				update.length, url("/download/missing.jar"), sha256(update)));
	}

	@Test
	void cancellingStopsTheDownload() throws Exception {
		ReleaseInfo release = released("2.1.0", jarBytes("2.1.0"));

		assertThrows(UpdateInstaller.CancelledException.class, () -> installer.install(release,
				new UpdateInstaller.Progress() {
					public void downloaded(long bytes, long total) { }
					public boolean isCancelled() { return true; }
				}));
		assertUntouched();
	}

	@Test
	void relaunchesWithTheRunningJavaAndItsSettings() {
		List<String> command = UpdateInstaller.relaunchCommand(Path.of("/Users/x/My Apps/PackageManager.jar"),
				"/Library/Java/jdk-17/Contents/Home", List.of("-Duser.home=/tmp/home", "-Xmx512m"));

		assertEquals(List.of("/Library/Java/jdk-17/Contents/Home/bin/java", "-Duser.home=/tmp/home", "-Xmx512m",
				"-jar", "/Users/x/My Apps/PackageManager.jar"), command);
	}
}
