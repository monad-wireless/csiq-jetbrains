# csiq-jetbrains

[![Build](https://github.com/monad-wireless/csiq-jetbrains/actions/workflows/build.yml/badge.svg)](https://github.com/monad-wireless/csiq-jetbrains/actions/workflows/build.yml)

A JetBrains IDE plugin that opens Wi-Fi Channel State Information captures.

It reads the **CSIQ** interchange format and the raw driver streams those
containers are derived from, and it shows them as plots rather than as bytes.
The format specification is
[`docs/CSIQ-format-v1.md` in csid](https://github.com/Sibyx/csid/blob/main/docs/CSIQ-format-v1.md),
and that document is authoritative: where this reader and that document
disagree, this reader is the bug.

## Install

Download `csiq-jetbrains-<version>.zip` from the
[latest release](https://github.com/monad-wireless/csiq-jetbrains/releases/latest),
then **Settings | Plugins | gear | Install Plugin from Disk** and choose the zip.
Each release also carries `csiq-format-<version>.jar`, the standalone format
layer, and a `SHA256SUMS` file.

## What it opens

| Name          | What it is                                            |
|---------------|-------------------------------------------------------|
| `*.csiq`      | a CSIQ container, uncompressed                        |
| `*.csiq.zst`  | a CSIQ container inside a zstd frame                  |
| `capture.raw` | the lossless iax driver stream, matched by exact name |

Those three are what the archive holds. One node's prefix under
`s3://.../datasets/ax210-csi-captures/monad02/` carries 712 `capture.csiq.zst`,
273 `capture.csiq` and 1,001 `capture.raw`, each beside a `metadata.json`.

**Compression is a separate axis from content.** The specification compresses
the *file* and never the format, so a `.zst` is a zstd frame around an unchanged
CSIQ byte stream. Nothing in that argument is specific to zstd, so any of these
codecs is expanded and the container inside is read normally:

| Codec     | Extensions       | Decoder                                              |
|-----------|------------------|------------------------------------------------------|
| zstd      | `.zst`, `.zstd`  | zstd-jni, bundled                                    |
| gzip      | `.gz`, `.gzip`   | commons-compress, concatenated members expanded      |
| xz        | `.xz`            | commons-compress + xz, concatenated members expanded |
| bzip2     | `.bz2`, `.bzip2` | commons-compress                                     |
| lz4 frame | `.lz4`           | commons-compress                                     |
| lzma      | `.lzma`          | commons-compress                                     |

All three decoder libraries are bundled rather than taken from the IDE, which
happens to ship all of them: a platform library is not part of the plugin API
contract and can be dropped between releases.

The envelope is decided by **sniffing the first bytes**, not by the extension,
and the payload inside a compressed file is sniffed after expansion. When the
name and the bytes disagree, the bytes win and the editor says which is which.
A codec with no decoder is refused by name, which is what the specification asks
a reader to do rather than reporting a corrupt container.

Only container version 1 exists. A file declaring another version is refused
with the version it declared, because the specification makes the version an
equality test rather than a floor.

## The session directory

A capture is not a file in isolation. The archive stores one directory per
capture session, on disk and in S3 alike, and the plugin reads the whole of it.

`metadata.json` is found through the virtual file system, so it works on S3
exactly as on disk, and it supplies three things the capture cannot.

1. **The monitor width.** The 272-byte driver header has no width field, because
   width is a session constant. A `capture.raw` opened on its own can only
   report "unknown"; opened beside its sidecar it reports the real width, and
   the editor says which of the two happened.
2. **The real lifecycle.** A container written before csid 0.2.0 embeds
   `status: capturing` forever. When the sidecar disagrees, the editor shows the
   sidecar's lifecycle and flags the embedded copy as stale.
3. **A cross-check.** `summary.records` against the records this reader finds. A
   mismatch is what a half-uploaded object looks like, so it is shown as a
   critical tile and in the status bar rather than left to be noticed.

A segment directory (`-segNNNN`) is named as such in the header, with the base
session it belongs to.

## The views

**Waterfall.** Amplitude over subcarrier and time, for the whole capture.
Ctrl-scroll zooms into a range of records, which is read from the file at full
resolution. Double-click returns to the whole capture.

The x axis can show any of three clocks, and the toolbar picks between them.
Every view follows the choice, so the table's time column never disagrees with
the waterfall's axis.

| Axis | What it is |
|---|---|
| wall clock | absolute time: an anchor, advanced by the baseband clock |
| elapsed | seconds since the first record, on the baseband clock |
| host clock | `unix_ts_ns` as stored, which an NTP step moves |

**Wall clock is the default** when the capture carries an absolute time. It is
built the way the specification says to build it: *analyse on `ftm`, anchor
wallclock on `unix_ts_ns`*. The anchor fixes where the capture sits in the day,
and every offset inside it comes from the 320 MHz baseband clock, which is
stamped in the RF plane before any host software runs and which an NTP step
cannot move. The anchor is the first record's own `unix_ts_ns`; a raw driver
stream with no host stamps falls back to `started_at` from `metadata.json`, and
a capture with neither gets an elapsed axis rather than an invented origin. The
axis always names its anchor and its time zone.

Tick labels are read from the record under each tick, never interpolated
between the two ends. Time is not linear in the x axis — overview columns are
spaced by byte position and records are not all the same size — so an
interpolated clock label would be close and wrong.

Keeping the two clocks apart pays for itself in the cross-check. Oscillator
drift between them measures in milliseconds: 2 ms over three minutes and 56 ms
over twelve hours on this fleet, about one part per million. A jump of seconds
is not drift, it is an NTP step, and on a fleet with no RTC that is the one
failure which moves timestamps under a running capture and leaves no other mark
in the file. The Session view reports the disagreement, and anything past half a
second is raised in the status bar.

The tone axis changes between records. A legacy frame carries 52 subcarriers, an
HT frame 56, an HE20 frame 242, and an ambient channel interleaves them frame by
frame. There is no grid all three fit on, so the view keeps one image per tone
count and draws the records of other geometries as gaps. It does not
interpolate, because that would invent subcarriers, and it does not paint a gap
as a low amplitude, because an absence of measurement is not a weak signal.

**Spectrum.** One record's amplitude and unwrapped phase per tone, every chain
on one pair of axes. A chain that reported the no-measurement sentinel is drawn
dashed and named in the legend: its CSI is a byte-identical copy of an earlier
frame, so hiding it would be wrong and drawing it as an equal would be worse.

Phase can be detrended, which removes the linear fit. That fit is carrier
frequency offset. It is a display aid and never a calibration.

**Impulse response.** The channel impulse response per chain, and the
inter-chain conjugate product. Both exist to catch the one defect that is
invisible in amplitude.

The panel prints the early-to-late tap energy ratio. A real channel puts its
energy at early delays, and on this hardware the correct byte order measures
about 21 while reading the coefficients real-first inverts it to about 0.5. A
ratio below one is drawn in red, because an anti-causal channel is impossible
and means a reader has the order wrong.

**Records.** Every record's clocks, RSSI, PHY label, source MAC and geometry.
The table reads the index rather than the file, so scrolling a capture of a
million records touches no bytes and issues no request to an object store. The
notes column names the facts that change how a record may be used: all-zero CSI,
a stale chain, an own transmission, a geometry mismatch.

**Fields and bytes.** Each TLV with the specification's own wording for what its
value means, a hex view of the field, and the 272-byte driver header decoded at
the offsets Appendix A documents. A type code this reader does not implement is
shown with whatever the format reserves that code for, rather than as a number.

**Session.** The embedded session block as a tree, and above it what the capture
says about itself: record count, duration on both clocks, mean rate, dropped
reports, all-zero share, stale-chain share, and how the stream ended. Every
number there is counted rather than read out of the session block, so it
describes the bytes and not the capturer's intention.

## Remote captures, Big Data Tools and Remote File Systems

**No dependency on either plugin is declared, and none is needed.**

Those plugins mount S3, HDFS, SFTP and Azure storage as ordinary entries in the
platform's virtual file system. A viewer that reads through `VirtualFile` rather
than through a filesystem path therefore works on a remote capture the day it
works on a local one, with nothing to keep in step when their APIs change.

Three rules make that true, and `VirtualFileByteSource` enforces all three.

1. `contentsToByteArray` is never called. The platform refuses it above about
   20 MB and a capture is routinely a hundred times that.
2. A virtual file is turned into a path only when it really is local.
3. The source states whether seeking is cheap. A local file gets true random
   access. A remote object does not, and every reader here has a sequential
   path for that case.

Indexing therefore costs **one pass**, which is one request rather than one per
record. `RemoteVirtualFileTest` proves both claims against a fake filesystem
that reports itself as `s3`, hands out a forward-only stream, and fails the test
if anything asks for the whole file at once.

A remote capture is copied into a local cache before indexing, so that later
jumps to a record do not re-read from the start of the stream. The threshold and
the cache budget are under **Settings | Tools | CSI Captures**. A `.csiq.zst` is
always expanded into that cache, because a compressed stream cannot be seeked.

The plugin IDs, if you want to install them: `com.intellij.bigdatatools.rfs`
(Remote File Systems), `com.intellij.bigdatatools` (Big Data Tools).

## Building

The build needs a **JDK 25**, because PyCharm 2026.2 ships platform classes at
class-file major 69 and a compiler must be able to read them. The emitted
bytecode targets 21. A JetBrains Runtime from any installed IDE satisfies this
and is already listed in `gradle.properties`, so nothing is downloaded.

```bash
./gradlew build          # compile, test, assemble
./gradlew buildPlugin    # build/distributions/csiq-jetbrains-<version>.zip
./gradlew verifyPlugin   # JetBrains plugin verifier, against the installed IDE
./gradlew runIde         # a sandbox IDE with the plugin loaded
```

The platform is resolved from the locally installed PyCharm by default, which is
an exact match with the IDE that will run the plugin and avoids a multi-gigabyte
download. When that path does not exist, the build downloads `platformType` and
`platformVersion` instead (PyCharm 2026.2.3, the same build as the reference
IDE). Pass `-PplatformLocalPath=` to force the download on a machine that has
PyCharm installed.

To install the built plugin: **Settings | Plugins | gear | Install Plugin from
Disk**, then choose the zip.

## CI and releases

Two GitHub Actions workflows live in `.github/workflows/`.

| Workflow      | Trigger                          | What it does                                                   |
|---------------|----------------------------------|----------------------------------------------------------------|
| `build.yml`   | push to `master`, pull request   | compile, test, package, plugin verifier; uploads zip and reports |
| `release.yml` | tag `v*`                         | the same, then a GitHub Release with zip, format jar, checksums |

Both run on JDK 25 and download the platform, so CI never depends on an
installed IDE. The test reports artifact includes the PNGs the render tests
write, which is how a change to the plot layout is reviewed.

To cut a release, set `pluginVersion` in `gradle.properties`, merge it, then
push a tag with the same version:

```bash
git tag v0.1.0
git push origin v0.1.0
```

The release workflow refuses a tag that does not match `pluginVersion`. A
version with a suffix, such as `0.2.0-rc.1`, is published as a pre-release.

## Which IDEs

The plugin depends on `com.intellij.modules.platform` and nothing else, so it
installs into PyCharm, IntelliJ IDEA Community and Ultimate, CLion, RustRover,
GoLand and DataGrip.

`sinceBuild` is **262**, which is the branch the plugin was compiled against and
the only one the verifier has been run against. It is deliberately not lower: a
compatibility claim nobody has checked is not worth making. To widen it, run the
verifier against the older branch first.

## Design

Colour is assigned by the job it does, and the categorical palette was checked
with a colourblind-separation validator rather than by eye.

- **Chains are categorical.** Blue, orange, aqua, in fixed order, never cycled.
  Validated all-pairs in both light and dark: worst colour-vision separation
  dE 9.2 light and 9.4 dark, worst normal-vision separation dE 24.0 and 20.9.
  Dark is a selected set of steps for the dark surface, not an inverted light
  one.
- **Amplitude is sequential.** Viridis by default, with Magma and a grey ramp as
  alternatives. All three are monotonic in lightness, so equal steps in
  amplitude look like equal steps on screen. Viridis is the default because it
  is what the wireless-sensing literature and this project's own Python plots
  use, so a figure in the IDE and a figure in a notebook read the same.
- **Status colours are reserved** for good, warning and critical, never reused
  as a fourth series, and every status also says its reason in words.
- **Identity never rests on colour alone.** A chain with no measurement is
  dashed as well as named. Every series is direct-labelled in the legend.
- **Text wears text colours.** A value is never tinted by its status; the accent
  goes in a rule down the tile's left edge or in a chip's dot.
- **The grid is recessive.** Dotted, about a tenth of the ink's weight, with a
  baseline and a left rule instead of a box.
- **A gap is hatched, not blank.** A waterfall column no record landed in is
  drawn with a diagonal hatch, because transparency alone reads as the darkest
  end of the colour map.

## Tests

```bash
./gradlew test
```

41 tests, none of which needs a running IDE.

`CsiqParityTest` checks this reader field by field against the **Python
reference reader** ([`python/csiq` in csid](https://github.com/Sibyx/csid/tree/main/python/csiq)), on 24 records cut verbatim out of
a capture taken on monad02. The fixture and the expectation beside it are in
`src/test/resources/fixtures/`. It also checks the two layout properties that
fail silently: that chain 0 is read chain-major and imaginary-first, and that
the resulting impulse response is causal on real data.

`RemoteVirtualFileTest` checks the remote-filesystem claims above.

`EnvelopeTest` checks every codec extension and magic number, and that a name
which lies about its codec is reported rather than obeyed.

`SessionSidecarTest` reads a real `metadata.json` taken from the archive,
because the one fact that file exists to supply is the one a `capture.raw`
cannot carry. It is verbatim except for the bystander transmitter MACs in
`summary.transmitters.top`, which are replaced by the RFC 7042 documentation
range `00:00:5e:00:53:xx`.

`WaterfallImageTest` checks the amplitude-to-pixel mapping: that a gap stays
transparent, and that an outlier does not flatten the image into one shade.

`PlotChromeTest` paints the shared plot furniture into an image, so a label
collision or a mark drawn outside its area fails a test rather than an editor
tab. The PNGs land in `build/reports/chrome/` and `build/reports/waterfall/`.

Two environment variables bring real data in. Without them the suite runs on the
checked-in fixtures alone.

```bash
# a directory of .csiq files: renders the largest as a waterfall
CSIQ_TEST_CAPTURES=/path/to/captures ./gradlew test

# one archive session directory: expands its .csiq.zst, indexes it, and
# checks the record count against the sidecar's claim
CSIQ_TEST_SESSION=/path/to/<host>_<profile>_<stamp> ./gradlew test
```

## Measured

| Fact                                             | Value                                                      |
|--------------------------------------------------|------------------------------------------------------------|
| Index pass, 604 MB local capture                 | 1,128,956 records in 886 ms                                |
| Record count against the Python reference reader | identical                                                  |
| Index memory                                     | about 50 bytes a record, so roughly 57 MB for that capture |
| A real `capture.csiq.zst` from S3                | 3.09 MB expands to 8.20 MB, 9,987 records                  |
| That capture's count against its `metadata.json` | 9,987 claimed, 9,987 found                                 |
| Clock disagreement, 180 s capture                | 2 ms                                                       |
| Clock disagreement, 43,200 s capture             | 56 ms, about 1 ppm                                         |
| Largest record gap, both                         | 0.8 s and 2.0 s, against a 13.42 s wrap                    |
| Plugin verifier against PY-262.10968.92          | Compatible                                                 |

The clock figures were measured twice, once by this reader and once by a script
over the Python reference reader. They agree to the microsecond.

The index holds one row per record as a structure of arrays and never retains a
CSI matrix. That is what makes a capture of this size open at all: the matrices
are all of the bytes, and they are decoded, used for the overview, and dropped.

## Layout

```
src/main/kotlin/io/monadcount/csiq/
  format/   the CSIQ codec. No IntelliJ dependency, no I/O assumptions.
  model/    the record index, the waterfall accumulator, capture health.
  ide/      virtual-file access, caching, the file type, the editor.
  ide/ui/   the Java2D views.
```

`format/` is deliberately standalone, down to its own small JSON reader. The
format's promise is that a capture is interpretable by someone who has only the
file and the specification, and a reader with a dependency list does not keep
it. `./gradlew formatJar` builds that layer on its own.

## License

MIT. See [LICENSE](LICENSE).
