#!/usr/bin/env python3

import argparse
import datetime
import pathlib
import re
import sys


HEADING_RE = re.compile(r"^## \[(?P<version>[^\]]+)\](?: - (?P<date>\d{4}-\d{2}-\d{2}|TBD|Unreleased))?\s*$")
VERSION_CODE_RE = re.compile(r"(?m)^(\s*versionCode\s+)\d+\s*$")
VERSION_NAME_RE = re.compile(r'(?m)^(\s*versionName\s+")[^"]+("\s*)$')


def read_text(path):
    return path.read_text(encoding="utf-8")


def write_text(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def find_version_section(lines, version):
    start = None
    for index, line in enumerate(lines):
        match = HEADING_RE.match(line)
        if match and match.group("version") == version:
            start = index
            break
    if start is None:
        raise ValueError(f"CHANGELOG.md does not contain a section for version {version}")

    end = len(lines)
    for index in range(start + 1, len(lines)):
        if lines[index].startswith("## "):
            end = index
            break
    return start, end


def latest_version(lines):
    for line in lines:
        match = HEADING_RE.match(line)
        if match:
            return match.group("version")
    raise ValueError("CHANGELOG.md does not contain any version sections")


def update_section_date(lines, start, release_date):
    match = HEADING_RE.match(lines[start])
    if not match:
        raise ValueError("Version heading is not in the expected changelog format")
    version = match.group("version")
    lines[start] = f"## [{version}] - {release_date}"


def section_body(lines, start, end):
    body = "\n".join(lines[start + 1:end]).strip()
    return body if body else "- No release notes provided."


def release_notes(version, body, full_changelog_url):
    notes = [body]
    if full_changelog_url:
        notes.extend(["", f"Full changelog: {full_changelog_url}"])
    return "\n".join(notes).rstrip() + "\n"


def fastlane_text(body):
    body = re.sub(r"^### .*$", "", body, flags=re.MULTILINE)
    body = body.replace("`", "")
    body = re.sub(r"\n{3,}", "\n\n", body).strip()
    return body + "\n"


def replace_once(pattern, replacement, content, path):
    content, count = pattern.subn(replacement, content, count=1)
    if count != 1:
        raise ValueError(f"Expected exactly one match in {path}")
    return content


def update_android_version(path, version, version_code):
    content = read_text(path)
    content = replace_once(VERSION_CODE_RE, rf"\g<1>{version_code}", content, path)
    content = replace_once(VERSION_NAME_RE, rf"\g<1>{version}\2", content, path)
    write_text(path, content)


def main():
    parser = argparse.ArgumentParser(description="Prepare changelog release metadata.")
    parser.add_argument("--version", help="Version section to release, for example 0.1.0")
    parser.add_argument("--latest", action="store_true", help="Use the first version section in CHANGELOG.md")
    parser.add_argument("--print-version", action="store_true", help="Print the resolved version and exit")
    parser.add_argument("--date", default=datetime.date.today().isoformat())
    parser.add_argument("--changelog", default="CHANGELOG.md")
    parser.add_argument("--app-build-gradle", default="app/build.gradle")
    parser.add_argument("--version-code", type=int, help="Android versionCode to write to app/build.gradle")
    parser.add_argument("--write", action="store_true", help="Write the release date back to CHANGELOG.md")
    parser.add_argument("--release-notes", help="Write release notes markdown for GitHub Releases")
    parser.add_argument("--full-changelog-url", default="")
    parser.add_argument("--fastlane-changelog", help="Write Fastlane changelog text for this version code")
    parser.add_argument("--fastlane-changelogs-dir", default="fastlane/metadata/android/en-US/changelogs")
    args = parser.parse_args()

    changelog_path = pathlib.Path(args.changelog)
    lines = read_text(changelog_path).splitlines()
    version = latest_version(lines) if args.latest else args.version
    if not version:
        parser.error("--version is required unless --latest is used")
    if args.print_version:
        print(version)
        return 0
    if args.write and args.version_code is None:
        parser.error("--version-code is required with --write")

    start, end = find_version_section(lines, version)

    if args.write:
        update_section_date(lines, start, args.date)
        write_text(changelog_path, "\n".join(lines).rstrip() + "\n")
        lines = read_text(changelog_path).splitlines()
        start, end = find_version_section(lines, version)
        if args.version_code is not None:
            update_android_version(pathlib.Path(args.app_build_gradle), version, args.version_code)

    body = section_body(lines, start, end)
    if args.write and args.version_code is not None and not args.fastlane_changelog:
        args.fastlane_changelog = str(pathlib.Path(args.fastlane_changelogs_dir) / f"{args.version_code}.txt")
    if args.release_notes:
        write_text(pathlib.Path(args.release_notes), release_notes(version, body, args.full_changelog_url))
    if args.fastlane_changelog:
        write_text(pathlib.Path(args.fastlane_changelog), fastlane_text(body))

    return 0


if __name__ == "__main__":
    sys.exit(main())
