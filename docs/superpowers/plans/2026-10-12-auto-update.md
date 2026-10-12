# Auto-update Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** On startup, the app offers the newest GitHub release of `ravnx/Jones-PMS` and, if the user approves, downloads it, verifies it, swaps it in place of the running jar and restarts into it.

**Architecture:** A new `update` package: `Version` (parse/compare), `ReleaseInfo` (record), `GitHubReleaseClient` (fetch + parse `releases/latest`), `UpdateInstaller` (download to `<jar>.new`, verify, keep `<jar>.previous`, atomic rename, relaunch) and `Updater` (eligibility, decision, Swing dialogs). `Controller.start()` calls `Updater.checkAtStartup()` before the window opens and before reminder emails are sent. A `release.sh` script guards and publishes releases.

**Tech Stack:** Java 17, Swing, `java.net.http.HttpClient`, Gson 2.11 (already a dependency), JUnit 5, `com.sun.net.httpserver` for tests, bash + `gh` for releasing.

**Spec:** `docs/superpowers/specs/2026-10-12-auto-update-design.md`

## Global Constraints

- Java release 17 (`maven.compiler.release` 17). No new Maven dependencies: JDK + Gson only.
- macOS only. The swap relies on Unix not locking a running jar. No Windows code paths.
- Repository: `ravnx/Jones-PMS`; API base `https://api.github.com/repos/ravnx/Jones-PMS/`.
- Asset chosen: the one whose name ends with `-jar-with-dependencies.jar`.
- Skip preference key in `config.properties`: `update_skip_version`.
- Network timeout: 5 seconds (connect and request) for the release check.
- Recommended install path: `~/Documents/package_management_system/app/PackageManager.jar`.
- Code style: tabs for indentation, `java.util.logging.Logger` per class, Javadoc-style comments as in `model/database/Backup.java`. Tests are package-private JUnit 5 classes using `@TempDir`.
- All files pure ASCII (a hook enforces it): use `-`, `->`, plain quotes.
- Nothing in the updater may stop the program starting: every failure is logged and startup continues.
- All `mvn` commands run from the `Package Management System/` directory (quote it: it has spaces).

**Deliberate deviation from the spec:** relaunch uses `<java.home>/bin/java` directly instead of trying `ProcessHandle.current().info().command()` first. `java.home` always names the running JVM, while the process command under macOS's Jar Launcher is not guaranteed to be a `java` binary. The relaunch also passes the running JVM's arguments through (so `-Duser.home=...` used in the end-to-end test survives the restart).

## Review Focus

1. GitHub download links answer with a 302 redirect to its file storage -> the download must follow it (test `followsRedirects`, Task 3).
2. The connection drops mid-download, so fewer bytes arrive than promised -> rejected, running jar untouched, `.new` removed (test `rejectsAConnectionThatDropsMidDownload`, Task 3).
3. The jar sits in a folder whose path contains spaces, so the code-source URL has `%20` -> it is still found and updated (test `findsAJarInAFolderWithSpaces`, Task 4).
4. A release published with no notes (`"body": null`) -> still offered, with empty notes rather than a crash (test `missingNotesAreEmpty`, Task 2).
5. The running jar is newer than GitHub's latest (a local test build) -> no offer, never a downgrade (test `doesNotOfferAnOlderRelease`, Task 4).

---

## File Structure

Create:
- `Package Management System/src/main/java/update/Version.java` - parse and compare versions.
- `Package Management System/src/main/java/update/ReleaseInfo.java` - immutable release + chosen asset.
- `Package Management System/src/main/java/update/GitHubReleaseClient.java` - fetch and parse `releases/latest`.
- `Package Management System/src/main/java/update/UpdateInstaller.java` - download, verify, swap, relaunch.
- `Package Management System/src/main/java/update/Updater.java` - startup orchestration and dialogs.
- `Package Management System/src/test/java/update/VersionTest.java`
- `Package Management System/src/test/java/update/GitHubReleaseClientTest.java`
- `Package Management System/src/test/java/update/UpdateInstallerTest.java`
- `Package Management System/src/test/java/update/UpdaterTest.java`
- `Package Management System/src/test/resources/update/release-2.1.0.json` - saved real GitHub response.
- `release.sh` (repo root) - guarded release publishing.

Modify:
- `Package Management System/src/main/java/controller/Controller.java` - `start()` calls the updater first.
- `Package Management System/pom.xml` - version 2.2.0 (final task).
- `README.md` - macOS install layout, Updates, Releasing.

---

### Task 1: Version

