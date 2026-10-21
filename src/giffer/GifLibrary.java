package giffer;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** On-disk GIF store: {@code gifs/<id>.gif}, a thumbnail cache and one JSON index. Safe to call from any thread. */
final class GifLibrary {
    static final int THUMBNAIL_EDGE_PIXELS = 360;

    /** The stored entry, and whether this call created it (false = identical GIF was already stored). */
    record AddResult(GifEntry entry, boolean created) {}

    final Path rootDirectory;
    private final Path gifDirectory;
    private final Path thumbnailDirectory;
    private final Path indexFile;
    private final List<GifEntry> entries = new ArrayList<>();

    GifLibrary(Path rootDirectory) {
        this.rootDirectory = rootDirectory;
        this.gifDirectory = rootDirectory.resolve("gifs");
        this.thumbnailDirectory = rootDirectory.resolve("thumbs");
        this.indexFile = rootDirectory.resolve("index.json");
    }

    Path settingsFile() {
        return rootDirectory.resolve("settings.properties");
    }

    File gifFile(GifEntry entry) {
        return gifDirectory.resolve(entry.fileName()).toFile();
    }

    File thumbnailFile(GifEntry entry) {
        return thumbnailDirectory.resolve(entry.id + "-" + THUMBNAIL_EDGE_PIXELS + ".jpg").toFile();
    }

    synchronized void load() throws IOException {
        Files.createDirectories(gifDirectory);
        Files.createDirectories(thumbnailDirectory);
        entries.clear();
        if (!Files.exists(indexFile)) return;

        Map<String, Object> index = Json.asObject(Json.parse(Files.readString(indexFile, StandardCharsets.UTF_8)));
        List<Object> storedEntries = index == null ? null : Json.asArray(index.get("gifs"));
        if (storedEntries == null) return;
        for (Object storedEntry : storedEntries) {
            Map<String, Object> json = Json.asObject(storedEntry);
            if (json == null) continue;
            GifEntry entry = GifEntry.fromJson(json);
            if (!entry.id.isEmpty() && Files.exists(gifDirectory.resolve(entry.fileName()))) entries.add(entry);
        }
    }

