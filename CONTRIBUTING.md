# Contributing to FoliaCode

Thanks for helping. FoliaCode is only as good as its rule set and the evidence
behind it, so contributions of either kind are welcome.

---

## Getting set up

Requires **Java 21+**. Nothing else.

```bash
git clone https://github.com/MARVserver/Foliacode.git
cd Foliacode
./gradlew build
```

`./gradlew test` must stay fast, offline and green. Anything that needs the network
or boots a server is tagged `integration` and excluded from the default run:

```bash
./gradlew :foliacode-verify:integrationTest
```

---

## Adding a rule

This is the most valuable contribution, and usually the smallest.

Every rule lives in one place:
`foliacode-core/src/main/java/dev/marv/foliacode/rules/UnsafeApiRegistry.java`

```java
rules.add(new UnsafeApi(
        "org/bukkit/entity/Entity",   // owner, internal name
        "teleport",                   // method name, or UnsafeApi.ANY_METHOD
        null,                         // descriptor, or null for any
        Category.ENTITY,
        Severity.HIGH,
        "A synchronous teleport cannot be performed safely across regions.",
        "Use teleportAsync(Location) and continue without awaiting the future."));
```

### Choosing a severity

Grade by **what actually happens on Folia**, not by how risky it feels.

| Severity | Use when |
|---|---|
| `CRITICAL` | Folia throws. The plugin certainly does not work. |
| `HIGH` | Thread-safety violation when called from outside the owning region. |
| `MEDIUM` | Whether it breaks depends on the calling thread context. |
| `INFO` | Static analysis genuinely cannot decide (reflection, dynamic dispatch). |

If you are unsure between two levels, pick the lower one. A report people trust and
read beats a report that cries wolf and gets ignored.

### Writing the reason and remedy

Both strings are shown directly to users, so write them as documentation:

- **`reason`** — why Folia rejects or breaks on this. Name the mechanism.
- **`remedy`** — what to do instead. Name the actual replacement API.

Avoid "this is unsafe" with no explanation. Someone has to act on this.

### Then add a test

Rules without tests get silently broken later. Add a case to
`ClassScannerTest` or `JarAnalyzerTest` that compiles real source using the API and
asserts the finding. If the API is not in `BukkitStubs`, add the minimal stub for it.

---

## How the tests work

Hand-assembled bytecode makes it easy to write a test that passes while the tool
fails on real plugins. So tests compile **real Java with javac** and analyse the
resulting `.class` files:

```java
List<Finding> findings = scan(tempDir, "example.Direct", """
        package example;
        import org.bukkit.Material;
        import org.bukkit.block.Block;
        public class Direct {
            public void clear(Block block) {
                block.setType(Material.AIR);
            }
        }
        """);
```

`JavaSourceCompiler`, `BukkitStubs` and `JarBuilder` live in
`foliacode-core/src/testFixtures/` and are available to every module.

**Patterns found in real plugins become regression tests.** If you fix a miss that a
real plugin exposed, encode that exact shape — see
`JarAnalyzerTest#detectsCallOnBukkitRunnableSubclass`, which came from EssentialsX.

---

## Reporting a false positive or a miss

These are the most useful bug reports. Please include:

- the plugin and version (a public download link if possible)
- the FoliaCode output, ideally `--json`
- what you expected instead

A miss that can be reproduced from a public JAR will normally become a regression
test as part of the fix.

---

## Code style

Match what is already there rather than importing your own conventions.

- Javadoc on every public type and member, explaining *why* where it is not obvious.
- Records for data, `final` classes for behaviour.
- No new runtime dependencies without discussion. `foliacode-core` deliberately
  avoids a JSON library so it can never conflict with a plugin being analysed, and
  `foliacode-verify` stays dependency-free because it shares a machine with
  downloaded server code.
- Handle hostile input defensively. Every JAR analysed is untrusted: YAML is parsed
  with `SafeConstructor`, archive extraction is size-capped, and a single broken
  class never aborts a run.

---

## Security-sensitive areas

Take extra care in these, and say so in the PR:

- **`FoliaDownloader`** — downloads a server jar that then gets executed. The
  SHA-256 check against PaperMC's published checksum is a security control, not a
  nicety. Do not weaken or bypass it.
- **`ServerVerifier`** — must never run unless explicitly enabled, and must always
  tear the sandbox down, including on interruption.
- **`JarAnalyzer`** — parses untrusted archives. Keep the size caps.

---

## Pull requests

1. One logical change per PR.
2. `./gradlew build` passes.
3. New behaviour has a test.
4. User-facing strings are in English.

Ecosystem note: FoliaCode is an independent tool in the
[MARV](https://github.com/MARVserver) ecosystem, built for the PaperMC/Folia
ecosystem. It is not affiliated with or endorsed by PaperMC.
