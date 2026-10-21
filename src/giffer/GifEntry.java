package giffer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One GIF in the library together with everything Gemini (or the user) said about it. */
final class GifEntry {

    /** Where a GIF stands in the automatic analysis. */
    enum AnalysisStatus {
        NEW, ANALYZING, DONE, ERROR;

        /** Unknown or missing names count as NEW so that a damaged index never blocks loading. */
        static AnalysisStatus fromName(String name) {
            for (AnalysisStatus status : values()) {
                if (status.name().equals(name)) return status;
            }
            return NEW;
        }
    }

    /** Short content hash; also the file name inside the library. */
    String id;
    String originalFileName = "";
    String title = "";
    String description = "";
    String usageHint = "";
    String textInGif = "";
    List<String> keywords = new ArrayList<>();
    List<String> emotions = new ArrayList<>();
    AnalysisStatus analysisStatus = AnalysisStatus.NEW;
    String analysisError = "";
    long addedAtMillis = System.currentTimeMillis();

    /** Web page the GIF was imported from (empty for local files). */
    String sourceUrl = "";
    /** Title/tags of the source page; handed to Gemini as an (untrusted) hint. */
    String sourceHint = "";
    int widthPixels;
    int heightPixels;

    String fileName() {
        return id + ".gif";
    }

    String displayName() {
        if (!title.isBlank()) return title;
        return originalFileName.isBlank() ? id : originalFileName;
    }

    /** width / height, or 0 when the size is unknown. */
    double aspectRatio() {
        return widthPixels > 0 && heightPixels > 0 ? (double) widthPixels / heightPixels : 0;
    }

    // The JSON keys below are the on-disk format of index.json: keep them stable.

    Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", id);
        json.put("originalName", originalFileName);
        json.put("title", title);
        json.put("description", description);
        json.put("emotions", emotions);
        json.put("keywords", keywords);
        json.put("usage", usageHint);
        json.put("textInGif", textInGif);
        // A GIF that was mid-analysis when saved is retried as "new" on the next start.
        json.put("status", (analysisStatus == AnalysisStatus.ANALYZING ? AnalysisStatus.NEW : analysisStatus).name());
        json.put("error", analysisError);
        json.put("addedAt", addedAtMillis);
        json.put("sourceUrl", sourceUrl);
        json.put("sourceHint", sourceHint);
        json.put("width", widthPixels);
        json.put("height", heightPixels);
        return json;
    }

    static GifEntry fromJson(Map<String, Object> json) {
        GifEntry entry = new GifEntry();
        entry.id = Json.asString(json.get("id"), "");
        entry.originalFileName = Json.asString(json.get("originalName"), "");
        entry.title = Json.asString(json.get("title"), "");
        entry.description = Json.asString(json.get("description"), "");
        entry.usageHint = Json.asString(json.get("usage"), "");
        entry.textInGif = Json.asString(json.get("textInGif"), "");
        entry.emotions = Json.asStringList(json.get("emotions"));
        entry.keywords = Json.asStringList(json.get("keywords"));
        entry.analysisStatus = AnalysisStatus.fromName(Json.asString(json.get("status"), ""));
        entry.analysisError = Json.asString(json.get("error"), "");
        entry.sourceUrl = Json.asString(json.get("sourceUrl"), "");
        entry.sourceHint = Json.asString(json.get("sourceHint"), "");
        if (json.get("addedAt") instanceof Number number) entry.addedAtMillis = number.longValue();
        if (json.get("width") instanceof Number number) entry.widthPixels = number.intValue();
        if (json.get("height") instanceof Number number) entry.heightPixels = number.intValue();
        return entry;
    }
}