**Files:**
- Create: `Package Management System/src/main/java/update/Version.java`
- Test: `Package Management System/src/test/java/update/VersionTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `public final class Version implements Comparable<Version>` with
  `public static Version parse(String text)` (throws `IllegalArgumentException`),
  `public int compareTo(Version other)`, `equals`/`hashCode` (so `2.1` equals `2.1.0`),
  `public String toString()` (at least three parts, no `v`, e.g. `"2.1.0"`).

- [ ] **Step 1: Write the failing test**

`Package Management System/src/test/java/update/VersionTest.java`:

```java
package update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VersionTest {

	@Test
	void ignoresTheLeadingVOfATagName() {
		assertEquals(Version.parse("2.1.0"), Version.parse("v2.1.0"));
	}

	@Test
	void missingPartsCountAsZero() {
		assertEquals(Version.parse("2.1"), Version.parse("2.1.0"));
		assertEquals(Version.parse("2.1").hashCode(), Version.parse("2.1.0").hashCode());
		assertEquals(0, Version.parse("2").compareTo(Version.parse("2.0.0")));
	}

	@Test
	void comparesNumbersNotText() {
		assertTrue(Version.parse("2.10.0").compareTo(Version.parse("2.9.0")) > 0);
		assertTrue(Version.parse("3.0.0").compareTo(Version.parse("2.99.99")) > 0);
		assertTrue(Version.parse("2.1.1").compareTo(Version.parse("2.1.0")) > 0);
		assertTrue(Version.parse("2.1.0").compareTo(Version.parse("2.1.1")) < 0);
	}

	@Test
	void printsAtLeastThreeParts() {
		assertEquals("2.0.0", Version.parse("v2").toString());
		assertEquals("2.1.0", Version.parse("2.1").toString());
		assertEquals("2.1.3", Version.parse("2.1.3").toString());
		assertEquals("2.1.3.4", Version.parse("2.1.3.4").toString());
	}

	@Test
	void acceptsSurroundingWhitespace() {
		assertEquals(Version.parse("2.1.0"), Version.parse(" v2.1.0\n"));
	}

	@Test
	void rejectsAnythingThatIsNotAPlainVersion() {
		String[] bad = { null, "", " ", "v", "latest", "2.1.0-beta", "2.2.0-SNAPSHOT", "2..1",
				"2.1.", ".2.1", "1.2.3.4.5", "99999999999" };
		for (String text : bad) {
			assertThrows(IllegalArgumentException.class, () -> Version.parse(text), "accepted: " + text);
		}
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd "Package Management System" && mvn -q test -Dtest=VersionTest`
Expected: compilation failure, `cannot find symbol ... class Version`.

- [ ] **Step 3: Write the implementation**

`Package Management System/src/main/java/update/Version.java`:

```java
package update;

import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * A release version such as 2.1.0. A leading "v", as in GitHub tag names, is ignored,
 * and missing trailing parts count as zero, so "v2.1" equals "2.1.0".
 *
 * Only plain dotted numbers are versions. Anything else, such as "2.2.0-SNAPSHOT", is
 * rejected, so the updater never guesses at how an unusual version compares.
 */
public final class Version implements Comparable<Version> {

	private static final Pattern FORMAT = Pattern.compile("v?\\d+(\\.\\d+){0,3}");

	// Trailing zeros are dropped so that equal versions are equal and hash the same
	private final int[] parts;

	private Version(int[] parts) {
		this.parts = parts;
	}

	/**
	 * @param text			A version such as "2.1.0" or a tag name such as "v2.1.0"
	 * @throws IllegalArgumentException	if the text is not a plain dotted version
	 */
	public static Version parse(String text) {
		if (text == null || !FORMAT.matcher(text.trim()).matches()) {
			throw new IllegalArgumentException("Not a version: " + text);
		}
		String digits = text.trim();
		if (digits.startsWith("v")) {
			digits = digits.substring(1);
		}
		String[] pieces = digits.split("\\.");
		int[] parts = new int[pieces.length];
		for (int i = 0; i < pieces.length; i++) {
			// Too many digits for an int throws NumberFormatException, an IllegalArgumentException
			parts[i] = Integer.parseInt(pieces[i]);
		}
		int length = parts.length;
		while (length > 1 && parts[length - 1] == 0) {
			length--;
		}
		return new Version(Arrays.copyOf(parts, length));
	}

	@Override
	public int compareTo(Version other) {
		int length = Math.max(parts.length, other.parts.length);
		for (int i = 0; i < length; i++) {
			int mine = i < parts.length ? parts[i] : 0;
			int theirs = i < other.parts.length ? other.parts[i] : 0;
			if (mine != theirs) {
				return Integer.compare(mine, theirs);
			}
		}
		return 0;
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof Version && Arrays.equals(parts, ((Version) other).parts);
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(parts);
	}

	@Override
	public String toString() {
		StringBuilder text = new StringBuilder();
		for (int i = 0; i < Math.max(parts.length, 3); i++) {
			if (i > 0) {
				text.append('.');
			}
			text.append(i < parts.length ? parts[i] : 0);
		}
		return text.toString();
	}
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd "Package Management System" && mvn -q test -Dtest=VersionTest`
Expected: no failures (quiet output, exit code 0).

- [ ] **Step 5: Commit**

```bash
git add "Package Management System/src/main/java/update/Version.java" "Package Management System/src/test/java/update/VersionTest.java"
git commit -m "Add Version for comparing release versions"
```

---

### Task 2: ReleaseInfo and GitHubReleaseClient

**Files:**
- Create: `Package Management System/src/main/java/update/ReleaseInfo.java`
- Create: `Package Management System/src/main/java/update/GitHubReleaseClient.java`
- Create: `Package Management System/src/test/resources/update/release-2.1.0.json`
- Test: `Package Management System/src/test/java/update/GitHubReleaseClientTest.java`

**Interfaces:**
- Consumes: `Version.parse(String)` from Task 1.
- Produces:
  - `public record ReleaseInfo(Version version, String notes, String assetName, long assetSize, URI downloadUrl, String sha256)` - `notes` never null (empty when the release has none, `\r\n` turned into `\n`); `sha256` lowercase hex or `null`.
  - `public class GitHubReleaseClient` with `public static final URI REPOSITORY`, `static final String USER_AGENT = "Jones-PMS-updater"`, `static final String JAR_SUFFIX = "-jar-with-dependencies.jar"`, constructor `GitHubReleaseClient(URI repository, Duration timeout)`, `public ReleaseInfo fetchLatest() throws IOException`, `static ReleaseInfo parse(String json) throws IOException`.

- [ ] **Step 1: Save the real GitHub response as a test fixture**

The `releases/tags/v2.1.0` endpoint returns the same shape as `releases/latest` but will not change when newer releases appear. `ensure_ascii` keeps the file pure ASCII (the notes contain a Unicode arrow, which is written as a JSON escape).

```bash
mkdir -p "Package Management System/src/test/resources/update"
curl -sf -H "Accept: application/vnd.github+json" https://api.github.com/repos/ravnx/Jones-PMS/releases/tags/v2.1.0 \
  | python3 -c 'import json,sys; json.dump(json.load(sys.stdin), sys.stdout, indent=2, ensure_ascii=True)' \
  > "Package Management System/src/test/resources/update/release-2.1.0.json"
grep -c '"tag_name": "v2.1.0"' "Package Management System/src/test/resources/update/release-2.1.0.json"
```

Expected: the final `grep` prints `1`.

- [ ] **Step 2: Write the failing test**

`Package Management System/src/test/java/update/GitHubReleaseClientTest.java`:

```java
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
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd "Package Management System" && mvn -q test -Dtest=GitHubReleaseClientTest`
Expected: compilation failure, `cannot find symbol ... class ReleaseInfo` / `GitHubReleaseClient`.

- [ ] **Step 4: Write `ReleaseInfo`**

`Package Management System/src/main/java/update/ReleaseInfo.java`:

```java
package update;

import java.net.URI;

/**
 * A published release and the jar to download for it.
 *
 * @param version		The release's version, from its tag
 * @param notes			The release notes as written on GitHub (Markdown); empty if none
 * @param assetName		The jar's file name on the release
 * @param assetSize		The jar's size in bytes
 * @param downloadUrl	Where to download the jar
 * @param sha256		The jar's SHA-256 as lowercase hex, or null if GitHub gave none
 */
public record ReleaseInfo(Version version, String notes, String assetName, long assetSize,
		URI downloadUrl, String sha256) {
}
```

- [ ] **Step 5: Write `GitHubReleaseClient`**

`Package Management System/src/main/java/update/GitHubReleaseClient.java`:

```java
package update;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

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
		HttpResponse<String> response;
		try {
			response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while checking for updates", e);
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
```

- [ ] **Step 6: Run test to verify it passes**

Run: `cd "Package Management System" && mvn -q test -Dtest=GitHubReleaseClientTest`
Expected: no failures.

- [ ] **Step 7: Commit**

```bash
git add "Package Management System/src/main/java/update/ReleaseInfo.java" \
  "Package Management System/src/main/java/update/GitHubReleaseClient.java" \
  "Package Management System/src/test/java/update/GitHubReleaseClientTest.java" \
  "Package Management System/src/test/resources/update/release-2.1.0.json"
git commit -m "Add GitHubReleaseClient for finding the newest release"
```

---

### Task 3: UpdateInstaller

**Files:**
- Create: `Package Management System/src/main/java/update/UpdateInstaller.java`
- Test: `Package Management System/src/test/java/update/UpdateInstallerTest.java`

**Interfaces:**
- Consumes: `ReleaseInfo` (record accessors `version()`, `assetSize()`, `downloadUrl()`, `sha256()`), `Version.parse`, `GitHubReleaseClient.USER_AGENT`.
- Produces:
  - `public class UpdateInstaller` with constructor `UpdateInstaller(Path jar, Duration connectTimeout)`.
  - `public interface UpdateInstaller.Progress { void downloaded(long bytes, long total); boolean isCancelled(); }`
  - `public static class UpdateInstaller.CancelledException extends IOException`
  - `public Path newJar()` (`<jar>.new`), `public Path previousJar()` (`<jar>.previous`)
  - `public void install(ReleaseInfo release, Progress progress) throws IOException` - download + verify + swap; on any failure the jar is untouched and `.new` removed.
  - `public void relaunch() throws IOException`
  - `static List<String> relaunchCommand(Path jar, String javaHome, List<String> jvmArguments)`

- [ ] **Step 1: Write the failing test**

`Package Management System/src/test/java/update/UpdateInstallerTest.java`:

```java
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
			try {
				// Closing short of the promised length drops the connection
				exchange.close();
			} catch (IOException expected) {
			}
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd "Package Management System" && mvn -q test -Dtest=UpdateInstallerTest`
Expected: compilation failure, `cannot find symbol ... class UpdateInstaller`.

- [ ] **Step 3: Write the implementation**

`Package Management System/src/main/java/update/UpdateInstaller.java`:

```java
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd "Package Management System" && mvn -q test -Dtest=UpdateInstallerTest`
Expected: no failures. If `rejectsAConnectionThatDropsMidDownload` passes only because of the size check, that is still correct behavior; do not weaken it.

- [ ] **Step 5: Commit**

```bash
git add "Package Management System/src/main/java/update/UpdateInstaller.java" \
  "Package Management System/src/test/java/update/UpdateInstallerTest.java"
git commit -m "Add UpdateInstaller: download, verify and swap in a release jar"
```

---

### Task 4: Updater and the startup hook

**Files:**
- Create: `Package Management System/src/main/java/update/Updater.java`
- Modify: `Package Management System/src/main/java/controller/Controller.java` (`start()` near the end of the file, plus an import)
- Test: `Package Management System/src/test/java/update/UpdaterTest.java`

**Interfaces:**
- Consumes: `Version.parse`, `ReleaseInfo`, `GitHubReleaseClient(URI, Duration)`, `GitHubReleaseClient.REPOSITORY`, `GitHubReleaseClient#fetchLatest()`, `UpdateInstaller(Path, Duration)`, `UpdateInstaller#install(ReleaseInfo, Progress)`, `UpdateInstaller#relaunch()`, `UpdateInstaller.Progress`, `util.PropertyHandler.getInstance().getProperty(String)` / `setProperty(String, String)`.
- Produces: `public final class Updater` with `public static void checkAtStartup()`, and for tests `static final String SKIP_PROPERTY = "update_skip_version"`, `static boolean shouldOffer(Version running, ReleaseInfo latest, String skipped)`, `static String eligibilityProblem(String runningVersion, Path jar)`, `static Path jarFrom(URL location)`.

- [ ] **Step 1: Write the failing test**

`Package Management System/src/test/java/update/UpdaterTest.java`:

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd "Package Management System" && mvn -q test -Dtest=UpdaterTest`
Expected: compilation failure, `cannot find symbol ... class Updater`.

- [ ] **Step 3: Write `Updater`**

`Package Management System/src/main/java/update/Updater.java`:

```java
package update;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;

import util.PropertyHandler;

/**
 * Offers the newest GitHub release when the program starts, and installs it if the user
 * agrees.
 *
 * Nothing here may keep the mail room from working: with no network, an error from
 * GitHub, or a bad download, the problem is logged and the program starts with the
 * version it has.
 */
public final class Updater {

	/** The version the user chose to skip, in config.properties */
	static final String SKIP_PROPERTY = "update_skip_version";

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private static final Logger logger = Logger.getLogger(Updater.class.getName());

	private enum Choice { UPDATE, LATER, SKIP }

	private Updater() { }

	/**
	 * Checks for and offers an update. Called once on the event thread before any window
	 * is shown; returns when the program should carry on starting, or exits after a
	 * successful update has started the new version.
	 */
	public static void checkAtStartup() {
		try {
			check();
		} catch (RuntimeException e) {
			logger.log(Level.WARNING, "Update check failed", e);
		}
	}

	private static void check() {
		// The version comes from the jar manifest, so it is null when run from an IDE
		String running = Updater.class.getPackage().getImplementationVersion();
		CodeSource source = Updater.class.getProtectionDomain().getCodeSource();
		Path jar = jarFrom(source == null ? null : source.getLocation());
		String problem = eligibilityProblem(running, jar);
		if (problem != null) {
			logger.info("Not checking for updates: " + problem);
			return;
		}

		ReleaseInfo latest;
		try {
			latest = new GitHubReleaseClient(GitHubReleaseClient.REPOSITORY, TIMEOUT).fetchLatest();
		} catch (IOException e) {
			logger.info("Could not check for updates: " + e.getMessage());
			return;
		}

		PropertyHandler props = PropertyHandler.getInstance();
		if (!shouldOffer(Version.parse(running), latest, props.getProperty(SKIP_PROPERTY))) {
			logger.info("No update offered; running " + running + ", newest release " + latest.version());
			return;
		}

		switch (askUser(running, latest)) {
		case UPDATE:
			install(jar, running, latest);
			break;
		case SKIP:
			logger.info("Skipping version " + latest.version());
			props.setProperty(SKIP_PROPERTY, latest.version().toString());
			break;
		case LATER:
			break;
		}
	}

	/**
	 * @return	whether to offer the release: it is newer than the running version and
	 *			is not the version the user skipped
	 */
	static boolean shouldOffer(Version running, ReleaseInfo latest, String skipped) {
		if (latest.version().compareTo(running) <= 0) {
			return false;
		}
		if (skipped == null) {
			return true;
		}
		try {
			return !Version.parse(skipped).equals(latest.version());
		} catch (IllegalArgumentException e) {
			return true;
		}
	}

	/**
	 * @param runningVersion	The running version from the jar manifest, or null
	 * @param jar				The jar the program is running from, or null
	 * @return					why this copy cannot update itself, or null if it can
	 */
	static String eligibilityProblem(String runningVersion, Path jar) {
		if (runningVersion == null) {
			return "development build";
		}
		try {
			Version.parse(runningVersion);
		} catch (IllegalArgumentException e) {
			return "unrecognised version " + runningVersion;
		}
		if (jar == null) {
			return "not running from a jar";
		}
		if (!Files.isWritable(jar.getParent())) {
			return "cannot write to " + jar.getParent();
		}
		return null;
	}

	/**
	 * @param location	Where the program's classes were loaded from
	 * @return			that location as a jar file, or null if it is not one
	 */
	static Path jarFrom(URL location) {
		if (location == null) {
			return null;
		}
		try {
			// Through a URI, so "%20" in folder names becomes a space again
			Path path = Paths.get(location.toURI());
			return Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar") ? path : null;
		} catch (URISyntaxException | IllegalArgumentException | FileSystemNotFoundException e) {
			return null;
		}
	}

	private static Choice askUser(String running, ReleaseInfo latest) {
		JTextArea notes = new JTextArea(latest.notes().isEmpty() ? "(No release notes)" : latest.notes(), 15, 60);
		notes.setEditable(false);
		notes.setLineWrap(true);
		notes.setWrapStyleWord(true);
		notes.setCaretPosition(0);

		JPanel panel = new JPanel(new BorderLayout(0, 8));
		panel.add(new JLabel("Version " + latest.version() + " is available. You have version "
				+ running + "."), BorderLayout.NORTH);
		panel.add(new JScrollPane(notes), BorderLayout.CENTER);
		panel.add(new JLabel("Students, packages and settings are kept when you update."), BorderLayout.SOUTH);

		String[] options = { "Update now", "Later", "Skip this version" };
		int answer = JOptionPane.showOptionDialog(null, panel, "Update available",
				JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null, options, options[0]);
		if (answer == 0) {
			return Choice.UPDATE;
		}
		if (answer == 2) {
			return Choice.SKIP;
		}
		// "Later", or the dialog was closed
		return Choice.LATER;
	}

	private static void install(Path jar, String running, ReleaseInfo latest) {
		UpdateInstaller installer = new UpdateInstaller(jar, TIMEOUT);
		AtomicBoolean cancelled = new AtomicBoolean();

		JProgressBar bar = new JProgressBar(0, 100);
		bar.setStringPainted(true);
		JButton cancel = new JButton("Cancel");
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
		buttons.add(cancel);
		JPanel panel = new JPanel(new BorderLayout(0, 8));
		panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
		panel.add(new JLabel("Downloading version " + latest.version() + "..."), BorderLayout.NORTH);
		panel.add(bar, BorderLayout.CENTER);
		panel.add(buttons, BorderLayout.SOUTH);

		JDialog dialog = new JDialog((Frame) null, "Updating", true);
		dialog.setContentPane(panel);
		dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
		dialog.pack();
		dialog.setLocationRelativeTo(null);

		SwingWorker<Void, Void> worker = new SwingWorker<Void, Void>() {
			@Override
			protected Void doInBackground() throws IOException {
				installer.install(latest, new UpdateInstaller.Progress() {
					public void downloaded(long bytes, long total) {
						int percent = (int) (bytes * 100 / Math.max(total, 1));
						SwingUtilities.invokeLater(() -> {
							bar.setValue(percent);
							// The checks and swap that follow take moments and cannot be stopped
							if (bytes >= total) {
								cancel.setEnabled(false);
							}
						});
					}

					public boolean isCancelled() {
						return cancelled.get();
					}
				});
				return null;
			}

			@Override
			protected void done() {
				dialog.dispose();
			}
		};
		cancel.addActionListener(e -> {
			cancelled.set(true);
			// Also interrupts a download stalled waiting on the network
			worker.cancel(true);
		});

		worker.execute();
		// Modal: returns once done() has closed the dialog
		dialog.setVisible(true);

		if (cancelled.get()) {
			logger.info("Update to " + latest.version() + " cancelled");
			return;
		}
		try {
			worker.get();
		} catch (InterruptedException | ExecutionException e) {
			Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
			logger.log(Level.WARNING, "Update to " + latest.version() + " failed", cause);
			JOptionPane.showMessageDialog(null, "The update could not be installed. Continuing with version "
					+ running + ".\n\n" + cause.getMessage(), "Update failed", JOptionPane.WARNING_MESSAGE);
			return;
		}

		try {
			installer.relaunch();
		} catch (IOException e) {
			logger.log(Level.WARNING, "Could not restart after updating", e);
			JOptionPane.showMessageDialog(null, "Version " + latest.version() + " is installed, but the program "
					+ "could not restart itself.\nPlease open the Package Management System again.",
					"Update installed", JOptionPane.INFORMATION_MESSAGE);
		}
		System.exit(0);
	}
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd "Package Management System" && mvn -q test -Dtest=UpdaterTest`
Expected: no failures (the read-only-folder test may report as skipped only if run as root).

- [ ] **Step 5: Hook it into startup**

In `Package Management System/src/main/java/controller/Controller.java`, add the import after `import model.PackageManager;`:

```java
import update.Updater;
```

Replace the `start()` method:

```java
	public void start() {
		// Start view and model
		viewFrame.start();
		modelPM.start();
	}
```

with:

```java
	public void start() {
		// Before anything is shown, and before modelPM.start() sends the reminder emails,
		// so that after an update they are sent by the new version
		Updater.checkAtStartup();

		// Start view and model
		viewFrame.start();
		modelPM.start();
	}
```

- [ ] **Step 6: Run the whole suite and build**

Run: `cd "Package Management System" && mvn -q clean package`
Expected: build succeeds with no test failures; `target/package-manager-2.1.0-jar-with-dependencies.jar` exists.

- [ ] **Step 7: Commit**

```bash
git add "Package Management System/src/main/java/update/Updater.java" \
  "Package Management System/src/test/java/update/UpdaterTest.java" \
  "Package Management System/src/main/java/controller/Controller.java"
git commit -m "Offer and install GitHub releases at startup"
```

---

### Task 5: release.sh

**Files:**
- Create: `release.sh` (repo root, executable)

**Interfaces:**
- Consumes: `pom.xml` version; `gh` CLI (authenticated); `mvn`; `unzip`.
- Produces: `./release.sh <notes-file> [--prerelease] [--dry-run]`. Exit 0 on success or completed dry run; exit 1 with a `release: ...` message on any refused check.

- [ ] **Step 1: Write the script**

`release.sh`:

```bash
#!/usr/bin/env bash
# Publishes the version in pom.xml as a GitHub release with the runnable jar attached.
#
#   ./release.sh <notes-file> [--prerelease] [--dry-run]
#
# The mail room is offered the newest release that is not a pre-release, and sees the
# notes in the update prompt, so write them for the mail room.
#
#   --prerelease  publish without offering it to the mail room
#   --dry-run     run every check and build the jar, but publish nothing
set -euo pipefail

REPO=ravnx/Jones-PMS
PROJECT="Package Management System"

die() {
	echo "release: $*" >&2
	exit 1
}

notes=""
prerelease=""
dry_run=false
for arg in "$@"; do
	case "$arg" in
		--prerelease) prerelease=--prerelease ;;
		--dry-run) dry_run=true ;;
		-*) die "unknown option $arg" ;;
		*)
			[ -z "$notes" ] || die "give only one notes file"
			notes=$arg
			;;
	esac
done
[ -n "$notes" ] || die "usage: ./release.sh <notes-file> [--prerelease] [--dry-run]"
[ -f "$notes" ] || die "notes file not found: $notes"
notes="$(cd "$(dirname "$notes")" && pwd)/$(basename "$notes")"

cd "$(dirname "$0")"

[ -z "$(git status --porcelain)" ] || die "commit or stash your changes first"
[ "$(git rev-parse --abbrev-ref HEAD)" = main ] || die "releases are made from main"
head=$(git rev-parse HEAD)
remote=$(gh api "repos/$REPO/branches/main" --jq .commit.sha) || die "could not ask GitHub about main"
[ "$head" = "$remote" ] || die "local main ($head) is not GitHub's main ($remote); push or pull first"

version=$(mvn -q -f "$PROJECT/pom.xml" help:evaluate -Dexpression=project.version -DforceStdout)
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "pom.xml version '$version' is not like 2.2.0"
tag="v$version"
if git rev-parse -q --verify "refs/tags/$tag" >/dev/null; then
	die "$tag already exists here; set a new version in pom.xml"
fi
if gh api "repos/$REPO/git/refs/tags/$tag" >/dev/null 2>&1; then
	die "$tag already exists on GitHub; set a new version in pom.xml"
fi

(cd "$PROJECT" && mvn -q clean package)
jar="$PROJECT/target/package-manager-$version-jar-with-dependencies.jar"
[ -f "$jar" ] || die "the build did not produce $jar"
unzip -p "$jar" META-INF/MANIFEST.MF | tr -d '\r' | grep -qx "Implementation-Version: $version" \
	|| die "$jar does not say it is version $version"

if $dry_run; then
	echo "Dry run: every check passed and $jar is built. Nothing was published."
	exit 0
fi

echo "About to publish $tag from $head${prerelease:+ as a pre-release}, with:"
echo "  $jar"
read -r -p "Publish? [y/N] " answer
case "$answer" in
	y|Y) ;;
	*) die "not published" ;;
