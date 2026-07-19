package dev.marv.foliacode.verify;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Resolves and downloads Folia server jars from the PaperMC API.
 *
 * <p>Uses the {@code fill.papermc.io/v3} API. The older {@code api.papermc.io/v2} endpoint
 * still responds for Folia but reports no versions, so it cannot be used.</p>
 *
 * <p><strong>Security.</strong> The downloaded jar is executed, so it is treated as the
 * security boundary it is. Downloads are restricted to PaperMC hosts over HTTPS, and every
 * jar is verified against the SHA-256 the API published for it. A mismatch fails loudly and
 * the partial file is deleted rather than cached. The checksum and the URL come from the
 * same response, so the checksum is not a defence against a compromised API; it is a
 * defence against truncated downloads, cache corruption, and a poisoned cache directory,
 * all of which would otherwise hand us a jar we then run.</p>
 */
public final class FoliaDownloader {

    /** PaperMC requires a descriptive User-Agent and rate limits requests that omit one. */
    public static final String USER_AGENT = "foliacode/0.1.0 (github.com/MARVserver/Foliacode)";

    /** Base URL of the PaperMC fill v3 API. */
    public static final String API_BASE = "https://fill.papermc.io/v3/projects/folia";

    /** The download key that identifies the plain server jar. */
    private static final String SERVER_DOWNLOAD_KEY = "server:default";

    /** Hosts we are willing to download an executable jar from. */
    private static final Set<String> ALLOWED_HOSTS =
            Set.of("fill.papermc.io", "fill-data.papermc.io", "api.papermc.io", "papermc.io");

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(10);

    private final Path cacheDirectory;
    private final HttpClient httpClient;

    /** Creates a downloader that caches into {@code ~/.foliacode/cache}. */
    public FoliaDownloader() {
        this(defaultCacheDirectory(), defaultHttpClient());
    }

