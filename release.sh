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

# The notes file itself may be an uncommitted file in the repository
here=$(pwd)
case "$notes" in
	"$here"/*) dirty=$(git status --porcelain -- . ":(exclude)${notes#"$here"/}") ;;
	*) dirty=$(git status --porcelain) ;;
esac
[ -z "$dirty" ] || die "commit or stash your changes first"
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
