package update;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ProcessBuilder.Redirect;
import java.lang.management.ManagementFactory;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.logging.Logger;

/**
 * Downloads a release's jar next to the running one, checks it, and puts it in place.
 *
 * The download goes to "<jar>.new" and is only renamed over the running jar once it has
 * passed every check, so a failed or cancelled update leaves the program as it was. The
 * rename is safe while the program runs: macOS does not lock open files, and the running
 * Java keeps reading the old jar it already has open. The jar being replaced is kept as
 * "<jar>.previous" for going back by hand.
 */
public class UpdateInstaller {

	/** Hears how the download is going, and can stop it */
	public interface Progress {
		/** Called after each piece of the download arrives */
		void downloaded(long bytes, long total);

		/** Checked before each piece is saved; true stops the download */
		boolean isCancelled();
	}

	/** The user stopped the update */
	public static class CancelledException extends IOException {
		private static final long serialVersionUID = 1L;

		public CancelledException() {
			super("Update cancelled");
		}
	}

	private static final Logger logger = Logger.getLogger(UpdateInstaller.class.getName());

	private final Path jar;
	private final HttpClient http;

	/**
	 * @param jar				The running program's jar, which an update replaces
	 * @param connectTimeout	How long to wait to connect to the download server
	 */
	public UpdateInstaller(Path jar, Duration connectTimeout) {
		this.jar = jar;
		// GitHub's download links redirect to its file storage
		this.http = HttpClient.newBuilder()
				.connectTimeout(connectTimeout)
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();
	}

	/** Where the download is saved until it has been checked */
	public Path newJar() {
		return jar.resolveSibling(jar.getFileName() + ".new");
	}

	/** Where the replaced jar is kept */
	public Path previousJar() {
		return jar.resolveSibling(jar.getFileName() + ".previous");
	}

	/**
	 * Downloads the release's jar, checks it, and puts it in place of the running jar.
	 * If anything goes wrong the running jar is left as it was and the download removed.
	 *
	 * @throws CancelledException	if the progress asked to stop
	 * @throws IOException			if the jar could not be downloaded, failed a check, or
	 *								could not be put in place
	 */
	public void install(ReleaseInfo release, Progress progress) throws IOException {
		Path candidate = newJar();
		try {
			download(release, candidate, progress);
			verify(candidate, release);
			Files.copy(jar, previousJar(), StandardCopyOption.REPLACE_EXISTING);
			Files.move(candidate, jar, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException | RuntimeException e) {
			try {
				Files.deleteIfExists(candidate);
			} catch (IOException cleanup) {
				e.addSuppressed(cleanup);
			}
			throw e;
		}
		logger.info("Installed version " + release.version() + " at " + jar);
	}

	private void download(ReleaseInfo release, Path target, Progress progress) throws IOException {
		HttpRequest request = HttpRequest.newBuilder(release.downloadUrl())
				.header("User-Agent", GitHubReleaseClient.USER_AGENT)
				.GET()
				.build();
		HttpResponse<InputStream> response;
		try {
			response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
		} catch (InterruptedException e) {
			// Only cancelling interrupts the download
			Thread.currentThread().interrupt();
			throw new CancelledException();
		}
		try (InputStream in = response.body()) {
			if (response.statusCode() != 200) {
				throw new IOException("Download failed: HTTP " + response.statusCode());
			}
			// Replaces any download left behind by an earlier attempt
			try (OutputStream out = Files.newOutputStream(target)) {
				byte[] buffer = new byte[64 * 1024];
				long total = 0;
				int read;
				while ((read = in.read(buffer)) != -1) {
					if (progress.isCancelled() || Thread.currentThread().isInterrupted()) {
						throw new CancelledException();
					}
					out.write(buffer, 0, read);
					total += read;
					if (total > release.assetSize()) {
						throw new IOException("The download is larger than the release's "
								+ release.assetSize() + " bytes");
					}
					progress.downloaded(total, release.assetSize());
				}
			}
		}
	}

	private static void verify(Path candidate, ReleaseInfo release) throws IOException {
		long size = Files.size(candidate);
		if (size != release.assetSize()) {
			throw new IOException("Downloaded " + size + " bytes but the release has " + release.assetSize());
		}
		if (release.sha256() != null && !release.sha256().equals(sha256(candidate))) {
			throw new IOException("The download's checksum does not match the release");
		}
		String built;
		try (JarFile jarFile = new JarFile(candidate.toFile())) {
			Manifest manifest = jarFile.getManifest();
			built = manifest == null ? null
					: manifest.getMainAttributes().getValue(Attributes.Name.IMPLEMENTATION_VERSION);
		} catch (IOException e) {
			throw new IOException("The download is not a jar: " + e.getMessage(), e);
		}
		Version builtVersion;
		try {
			builtVersion = Version.parse(built);
		} catch (IllegalArgumentException e) {
			throw new IOException("The downloaded jar has no usable version: " + built);
		}
		if (!builtVersion.equals(release.version())) {
			throw new IOException("The downloaded jar is version " + built + ", not " + release.version());
		}
	}

	private static String sha256(Path file) throws IOException {
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			// Every Java has SHA-256
			throw new IllegalStateException(e);
		}
		try (InputStream in = Files.newInputStream(file)) {
			byte[] buffer = new byte[64 * 1024];
			int read;
			while ((read = in.read(buffer)) != -1) {
				digest.update(buffer, 0, read);
			}
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	/**
	 * Starts the jar again in a new process. The caller then exits.
	 *
	 * @throws IOException	if the new process could not be started
	 */
	public void relaunch() throws IOException {
		List<String> command = relaunchCommand(jar, System.getProperty("java.home"),
				ManagementFactory.getRuntimeMXBean().getInputArguments());
		logger.info("Restarting: " + command);
		new ProcessBuilder(command)
				.redirectOutput(Redirect.DISCARD)
				.redirectError(Redirect.DISCARD)
				.start();
	}

	/** The running Java, with the settings it was started with, running the jar */
	static List<String> relaunchCommand(Path jar, String javaHome, List<String> jvmArguments) {
		List<String> command = new ArrayList<String>();
		command.add(Paths.get(javaHome, "bin", "java").toString());
		command.addAll(jvmArguments);
		command.add("-jar");
		command.add(jar.toString());
		return command;
	}
}