esac

gh release create "$tag" --repo "$REPO" --target "$head" --title "$tag Release" \
	--notes-file "$notes" ${prerelease:+"$prerelease"} "$jar"
git tag "$tag" "$head"
echo "Published $tag"
```

Run: `chmod +x release.sh`

- [ ] **Step 2: Check its syntax**

Run: `bash -n release.sh && (command -v shellcheck >/dev/null && shellcheck release.sh || echo "shellcheck not installed")`
Expected: no output from `bash -n`; shellcheck clean or "shellcheck not installed".

- [ ] **Step 3: Check the guards refuse correctly**

Each command below must exit 1 and print the quoted message. None of them publish anything.

```bash
./release.sh; echo "exit $?"
# release: usage: ./release.sh <notes-file> [--prerelease] [--dry-run]   exit 1
./release.sh no-such-file.md; echo "exit $?"
# release: notes file not found: no-such-file.md   exit 1
./release.sh README.md --bogus; echo "exit $?"
# release: unknown option --bogus   exit 1
touch untracked-check.tmp; ./release.sh README.md --dry-run; echo "exit $?"; rm untracked-check.tmp
# release: commit or stash your changes first   exit 1
./release.sh README.md --dry-run; echo "exit $?"
# (on the auto-update branch, clean tree) release: releases are made from main   exit 1
```

- [ ] **Step 4: Commit**

```bash
git add release.sh
git commit -m "Add release.sh to publish checked releases"
```

---

### Task 6: Docs, version 2.2.0 and end-to-end check

**Files:**
- Modify: `README.md` (Installation instructions, lines 8-26; new sections after "Data and backups")
- Modify: `Package Management System/pom.xml` (`<version>2.1.0</version>` -> `2.2.0`)

**Interfaces:**
- Consumes: everything above.
- Produces: the 2.2.0 build that is installed by hand once at the mailroom.

- [ ] **Step 1: Rewrite the installation steps in README.md**

Replace lines 11-15 (from `1. [Install Java 17 or newer]` through `3. Open the Package Management System`) with:

```markdown
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
```

Then renumber the following steps: `4. Setup email` -> `6.`, `5. Select a printer` -> `7.`, `6. Import a CSV file` -> `8.`.

- [ ] **Step 2: Add the Updates and Releasing sections**

Insert immediately before the line `Usage instructions` (after the "Data and backups" section):

```markdown
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
```

- [ ] **Step 3: Set the version to 2.2.0**

In `Package Management System/pom.xml` change `<version>2.1.0</version>` (the project version, line 8) to `<version>2.2.0</version>`.

Run: `cd "Package Management System" && mvn -q clean package && unzip -p target/package-manager-2.2.0-jar-with-dependencies.jar META-INF/MANIFEST.MF | grep Implementation-Version`
Expected: `Implementation-Version: 2.2.0`

- [ ] **Step 4: Check the README is pure ASCII**

Run: `LC_ALL=C grep -n '[^ -~]' README.md || echo "pure ASCII"`
Expected: `pure ASCII` (tabs would also be reported; there are none in the README).

- [ ] **Step 5: Commit**

```bash
git add README.md "Package Management System/pom.xml"
git commit -m "2.2.0: document updates and releasing"
```

- [ ] **Step 6: End-to-end check against the real 2.1.0 release (manual, needs a person to click)**

Build a copy that believes it is 2.0.9, run it with a throwaway home folder so it cannot touch real data or send real email, and let it update to GitHub's 2.1.0.

```bash
cd "Package Management System"
mvn -q versions:set -DnewVersion=2.0.9 -DgenerateBackupPoms=false
mvn -q clean package -DskipTests
git checkout pom.xml
E2E="$(mktemp -d)"
mkdir -p "$E2E/home" "$E2E/My Apps"
cp target/package-manager-2.0.9-jar-with-dependencies.jar "$E2E/My Apps/PackageManager.jar"
echo "$E2E"
java -Duser.home="$E2E/home" -jar "$E2E/My Apps/PackageManager.jar"
```

Expected, clicking through:
1. Dialog "Version 2.1.0 is available. You have version 2.0.9." with the 2.1.0 notes. Click **Later**: the app starts normally (window title "Package Management System 2.0.9"). Quit it.
2. Run the same `java` command again. Click **Skip this version**: the app starts normally. Quit. `grep update_skip_version "$E2E/home/Documents/package_management_system/props/config.properties"` prints `update_skip_version=2.1.0`. Run again: no dialog. Quit, then remove that line from `config.properties`.
3. Run again, click **Update now**: a progress bar fills, the app closes and reopens with the title "Package Management System 2.1.0".
4. Check the files:

```bash
unzip -p "$E2E/My Apps/PackageManager.jar" META-INF/MANIFEST.MF | grep Implementation-Version
# Implementation-Version: 2.1.0
unzip -p "$E2E/My Apps/PackageManager.jar.previous" META-INF/MANIFEST.MF | grep Implementation-Version
# Implementation-Version: 2.0.9
ls "$E2E/My Apps"
# PackageManager.jar  PackageManager.jar.previous   (no .new)
grep -i update "$E2E/home/Documents/package_management_system/"logs/* | tail -5
# shows "Installed version 2.1.0" and "Restarting: [...]" lines
```

5. Offline check: turn Wi-Fi off, restore the 2.0.9 jar (`cp target/package-manager-2.0.9-jar-with-dependencies.jar "$E2E/My Apps/PackageManager.jar"`), run it: no dialog, normal start, log says "Could not check for updates". Turn Wi-Fi back on.

Record the outcome of each numbered check in the branch summary. If the log folder name differs, look under `$E2E/home/Documents/package_management_system/`.

- [ ] **Step 7: Rebuild the real 2.2.0 jar**

The end-to-end build replaced `target/`. Run: `cd "Package Management System" && mvn -q clean package`
Expected: `target/package-manager-2.2.0-jar-with-dependencies.jar` exists.

---

## After the plan (not part of execution)

Merging to `main`, running `./release.sh` for 2.2.0, and the one-time install on the
mailroom iMac (README steps 1-5, then confirming the desktop alias still opens the jar
after the first real update) are done with Michael's approval, not by the executor.
