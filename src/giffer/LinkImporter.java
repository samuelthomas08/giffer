package giffer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a web link into GIF bytes: Tenor / Giphy / Imgur pages, direct .gif links, or any page that names its
 * GIF in {@code og:image}. Also extracts the page title and URL slug as a hint for the analysis.
 */
final class LinkImporter {
    private LinkImporter() {}

    /** A downloaded GIF plus what we learned about where it came from. */
    record ImportedGif(byte[] gifBytes, String sourceUrl, String suggestedFileName, String sourceHint) {}

    private static final int MAX_GIF_BYTES = 64 * 1024 * 1024;
    private static final int MAX_HTML_BYTES = 3 * 1024 * 1024;
    private static final int MAX_REDIRECTS = 6;
    private static final int MAX_IMAGE_CANDIDATES = 6;
    private static final String USER_AGENT = "Giffer/1.0 (personal GIF library tool)";

    private static final Pattern URL_IN_TEXT = Pattern.compile("(?i)\\bhttps?://[^\\s<>\"']+");
    private static final Pattern BARE_DOMAIN_LINE = Pattern.compile("(?i)^(www\\.)?[a-z0-9-]+(\\.[a-z0-9-]+)+/\\S*$");
    private static final Pattern BARE_DOMAIN = Pattern.compile("(?i)^[a-z0-9.-]+\\.[a-z]{2,}(/.*)?$");

    /** Result of one request: GIF bytes, an HTML document, or neither (e.g. a JPEG). */
    private record FetchedContent(byte[] gifBytes, String htmlText, URI finalUri) {}

    // ---------------------------------------------------------------- URL helpers

    /** Validates a user-supplied link and returns it in ASCII form. */
    static String normalizeUrl(String rawLink) throws IOException {
        String link = stripBracketsAndPunctuation(rawLink == null ? "" : rawLink.strip());
        if (!link.contains("://")) {
            if (BARE_DOMAIN.matcher(link).matches()) link = "https://" + link;
            else throw new IOException("Das sieht nicht wie ein Link aus: " + abbreviate(link));
        }
        try {
            URI parsed = new URI(link.replace(" ", "%20"));
            parsed = new URI(parsed.toASCIIString());
            String scheme = parsed.getScheme();
            boolean isWebLink = scheme != null && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"));
            if (!isWebLink) throw new IOException("Nur http(s)-Links werden unterstützt");
            if (parsed.getHost() == null) throw new IOException("Link ohne Host: " + abbreviate(link));
            return parsed.toString();
        } catch (URISyntaxException invalidSyntax) {
            throw new IOException("Ungültiger Link: " + abbreviate(link));
        }
    }

    /** Finds all links in free text (pasted lines, clipboard, dropped text). */
    static List<String> extractUrls(String text) {
        Set<String> links = new LinkedHashSet<>();
        if (text == null) return new ArrayList<>();
        Matcher matcher = URL_IN_TEXT.matcher(text);
        while (matcher.find()) links.add(stripBracketsAndPunctuation(matcher.group()));
        if (links.isEmpty()) {
            for (String line : text.split("\\R")) {
                String strippedLine = line.strip();
                if (BARE_DOMAIN_LINE.matcher(strippedLine).matches()) links.add("https://" + stripBracketsAndPunctuation(strippedLine));
            }
        }
        return new ArrayList<>(links);
    }

