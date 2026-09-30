# Changelog

All notable changes to this project are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

The section for `pluginVersion` becomes the plugin's change notes and the
GitHub Release notes, so write it before you tag.

## [Unreleased]

## [0.1.0] - 2026-09-30

First release.

### Added

- An editor for `*.csiq`, `*.csiq.zst` and `capture.raw` files.
- Compressed containers: zstd, gzip, xz, bzip2, lz4 frame and lzma. The codec
  comes from the magic bytes, not from the file name.
- Views: waterfall, spectrum, impulse response, records, fields and bytes,
  session.
- Three time axes: wall clock, elapsed, host clock.
- `metadata.json` beside the capture supplies the monitor width of a
  `capture.raw`, the session lifecycle, and a record-count check.
- Remote files through the platform virtual file system, which covers Big Data
  Tools and Remote File Systems mounts. The plugin does not depend on either.
- **Settings | Tools | CSI Captures** for the remote cache threshold and budget.
- `csiq-format-<version>.jar`: the format reader alone, with no IntelliJ
  dependency.

### Known limitations

- Needs IDE build 262 (2026.2) or later. The plugin verifier ran against
  PyCharm 2026.2.3 only.
- Reads CSIQ container version 1 only.
- Not on the JetBrains Marketplace. Install the zip from disk.

[Unreleased]: https://github.com/monad-wireless/csiq-jetbrains/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/monad-wireless/csiq-jetbrains/releases/tag/v0.1.0
