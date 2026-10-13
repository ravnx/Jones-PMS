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