    private static String stripBracketsAndPunctuation(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && "<(['\"".indexOf(text.charAt(start)) >= 0) start++;
        while (end > start && ".,;:!?)]}>'\"".indexOf(text.charAt(end - 1)) >= 0) end--;
        return text.substring(start, end);
    }

    // ---------------------------------------------------------------- fetching

    static ImportedGif fetch(String rawLink) throws IOException {
        String normalizedLink = normalizeUrl(rawLink);
        URI startUri = URI.create(normalizedLink);
        // A link the user points at their own LAN/localhost is allowed; links found *inside* pages never are.
        boolean startIsPrivate = isPrivateHost(startUri.getHost());

        String slugWords = slugWords(startUri);
        String fileNameFromSlug = fileNameFor(slugWords, null);

        // 1) well-known sites: the media URL follows from the page URL
        for (URI derivedMediaUri : derivedMediaUris(startUri)) {
            try {
                FetchedContent content = fetchContent(derivedMediaUri, startIsPrivate);
                if (content.gifBytes() != null) return new ImportedGif(content.gifBytes(), normalizedLink, fileNameFromSlug, slugWords);
            } catch (IOException unreachableGuess) {
                // fall through to the page itself
            }
        }

        // 2) the link itself
        FetchedContent startContent = fetchContent(startUri, startIsPrivate);
        if (startContent.gifBytes() != null) return new ImportedGif(startContent.gifBytes(), normalizedLink, fileNameFromSlug, slugWords);
        if (startContent.htmlText() == null) throw new IOException("Unter diesem Link liegt weder ein GIF noch eine Webseite.");

        // 3) a web page: follow og:image and friends
        HtmlPage page = HtmlPage.parse(startContent.htmlText(), startContent.finalUri());
        String pageTitle = cleanTitle(page.title());
        String sourceHint = combineHint(pageTitle, slugWords);
        String fileNameFromPage = fileNameFor(slugWords, pageTitle);
        IOException lastFailure = null;
        for (URI candidate : page.imageCandidates()) {
            try {
                FetchedContent content = fetchContent(candidate, startIsPrivate);
                if (content.gifBytes() != null) return new ImportedGif(content.gifBytes(), normalizedLink, fileNameFromPage, sourceHint);
            } catch (IOException candidateFailure) {
                lastFailure = candidateFailure;
            }
        }
        String reason = lastFailure != null ? " (" + lastFailure.getMessage() + ")" : "";
        throw new IOException("Auf der Seite wurde kein GIF gefunden" + reason + ".");
    }

    // HttpURLConnection instead of java.net.http.HttpClient: the latter needs an NIO Selector, which
    // fails to start on some Windows setups ("Unable to establish loopback connection").
    private static FetchedContent fetchContent(URI startUri, boolean allowPrivateHosts) throws IOException {
        URI currentUri = startUri;
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            checkHost(currentUri, allowPrivateHosts);
            HttpURLConnection connection = (HttpURLConnection) currentUri.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(30_000);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            // Deliberately no "text/html": Tenor's CDN then answers with an HTML wrapper page instead of the GIF.
            connection.setRequestProperty("Accept", "image/gif,image/*;q=0.9,*/*;q=0.5");
            connection.setRequestProperty("Accept-Language", "en,de;q=0.8");
            try {
                int statusCode = connection.getResponseCode();
                boolean isRedirect = statusCode >= 300 && statusCode < 400;
                if (isRedirect) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Weiterleitung ohne Ziel");
                    currentUri = resolveRedirect(currentUri, location);
                    continue;
                }
                if (statusCode != 200) throw new IOException("HTTP " + statusCode);
                try (InputStream body = connection.getInputStream()) {
                    byte[] firstBytes = body.readNBytes(8);
                    if (startsWithGifSignature(firstBytes)) {
                        return new FetchedContent(readRemaining(body, firstBytes, MAX_GIF_BYTES, true), null, currentUri);
                    }
                    String contentType = String.valueOf(connection.getContentType()).toLowerCase(Locale.ROOT);
                    boolean isHtml = contentType.contains("html") || contentType.contains("xml") || startsWithTag(firstBytes);
                    if (isHtml) {
                        byte[] htmlBytes = readRemaining(body, firstBytes, MAX_HTML_BYTES, false);
                        return new FetchedContent(null, new String(htmlBytes, charsetOf(contentType)), currentUri);
                    }
                    return new FetchedContent(null, null, currentUri);
                }
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("Zu viele Weiterleitungen");
    }

    private static URI resolveRedirect(URI currentUri, String location) throws IOException {
        try {
            return currentUri.resolve(new URI(location.replace(" ", "%20")).toASCIIString());
        } catch (URISyntaxException invalidLocation) {
            throw new IOException("Ungültige Weiterleitung");
        }
    }

    private static boolean startsWithGifSignature(byte[] bytes) {
        return bytes.length >= 4 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == '8';
    }

    private static boolean startsWithTag(byte[] bytes) {
        for (byte current : bytes) {
            if (current == ' ' || current == '\n' || current == '\r' || current == '\t') continue;
            return current == '<';
        }
        return false;
    }

    private static Charset charsetOf(String contentType) {
        Matcher charsetMatcher = Pattern.compile("charset=([\\w-]+)").matcher(contentType);
        if (charsetMatcher.find()) {
            try {
                return Charset.forName(charsetMatcher.group(1));
            } catch (RuntimeException unknownCharset) {
                // fall back to UTF-8
            }
        }
        return StandardCharsets.UTF_8;
    }

    /** Reads {@code alreadyRead} plus the rest of the stream; too much data either fails or is cut off. */
    private static byte[] readRemaining(InputStream body, byte[] alreadyRead, int limitBytes, boolean failIfLarger) throws IOException {
        ByteArrayOutputStream collected = new ByteArrayOutputStream(Math.max(alreadyRead.length, 64 * 1024));
        collected.write(alreadyRead);
        byte[] buffer = new byte[64 * 1024];
        int readCount;
        while ((readCount = body.read(buffer)) > 0) {
            if (collected.size() + readCount > limitBytes) {
                if (failIfLarger) throw new IOException("Datei ist zu groß (über " + limitBytes / (1024 * 1024) + " MB)");
                collected.write(buffer, 0, limitBytes - collected.size());
                break;
            }
            collected.write(buffer, 0, readCount);
        }
        return collected.toByteArray();
    }

    // ---------------------------------------------------------------- network safety

    private static void checkHost(URI uri, boolean allowPrivateHosts) throws IOException {
        String scheme = uri.getScheme();
        boolean isWebLink = scheme != null && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"));
        if (!isWebLink) throw new IOException("Nur http(s)-Links werden unterstützt");
        String host = uri.getHost();
        if (host == null) throw new IOException("Link ohne Host");
        if (!allowPrivateHosts && isPrivateHost(host)) {
            throw new IOException("Ein Link verweist auf eine lokale Adresse und wurde blockiert");
        }
    }

    private static boolean isPrivateHost(String host) {
        if (host == null) return false;
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                boolean isLocal = address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress() || address.isMulticastAddress();
                boolean isUniqueLocalIpv6 = address instanceof Inet6Address && (address.getAddress()[0] & 0xfe) == 0xfc; // fc00::/7
                if (isLocal || isUniqueLocalIpv6) return true;
            }
        } catch (UnknownHostException unresolvable) {
            return false; // the request itself will fail with a clear message
        }
        return false;
    }

    // ---------------------------------------------------------------- site knowledge

    /** Media URLs that can be derived from the page URL without parsing the page (Giphy, Imgur). */
    private static List<URI> derivedMediaUris(URI pageUri) {
        List<URI> derived = new ArrayList<>();
        String host = pageUri.getHost() == null ? "" : pageUri.getHost().toLowerCase(Locale.ROOT);
        String path = pageUri.getPath() == null ? "" : pageUri.getPath();
        if (host.equals("giphy.com") || host.equals("www.giphy.com")) {
            Matcher giphyId = Pattern.compile("/(?:gifs|stickers|clips|embed)/(?:[^/]*-)?([A-Za-z0-9]{8,})(?:/|$)").matcher(path);
            if (giphyId.find()) derived.add(URI.create("https://media.giphy.com/media/" + giphyId.group(1) + "/giphy.gif"));
        } else if (host.equals("imgur.com") || host.equals("www.imgur.com") || host.equals("m.imgur.com")) {
            Matcher imgurId = Pattern.compile("^/([A-Za-z0-9]{5,8})(?:\\.\\w+)?/?$").matcher(path);
            if (imgurId.find()) derived.add(URI.create("https://i.imgur.com/" + imgurId.group(1) + ".gif"));
        }
        return derived;
    }

    /** Words from the URL slug, e.g. ".../view/clapping-clap-applause-gif-15648815" gives "clapping clap applause". */
    private static String slugWords(URI uri) {
        String path = uri.getPath();
        if (path == null || path.isEmpty()) return "";
        String lastSegment = URLDecoder.decode(path.substring(path.lastIndexOf('/') + 1).replace("+", "%2B"), StandardCharsets.UTF_8);
        lastSegment = lastSegment.replaceFirst("(?i)\\.(gif|gifv|mp4|webm|webp)$", "");
        lastSegment = lastSegment.replaceFirst("(?i)-gif-\\d+$", "").replaceFirst("(?i)-gif$", "");
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (host.endsWith("giphy.com")) lastSegment = lastSegment.replaceFirst("-[A-Za-z0-9]{8,}$", "");
        if (!lastSegment.matches("[\\p{L}\\p{N}_-]+")) return "";

        String words = lastSegment.replace('-', ' ').replace('_', ' ').strip();
        if (words.matches("[\\p{N} ]*")) return "";
        // random ids such as "AbCd12345" are not hints
        boolean looksLikeRandomId = !words.contains(" ") && words.length() > 8 && words.matches("(?=.*\\p{N})(?=.*\\p{L}).*");
        return looksLikeRandomId ? "" : words;
    }

    /** Drops the trailing site slogan, e.g. " - Discover & Share GIFs" or " – GIFs entdecken und teilen". */
    private static String cleanTitle(String title) {
        if (title == null) return "";
        String withoutSlogan = title.replaceAll(
                "(?i)\\s*[-|\u2013\u2014]\\s*[^-|\u2013\u2014]*\\b(discover|share|teilen|entdecken|partager|compartir|descubre|giphy|tenor|imgur)\\b[^-|\u2013\u2014]*$", "");
        return withoutSlogan.strip();
    }

    private static String combineHint(String pageTitle, String slugWords) {
        String hint = pageTitle == null ? "" : pageTitle;
        boolean slugAddsInformation = !slugWords.isEmpty()
                && !GifLibrary.normalizeForSearch(hint).contains(GifLibrary.normalizeForSearch(slugWords));
        if (slugAddsInformation) hint = hint.isEmpty() ? slugWords : hint + " | " + slugWords;
        return hint.length() > 300 ? hint.substring(0, 300) : hint;
    }

    private static String fileNameFor(String slugWords, String pageTitle) {
        String baseName = !slugWords.isEmpty() ? slugWords : (pageTitle == null ? "" : pageTitle);
        baseName = baseName.replaceAll("[^\\p{L}\\p{N}._ -]", "_").strip().replace(' ', '-');
        if (baseName.length() > 60) baseName = baseName.substring(0, 60);
        return (baseName.isEmpty() ? "gif" : baseName) + ".gif";
    }

    private static String abbreviate(String text) {
        return text.length() > 80 ? text.substring(0, 80) + "…" : text;
    }

    // ---------------------------------------------------------------- HTML

    /** The meta/link tags of an HTML page that matter for finding its image. */
    private record HtmlPage(Map<String, List<String>> metaValues, String documentTitle, URI baseUri) {
        private static final Pattern META_OR_LINK_TAG = Pattern.compile("<(meta|link)\\b([^>]*)>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        private static final Pattern TAG_ATTRIBUTE = Pattern.compile("([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");
        private static final Pattern TITLE_TAG = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        private static final List<String> IMAGE_META_KEYS = List.of(
                "og:image:secure_url", "og:image", "og:image:url", "twitter:image", "twitter:image:src", "link:image_src");

        static HtmlPage parse(String html, URI baseUri) {
            Map<String, List<String>> metaValues = new HashMap<>();
            Matcher tags = META_OR_LINK_TAG.matcher(html);
            while (tags.find()) {
                boolean isMetaTag = tags.group(1).equalsIgnoreCase("meta");
                Map<String, String> attributes = new HashMap<>();
                Matcher attributeMatcher = TAG_ATTRIBUTE.matcher(tags.group(2));
                while (attributeMatcher.find()) {
                    String value = attributeMatcher.group(2) != null ? attributeMatcher.group(2) : attributeMatcher.group(3);
                    attributes.put(attributeMatcher.group(1).toLowerCase(Locale.ROOT), unescapeHtml(value));
                }
                String key;
                String value;
                if (isMetaTag) {
                    key = attributes.getOrDefault("property", attributes.get("name"));
                    value = attributes.get("content");
                } else {
                    key = attributes.get("rel") != null ? "link:" + attributes.get("rel").toLowerCase(Locale.ROOT) : null;
                    value = attributes.get("href");
                }
                if (key == null || value == null || value.isBlank()) continue;
                metaValues.computeIfAbsent(key.toLowerCase(Locale.ROOT), _ -> new ArrayList<>()).add(value.strip());
            }
            Matcher titleMatcher = TITLE_TAG.matcher(html);
            String documentTitle = titleMatcher.find() ? unescapeHtml(titleMatcher.group(1)).strip() : "";
            return new HtmlPage(metaValues, documentTitle, baseUri);
        }

        String title() {
            for (String key : List.of("og:title", "twitter:title")) {
                List<String> values = metaValues.get(key);
                if (values != null && !values.isEmpty()) return values.get(0);
            }
            return documentTitle;
        }

        /** Image URLs the page advertises, those ending in .gif first. */
        List<URI> imageCandidates() {
            List<URI> candidates = new ArrayList<>();
            for (String key : IMAGE_META_KEYS) {
                for (String value : metaValues.getOrDefault(key, List.of())) {
                    try {
                        URI candidate = baseUri.resolve(new URI(value.replace(" ", "%20")).toASCIIString());
                        if (!candidates.contains(candidate)) candidates.add(candidate);
                    } catch (URISyntaxException | IllegalArgumentException invalidValue) {
                        // skip values that are not URLs
                    }
                }
            }
            candidates.sort(Comparator.comparing((URI candidate) ->
                    candidate.getPath() != null && candidate.getPath().toLowerCase(Locale.ROOT).endsWith(".gif") ? 0 : 1));
            return candidates.size() > MAX_IMAGE_CANDIDATES ? candidates.subList(0, MAX_IMAGE_CANDIDATES) : candidates;
        }

        private static String unescapeHtml(String text) {
            String unescaped = text.replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'").replace("&apos;", "'")
                    .replace("&lt;", "<").replace("&gt;", ">");
            Matcher numericEntity = Pattern.compile("&#(x?)([0-9a-fA-F]+);").matcher(unescaped);
            StringBuilder result = new StringBuilder();
            while (numericEntity.find()) {
                String replacement;
                try {
                    int radix = numericEntity.group(1).isEmpty() ? 10 : 16;
                    replacement = new String(Character.toChars(Integer.parseInt(numericEntity.group(2), radix)));
                } catch (RuntimeException invalidEntity) {
                    replacement = numericEntity.group();
                }
                numericEntity.appendReplacement(result, Matcher.quoteReplacement(replacement));
            }
            numericEntity.appendTail(result);
            return result.toString().replace("&amp;", "&");
        }
    }
}
