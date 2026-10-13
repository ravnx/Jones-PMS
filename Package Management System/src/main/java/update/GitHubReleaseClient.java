package update;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Asks GitHub for the newest published release.
 *
 * GitHub's "latest" release is never a draft or a pre-release, so publishing a release
 * as a pre-release keeps it from being offered to the mail room.
 */
public class GitHubReleaseClient {

	/** The repository the program updates from */
	public static final URI REPOSITORY = URI.create("https://api.github.com/repos/ravnx/Jones-PMS/");

	/** GitHub rejects API requests that do not name the program making them */
	static final String USER_AGENT = "Jones-PMS-updater";

	/** The release asset that is the runnable jar */
	static final String JAR_SUFFIX = "-jar-with-dependencies.jar";

	private static final String SHA256_PREFIX = "sha256:";

	private final URI repository;
	private final Duration timeout;
	private final HttpClient http;

	/**
	 * @param repository	The repository's API URL, ending in "/"
	 * @param timeout		How long to wait to connect, and again for the answer
	 */
	public GitHubReleaseClient(URI repository, Duration timeout) {
		this.repository = repository;
		this.timeout = timeout;
		this.http = HttpClient.newBuilder()
				.connectTimeout(timeout)
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();
	}

	/**
	 * @return				The newest published release
	 * @throws IOException	if GitHub cannot be reached, answers with an error, or the
	 *						release has no usable version or jar
	 */
	public ReleaseInfo fetchLatest() throws IOException {
		HttpRequest request = HttpRequest.newBuilder(repository.resolve("releases/latest"))
				.timeout(timeout)
				.header("Accept", "application/vnd.github+json")
				.header("User-Agent", USER_AGENT)
				.GET()
				.build();
		// Before Java 26 the request timeout stops once the headers arrive, so a reply that
		// stalls part way would hang startup. This limit covers the whole exchange.
		CompletableFuture<HttpResponse<String>> pending =
				http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		HttpResponse<String> response;
		try {
			response = pending.get(timeout.toMillis() * 2, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			pending.cancel(true);
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while checking for updates", e);
		} catch (TimeoutException e) {
			pending.cancel(true);
			throw new IOException("GitHub did not answer in time", e);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			throw cause instanceof IOException ? (IOException) cause : new IOException(cause);
		}
		if (response.statusCode() != 200) {
			throw new IOException("GitHub answered HTTP " + response.statusCode());
		}
		return parse(response.body());
	}

	/**
	 * Reads a release in GitHub's JSON format.
	 *
	 * @throws IOException	if the release is malformed or has no runnable jar
	 */
	static ReleaseInfo parse(String json) throws IOException {
		try {
			JsonObject release = JsonParser.parseString(json).getAsJsonObject();
			Version version = Version.parse(release.get("tag_name").getAsString());
			String notes = stringOrEmpty(release.get("body")).replace("\r\n", "\n");

			for (JsonElement element : release.getAsJsonArray("assets")) {
				JsonObject asset = element.getAsJsonObject();
				String name = asset.get("name").getAsString();
				if (!name.endsWith(JAR_SUFFIX)) {
					continue;
				}
				String digest = stringOrEmpty(asset.get("digest"));
				String sha256 = digest.startsWith(SHA256_PREFIX)
						? digest.substring(SHA256_PREFIX.length()).toLowerCase(Locale.ROOT)
						: null;
				return new ReleaseInfo(version, notes, name, asset.get("size").getAsLong(),
						URI.create(asset.get("browser_download_url").getAsString()), sha256);
			}
			throw new IOException("Release " + version + " has no *" + JAR_SUFFIX + " to download");
		} catch (RuntimeException e) {
			// Gson and Version report malformed or missing fields with unchecked exceptions
			throw new IOException("Could not read the release from GitHub: " + e, e);
		}
	}

	private static String stringOrEmpty(JsonElement element) {
		return element == null || element.isJsonNull() ? "" : element.getAsString();
	}
}