    /**
     * Creates a downloader with an explicit cache directory and HTTP client.
     *
     * @param cacheDirectory where jars are cached
     * @param httpClient     the client used for all requests
     */
    public FoliaDownloader(Path cacheDirectory, HttpClient httpClient) {
        this.cacheDirectory = Objects.requireNonNull(cacheDirectory, "cacheDirectory");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    /** The default cache directory, {@code ~/.foliacode/cache}. */
    public static Path defaultCacheDirectory() {
        return Path.of(System.getProperty("user.home", "."), ".foliacode", "cache");
    }

    private static HttpClient defaultHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** The directory jars are cached in. */
    public Path cacheDirectory() {
        return cacheDirectory;
    }

    /**
     * Lists every Minecraft version Folia publishes builds for.
     *
     * <p>The API groups versions by major release and lists each group newest first; that
     * order is preserved.</p>
     *
     * @return the available versions, newest first
     * @throws IOException          if the API cannot be reached or returns an unusable body
     * @throws InterruptedException if the calling thread is interrupted
     */
    public List<String> availableVersions() throws IOException, InterruptedException {
        String body = get(API_BASE);
        Object versions = Json.path(Json.parse(body), "versions");
        Map<String, Object> grouped = Json.asObject(versions);
        if (grouped.isEmpty()) {
            throw new IOException("The PaperMC API returned no Folia versions. Response: " + excerpt(body));
        }
        List<String> result = new ArrayList<>();
        for (Object group : grouped.values()) {
            for (Object version : Json.asArray(group)) {
                String text = Json.asString(version);
                if (text != null && !result.contains(text)) {
                    result.add(text);
                }
            }
        }
        return List.copyOf(result);
    }

    /**
     * Resolves the newest build for a Minecraft version.
     *
     * <p>The API returns builds newest first, so the first entry that carries a usable
     * server download wins.</p>
     *
     * @param minecraftVersion the Minecraft version, for example {@code 1.21.4}
     * @return the newest build
     * @throws IOException          if the API cannot be reached or publishes no usable build
     * @throws InterruptedException if the calling thread is interrupted
     */
    public FoliaBuild latestBuild(String minecraftVersion) throws IOException, InterruptedException {
        Objects.requireNonNull(minecraftVersion, "minecraftVersion");
        String body = get(API_BASE + "/versions/" + minecraftVersion + "/builds");
        FoliaBuild build = parseLatestBuild(body, minecraftVersion);
        if (build == null) {
            throw new IOException("No Folia build with a downloadable server jar was found for "
                    + minecraftVersion + ". Available versions: " + availableVersions());
        }
        return build;
    }

    /**
     * Parses the newest usable build out of a builds response.
     *
     * <p>Package private so it can be tested against recorded API responses without network
     * access.</p>
     *
     * @param body             the raw JSON body; may be {@code null}
     * @param minecraftVersion the version the response was requested for
     * @return the newest build, or {@code null} if the body carries none
     */
    static FoliaBuild parseLatestBuild(String body, String minecraftVersion) {
        for (Object entry : Json.asArray(Json.parse(body))) {
            Object download = Json.path(entry, "downloads", SERVER_DOWNLOAD_KEY);
            if (download == null) {
                continue;
            }
            String url = Json.asString(Json.path(download, "url"));
            String sha256 = Json.asString(Json.path(download, "checksums", "sha256"));
            String name = Json.asString(Json.path(download, "name"));
            if (url == null || sha256 == null) {
                continue;
            }
            return new FoliaBuild(
                    Json.asInt(Json.path(entry, "id"), -1),
                    Json.asString(Json.path(entry, "channel")),
                    minecraftVersion,
                    name == null ? "folia-" + minecraftVersion + ".jar" : name,
                    url,
                    sha256.toLowerCase(Locale.ROOT));
        }
        return null;
    }

    /**
     * Downloads the newest Folia build for a version, reusing a cached jar when possible.
     *
     * @param minecraftVersion the Minecraft version, for example {@code 1.21.4}
     * @return the path of the verified jar
     * @throws IOException          if the download fails or the checksum does not match
     * @throws InterruptedException if the calling thread is interrupted
     */
    public Path download(String minecraftVersion) throws IOException, InterruptedException {
        return download(latestBuild(minecraftVersion));
    }

    /**
     * Downloads a specific build, reusing a cached jar when its checksum already matches.
     *
     * @param build the build to fetch
     * @return the path of the verified jar
     * @throws IOException          if the download fails or the checksum does not match
     * @throws InterruptedException if the calling thread is interrupted
     */
    public Path download(FoliaBuild build) throws IOException, InterruptedException {
        Objects.requireNonNull(build, "build");
        requireAllowedHost(build.downloadUrl());

        Files.createDirectories(cacheDirectory);
        Path target = cacheDirectory.resolve(build.cacheFileName());

        if (Files.isRegularFile(target)) {
            String cached = sha256(target);
            if (cached.equalsIgnoreCase(build.sha256())) {
                return target;
            }
            // A cached jar that no longer matches is not trustworthy; replace it.
            Files.deleteIfExists(target);
        }

        Path temp = Files.createTempFile(cacheDirectory, build.cacheFileName(), ".part");
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(build.downloadUrl()))
                    .header("User-Agent", USER_AGENT)
                    .timeout(DOWNLOAD_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<InputStream> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new IOException("Downloading " + build.downloadUrl() + " failed with HTTP "
                        + response.statusCode());
            }
            try (InputStream in = response.body()) {
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            }

            String actual = sha256(temp);
            if (!actual.equalsIgnoreCase(build.sha256())) {
                throw new IOException("SHA-256 mismatch for " + build.fileName()
                        + ". Expected " + build.sha256() + " but downloaded " + actual
                        + ". Refusing to run an unverified server jar.");
            }

            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * Computes the SHA-256 of a file.
     *
     * @param file the file to hash
     * @return the lowercase hex digest
     * @throws IOException if the file cannot be read
     */
    public static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
        byte[] buffer = new byte[8192];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return toHex(digest.digest());
    }

    /** Converts a digest to lowercase hex. */
    static String toHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    /**
     * Rejects download URLs that do not point at PaperMC over HTTPS.
     *
     * @param url the URL to check
     * @throws IOException if the URL is not an acceptable source for an executable jar
     */
    static void requireAllowedHost(String url) throws IOException {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IOException("Malformed download URL: " + url, e);
        }
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || !ALLOWED_HOSTS.contains(host)) {
            throw new IOException("Refusing to download an executable jar from " + url
                    + ". Only HTTPS downloads from PaperMC hosts are allowed.");
        }
    }

    private String get(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("GET " + url + " failed with HTTP " + response.statusCode());
        }
        return response.body();
    }

    private static String excerpt(String body) {
        if (body == null) {
            return "<empty>";
        }
        return body.length() <= 200 ? body : body.substring(0, 200) + "...";
    }
}
