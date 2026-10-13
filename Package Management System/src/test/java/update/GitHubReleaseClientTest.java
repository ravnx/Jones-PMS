package update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

class GitHubReleaseClientTest {

	private HttpServer server;
	private volatile String userAgent;

	@AfterEach
	void stopServer() {
		if (server != null) {
			server.stop(0);
		}
	}

	private static String fixture() throws IOException {
		try (InputStream in = GitHubReleaseClientTest.class.getResourceAsStream("/update/release-2.1.0.json")) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/** A release in GitHub's format with the given notes (a JSON value) and assets (JSON objects) */
	private static String release(String tag, String notesJson, String... assets) {
		return "{\"tag_name\": \"" + tag + "\", \"body\": " + notesJson
				+ ", \"assets\": [" + String.join(",", assets) + "]}";
	}

	private static String asset(String name, long size, String digestJson) {
		return "{\"name\": \"" + name + "\", \"size\": " + size + ", \"digest\": " + digestJson
				+ ", \"browser_download_url\": \"https://example.com/" + name + "\"}";
	}

	/** Answers every request for releases/latest with the status and body; returns the repository URL */
	private URI serve(int status, String body) throws IOException {
		return serve(exchange -> {
			userAgent = exchange.getRequestHeaders().getFirst("User-Agent");
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(status, bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		});
	}

	private URI serve(HttpHandler handler) throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.setExecutor(Executors.newCachedThreadPool());
		server.createContext("/repos/ravnx/Jones-PMS/releases/latest", handler);
		server.start();
		return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/repos/ravnx/Jones-PMS/");
	}

	@Test
	void readsTheRealGitHubResponse() throws Exception {
		ReleaseInfo release = GitHubReleaseClient.parse(fixture());

		assertEquals(Version.parse("2.1.0"), release.version());
		assertEquals("package-manager-2.1.0-jar-with-dependencies.jar", release.assetName());
		assertEquals(3210852L, release.assetSize());
		assertEquals("599dd416b204ff8d0b6e393875db9b55ab6f6989243c1d988e48d8c3bfdfc7e8", release.sha256());
		assertEquals(URI.create("https://github.com/ravnx/Jones-PMS/releases/download/v2.1.0/"
				+ "package-manager-2.1.0-jar-with-dependencies.jar"), release.downloadUrl());
		assertTrue(release.notes().startsWith("## What's new in 2.1.0\n"), release.notes());
		assertFalse(release.notes().contains("\r"));
	}

	@Test
	void picksTheJarAmongOtherAssets() throws Exception {
		ReleaseInfo release = GitHubReleaseClient.parse(release("v2.2.0", "\"notes\"",
				asset("source.zip", 10, "null"),
				asset("package-manager-2.2.0.jar", 20, "null"),
				asset("package-manager-2.2.0-jar-with-dependencies.jar", 30, "\"sha256:ABCDEF\"")));

		assertEquals("package-manager-2.2.0-jar-with-dependencies.jar", release.assetName());
		assertEquals(30L, release.assetSize());
		assertEquals("abcdef", release.sha256());
	}

	@Test
	void anAssetWithoutADigestHasNoChecksum() throws Exception {
		ReleaseInfo withNull = GitHubReleaseClient.parse(release("v2.2.0", "\"\"",
				asset("x-jar-with-dependencies.jar", 1, "null")));
		ReleaseInfo withOther = GitHubReleaseClient.parse(release("v2.2.0", "\"\"",
				asset("x-jar-with-dependencies.jar", 1, "\"md5:abc\"")));
		ReleaseInfo without = GitHubReleaseClient.parse("{\"tag_name\": \"v2.2.0\", \"body\": \"\", \"assets\": ["
				+ "{\"name\": \"x-jar-with-dependencies.jar\", \"size\": 1, \"browser_download_url\": \"https://example.com/x\"}]}");

		assertNull(withNull.sha256());
		assertNull(withOther.sha256());
		assertNull(without.sha256());
	}

	@Test
	void missingNotesAreEmpty() throws Exception {
		ReleaseInfo nullBody = GitHubReleaseClient.parse(release("v2.2.0", "null",
				asset("x-jar-with-dependencies.jar", 1, "null")));
		ReleaseInfo noBody = GitHubReleaseClient.parse("{\"tag_name\": \"v2.2.0\", \"assets\": ["
				+ asset("x-jar-with-dependencies.jar", 1, "null") + "]}");

		assertEquals("", nullBody.notes());
		assertEquals("", noBody.notes());
	}

	@Test
	void aReleaseWithoutAJarIsRejected() {
		assertThrows(IOException.class, () -> GitHubReleaseClient.parse(release("v2.2.0", "\"\"",
				asset("source.zip", 10, "null"))));
		assertThrows(IOException.class, () -> GitHubReleaseClient.parse(release("v2.2.0", "\"\"")));
	}

	@Test
	void malformedReleasesAreRejected() {
		String[] bad = { "", "not json", "[]", "{}", "{\"tag_name\": \"latest\", \"assets\": []}",
				"{\"tag_name\": \"v2.2.0\"}", "{\"tag_name\": \"v2.2.0\", \"assets\": [{\"name\": \"x-jar-with-dependencies.jar\"}]}" };
		for (String json : bad) {
			assertThrows(IOException.class, () -> GitHubReleaseClient.parse(json), "accepted: " + json);
		}
	}

	@Test
	void fetchesTheLatestReleaseWithAUserAgent() throws Exception {
		URI repository = serve(200, fixture());

		ReleaseInfo release = new GitHubReleaseClient(repository, Duration.ofSeconds(5)).fetchLatest();

		assertEquals(Version.parse("2.1.0"), release.version());
		assertEquals(GitHubReleaseClient.USER_AGENT, userAgent);
	}

	@Test
	void anErrorFromGitHubIsReported() throws Exception {
		for (int status : new int[] { 403, 404, 500 }) {
			stopServer();
			URI repository = serve(status, "{\"message\": \"no\"}");
			GitHubReleaseClient client = new GitHubReleaseClient(repository, Duration.ofSeconds(5));
			IOException e = assertThrows(IOException.class, client::fetchLatest);
			assertTrue(e.getMessage().contains(Integer.toString(status)), e.getMessage());
		}
	}

	@Test
	void givesUpOnASlowServer() throws Exception {
		URI repository = serve(exchange -> {
			try {
				Thread.sleep(2000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			exchange.close();
		});
		GitHubReleaseClient client = new GitHubReleaseClient(repository, Duration.ofMillis(300));

		long start = System.nanoTime();
		assertThrows(IOException.class, client::fetchLatest);
		assertTrue(System.nanoTime() - start < 1_500_000_000L, "waited for the slow server");
	}

	@Test
	void nobodyListeningIsReported() throws Exception {
		URI repository = serve(200, "{}");
		stopServer();
		server = null;

		GitHubReleaseClient client = new GitHubReleaseClient(repository, Duration.ofSeconds(2));
		assertThrows(IOException.class, client::fetchLatest);
	}
}
