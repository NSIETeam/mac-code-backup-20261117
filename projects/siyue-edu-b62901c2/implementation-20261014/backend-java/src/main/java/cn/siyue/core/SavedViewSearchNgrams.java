package cn.siyue.core;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.HexFormat;
import java.util.Map;

/** Keyed trigram candidates for server-side substring checks on encrypted CRM fields. */
final class SavedViewSearchNgrams {
    private SavedViewSearchNgrams() { }

    static Map<String, String> encode(PiiCipher cipher, String value) {
        List<String> grams = grams(normalize(value));
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : cipher.blindIndexes("saved_search", "").keySet()) {
            List<String> hashes = grams.stream().map(gram -> hash(cipher, gram, key)).distinct().toList();
            result.put(key, json(hashes));
        }
        return Map.copyOf(result);
    }

    static ScopeSql.Clause candidate(String alias, String column, PiiCipher cipher, String value) {
        Map<String, List<String>> keyHashes = hashes(cipher, value);
        List<Object> args = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        keyHashes.forEach((key, values) -> {
            List<String> tests = new ArrayList<>();
            tests.add(alias + ".key_id=?"); args.add(key);
            for (String hash : values) {
                tests.add("JSON_CONTAINS(" + alias + "." + column + ",JSON_QUOTE(?))=1");
                args.add(hash);
            }
            keys.add("(" + String.join(" AND ", tests) + ")");
        });
        return new ScopeSql.Clause("(" + String.join(" OR ", keys) + ")", List.copyOf(args));
    }

    static boolean contains(String field, String query) {
        return field != null && normalize(field).contains(normalize(query));
    }

    private static Map<String, List<String>> hashes(PiiCipher cipher, String value) {
        List<String> grams = grams(normalize(value));
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String key : cipher.blindIndexes("saved_search", "").keySet()) {
            result.put(key, grams.stream().map(gram -> hash(cipher, gram, key)).distinct().toList());
        }
        return result;
    }

    private static String hash(PiiCipher cipher, String gram, String key) {
        return HexFormat.of().formatHex(cipher.blindIndexes("saved_search", gram).get(key));
    }

    private static List<String> grams(String value) {
        int[] points = value.codePoints().toArray();
        if (points.length == 0) { throw new BusinessException(ErrorCode.INVALID_INPUT, "搜索词不能为空"); }
        int width = Math.min(3, points.length);
        List<String> result = new ArrayList<>();
        for (int index = 0; index <= points.length - width; index++) {
            result.add(new String(points, index, width));
        }
        return result.stream().distinct().toList();
    }

    static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).trim().toLowerCase(Locale.ROOT);
    }

    private static String json(List<String> values) {
        return "[\"" + String.join("\",\"", values) + "\"]";
    }
}