    synchronized void save() {
        try {
            List<Object> serializedEntries = new ArrayList<>();
            for (GifEntry entry : entries) serializedEntries.add(entry.toJson());
            Map<String, Object> index = new LinkedHashMap<>();
            index.put("version", 2);
            index.put("gifs", serializedEntries);
            Path temporaryFile = rootDirectory.resolve("index.json.tmp");
            Files.writeString(temporaryFile, Json.write(index), StandardCharsets.UTF_8);
            Files.move(temporaryFile, indexFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            throw new RuntimeException("Index konnte nicht gespeichert werden: " + failure.getMessage(), failure);
        }
    }

    /** Stores a GIF. If identical content is already in the library, that entry is returned with created=false. */
    synchronized AddResult add(byte[] gifBytes, String originalFileName, String sourceUrl, String sourceHint) throws IOException {
        boolean looksLikeGif = gifBytes.length >= 6 && gifBytes[0] == 'G' && gifBytes[1] == 'I' && gifBytes[2] == 'F';
        if (!looksLikeGif) throw new IOException("Kein GIF: " + originalFileName);
        String newEntryId = shortContentHash(gifBytes);
        for (GifEntry existing : entries) {
            if (existing.id.equals(newEntryId)) return new AddResult(existing, false);
        }

        GifEntry entry = new GifEntry();
        entry.id = newEntryId;
        entry.originalFileName = originalFileName == null ? "" : originalFileName;
        entry.sourceUrl = sourceUrl == null ? "" : sourceUrl;
        entry.sourceHint = sourceHint == null ? "" : sourceHint;
        Files.write(gifDirectory.resolve(entry.fileName()), gifBytes);
        try {
            GifDecoder.Size size = GifDecoder.readSize(gifFile(entry));
            entry.widthPixels = size.widthPixels();
            entry.heightPixels = size.heightPixels();
        } catch (IOException | RuntimeException unreadableHeader) {
            // the card falls back to the thumbnail's aspect ratio
        }
        entries.add(entry);
        save();
        return new AddResult(entry, true);
    }

    AddResult addFile(File sourceFile) throws IOException {
        return add(Files.readAllBytes(sourceFile.toPath()), sourceFile.getName(), "", "");
    }

    synchronized GifEntry findBySourceUrl(String sourceUrl) {
        if (sourceUrl == null || sourceUrl.isBlank()) return null;
        for (GifEntry entry : entries) {
            if (sourceUrl.equals(entry.sourceUrl)) return entry;
        }
        return null;
    }

    synchronized void delete(GifEntry entry) {
        entries.remove(entry);
        try {
            Files.deleteIfExists(gifDirectory.resolve(entry.fileName()));
            Files.deleteIfExists(thumbnailFile(entry).toPath());
        } catch (IOException ignored) {
            // an orphaned file does no harm
        }
        save();
    }

    synchronized int size() {
        return entries.size();
    }

    synchronized Set<String> allIds() {
        Set<String> ids = new HashSet<>();
        for (GifEntry entry : entries) ids.add(entry.id);
        return ids;
    }

    /** Entries that were never analysed or whose last analysis failed. */
    synchronized List<GifEntry> entriesNeedingAnalysis() {
        List<GifEntry> pending = new ArrayList<>();
        for (GifEntry entry : entries) {
            if (entry.analysisStatus == GifEntry.AnalysisStatus.NEW || entry.analysisStatus == GifEntry.AnalysisStatus.ERROR) {
                pending.add(entry);
            }
        }
        return pending;
    }

    /** Fills in the size of older entries that were stored without one. Returns true if anything changed. */
    synchronized boolean fillMissingDimensions() {
        boolean changed = false;
        for (GifEntry entry : entries) {
            if (entry.widthPixels > 0 && entry.heightPixels > 0) continue;
            try {
                GifDecoder.Size size = GifDecoder.readSize(gifFile(entry));
                entry.widthPixels = size.widthPixels();
                entry.heightPixels = size.heightPixels();
                changed = true;
            } catch (IOException | RuntimeException unreadableHeader) {
                // leave the entry as it is
            }
        }
        return changed;
    }

    /** The most frequently used emotions (for the quick-filter chips), most common first. */
    synchronized List<String> mostUsedEmotions(int limit) {
        Map<String, Integer> usageCount = new HashMap<>();
        Map<String, String> displayLabel = new HashMap<>();
        for (GifEntry entry : entries) {
            for (String emotion : entry.emotions) {
                String normalized = normalizeForSearch(emotion);
                if (normalized.isEmpty()) continue;
                usageCount.merge(normalized, 1, Integer::sum);
                displayLabel.putIfAbsent(normalized, emotion.strip());
            }
        }
        return usageCount.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(limit)
                .map(usage -> displayLabel.get(usage.getKey()))
                .toList();
    }

    // ---------------------------------------------------------------- search

    /** Entries matching the query, best match first; an empty query returns everything, newest first. */
    synchronized List<GifEntry> search(String query) {
        String[] searchWords = normalizeForSearch(query).split("[\\s,;]+");
        List<GifEntry> allEntries = new ArrayList<>(entries);
        boolean queryIsEmpty = searchWords.length == 1 && searchWords[0].isEmpty();
        if (queryIsEmpty) {
            allEntries.sort(Comparator.comparingLong((GifEntry entry) -> entry.addedAtMillis).reversed());
            return allEntries;
        }

        Map<GifEntry, Double> scores = new HashMap<>();
        for (GifEntry entry : allEntries) {
            double score = relevanceScore(entry, searchWords);
            if (score > 0) scores.put(entry, score);
        }
        List<GifEntry> matches = new ArrayList<>(scores.keySet());
        matches.sort(Comparator.comparingDouble((GifEntry entry) -> scores.get(entry)).reversed());
        return matches;
    }

    private static double relevanceScore(GifEntry entry, String[] searchWords) {
        List<String> keywords = entry.keywords.stream().map(GifLibrary::normalizeForSearch).toList();
        List<String> emotions = entry.emotions.stream().map(GifLibrary::normalizeForSearch).toList();
        String title = normalizeForSearch(entry.title);
        String description = normalizeForSearch(entry.description);
        String usageHint = normalizeForSearch(entry.usageHint);
        String textInGif = normalizeForSearch(entry.textInGif);
        String fileName = normalizeForSearch(entry.originalFileName);
        String sourceHint = normalizeForSearch(entry.sourceHint);

        double totalScore = 0;
        int matchedWords = 0;
        int requestedWords = 0;
        for (String word : searchWords) {
            if (word.isEmpty()) continue;
            requestedWords++;
            double wordScore = 0;
            for (String keyword : keywords) {
                wordScore = Math.max(wordScore, keyword.equals(word) ? 6 : keyword.startsWith(word) ? 4 : keyword.contains(word) ? 2.5 : 0);
            }
            for (String emotion : emotions) {
                wordScore = Math.max(wordScore, emotion.equals(word) ? 6 : emotion.startsWith(word) ? 5 : emotion.contains(word) ? 3 : 0);
            }
            if (title.contains(word)) wordScore += 3;
            if (description.contains(word)) wordScore += 1.5;
            if (usageHint.contains(word)) wordScore += 1.5;
            if (textInGif.contains(word)) wordScore += 2;
            if (fileName.contains(word)) wordScore += 1;
            if (sourceHint.contains(word)) wordScore += 1;
            if (wordScore > 0) matchedWords++;
            totalScore += wordScore;
        }
        boolean everyWordFound = matchedWords == requestedWords && requestedWords > 0;
        if (everyWordFound) totalScore += 5; // rank above entries that only match some of the words
        return totalScore;
    }

    /** Lower case without accents, so that "Fröhlich" finds "frohlich" and vice versa. */
    static String normalizeForSearch(String text) {
        if (text == null) return "";
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).strip();
    }

    private static String shortContentHash(byte[] content) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder hex = new StringBuilder();
            for (int index = 0; index < 8; index++) hex.append(String.format("%02x", digest[index]));
            return hex.toString();
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IOException(unavailable);
        }
    }
}
