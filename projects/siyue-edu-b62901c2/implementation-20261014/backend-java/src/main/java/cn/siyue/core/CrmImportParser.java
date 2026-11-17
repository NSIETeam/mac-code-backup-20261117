package cn.siyue.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

/** Parses bounded CSV and first-sheet XLSX files without external XML entities. */
public final class CrmImportParser {
    private static final int MAX_ROWS = 501;
    private static final int MAX_COLUMNS = 200;
    private static final int MAX_XML_BYTES = 8_000_000;
    private CrmImportParser() { }

    /** Selects the requested supported format and returns rows in file order. */
    public static List<List<String>> parse(String format, String content) {
        if (content == null || content.length() > 950_000) { invalid("导入文件过大"); }
        return switch (format) {
            case "csv" -> csv(content);
            case "xlsx" -> xlsx(content);
            default -> invalid("仅支持 CSV 或 XLSX");
        };
    }

    private static List<List<String>> csv(String content) {
        CsvState state = new CsvState();
        for (int i = 0; i < content.length(); i++) {
            i += readCsvCharacter(content, i, state);
        }
        List<List<String>> rows = state.finish();
        validateRows(rows);
        if (!rows.isEmpty() && !rows.get(0).isEmpty()) {
            rows.set(0, new ArrayList<>(rows.get(0)));
            String first = rows.get(0).get(0);
            rows.get(0).set(0, first.replaceFirst("^\\uFEFF", ""));
        }
        return rows;
    }

    private static int readCsvCharacter(String content, int index, CsvState state) {
        return state.quoted ? readQuotedCharacter(content, index, state)
            : readUnquotedCharacter(content, index, state);
    }

    private static int readQuotedCharacter(String content, int index, CsvState state) {
        char current = content.charAt(index);
        if (current != '"') { state.value.append(current); return 0; }
        if (index + 1 < content.length() && content.charAt(index + 1) == '"') {
            state.value.append('"'); return 1;
        }
        state.quoted = false; state.afterQuote = true;
        return 0;
    }

    private static int readUnquotedCharacter(String content, int index, CsvState state) {
        char current = content.charAt(index);
        if (state.afterQuote) { return afterCsvQuote(content, index, current, state); }
        if (current == '"') {
            if (!state.value.isEmpty()) { invalid("CSV 引号位置无效"); }
            state.quoted = true; return 0;
        }
        if (isSeparator(current)) { return separateCsvValue(content, index, current, state); }
        state.value.append(current);
        return 0;
    }

    private static int afterCsvQuote(String content, int index, char current, CsvState state) {
        if (Character.isWhitespace(current) && current != '\n' && current != '\r') { return 0; }
        if (!isSeparator(current)) { invalid("CSV 结束引号后只能出现分隔符"); }
        return separateCsvValue(content, index, current, state);
    }

    private static int separateCsvValue(String content, int index, char current, CsvState state) {
        state.row.add(state.value.toString()); state.value.setLength(0); state.afterQuote = false;
        if (current == ',') { return 0; }
        addRow(state.rows, state.row); state.row = new ArrayList<>();
        return current == '\r' && index + 1 < content.length() && content.charAt(index + 1) == '\n' ? 1 : 0;
    }

    private static boolean isSeparator(char value) { return value == ',' || value == '\n' || value == '\r'; }

    /** 有界CSV解析过程中的行、字段及引号状态，支持跨行引号内容并检查输入上限。 */
    private static final class CsvState {
        private final List<List<String>> rows = new ArrayList<>();
        private List<String> row = new ArrayList<>();
        private final StringBuilder value = new StringBuilder();
        private boolean quoted;
        private boolean afterQuote;
        private List<List<String>> finish() {
            if (quoted) { invalid("CSV 引号没有闭合"); }
            row.add(value.toString()); addRow(rows, row);
            return rows;
        }
    }

    private static void addRow(List<List<String>> rows, List<String> row) {
        if (row.stream().anyMatch(value -> value != null && !value.isBlank())) { rows.add(List.copyOf(row)); }
        if (rows.size() > MAX_ROWS) { invalid("请上传含表头及最多500行数据的文件"); }
    }

    private static List<List<String>> xlsx(String content) {
        try {
            byte[] file = Base64.getDecoder().decode(content);
            Map<String, String> entries = unzip(file);
            List<String> strings = sharedStrings(entries.get("xl/sharedStrings.xml"));
            String sheetPath = firstSheetPath(entries);
            String sheet = entries.get(sheetPath);
            if (sheet == null) { invalid("Excel 未找到第一个工作表"); }
            List<List<String>> rows = sheetRows(sheet, strings);
            validateRows(rows);
            return rows;
        } catch (BusinessException error) { throw error; }
        catch (Exception error) { return invalid("Excel 文件损坏或格式不受支持，请另存为 XLSX 或 CSV 后导入"); }
    }

    private static Map<String, String> unzip(byte[] file) throws Exception {
        Map<String, String> entries = new HashMap<>();
        int total = 0;
        int totalBytes = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(file), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++total > 3000) { invalid("Excel 文件包含过多资源"); }
                String name = entry.getName();
                if (name.startsWith("/") || name.contains("..")) { invalid("Excel 压缩目录无效"); }
                byte[] bytes = readBounded(zip, MAX_XML_BYTES);
                totalBytes += bytes.length;
                if (totalBytes > 20_000_000) { invalid("Excel 解压后超过限制"); }
                if (allowedEntry(name)) { entries.put(name, new String(bytes, StandardCharsets.UTF_8)); }
                zip.closeEntry();
            }
        }
        return entries;
    }

    private static byte[] readBounded(ZipInputStream input, int limit) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (output.size() + read > limit) { invalid("Excel 单个资源解压后超过限制"); }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static boolean allowedEntry(String name) {
        return name.equals("xl/sharedStrings.xml") || name.equals("xl/workbook.xml")
            || name.equals("xl/_rels/workbook.xml.rels") || name.matches("xl/worksheets/sheet[0-9]+\\.xml");
    }

    private static String firstSheetPath(Map<String, String> entries) throws Exception {
        String workbook = entries.get("xl/workbook.xml");
        String relationships = entries.get("xl/_rels/workbook.xml.rels");
        if (workbook == null || relationships == null) { return "xl/worksheets/sheet1.xml"; }
        String relationshipId = null;
        XMLStreamReader reader = xml(workbook);
        while (reader.hasNext()) {
            if (reader.next() == XMLStreamConstants.START_ELEMENT && reader.getLocalName().equals("sheet")) {
                relationshipId = attribute(reader, "id"); break;
            }
        }
        reader.close();
        if (relationshipId == null) { return "xl/worksheets/sheet1.xml"; }
        reader = xml(relationships);
        String path = null;
        while (reader.hasNext()) {
            if (reader.next() == XMLStreamConstants.START_ELEMENT && reader.getLocalName().equals("Relationship")
                    && relationshipId.equals(attribute(reader, "Id"))) {
                path = attribute(reader, "Target"); break;
            }
        }
        reader.close();
        if (path == null) { return "xl/worksheets/sheet1.xml"; }
        return path.startsWith("/") ? path.substring(1) : path.startsWith("xl/") ? path : "xl/" + path;
    }

    private static List<String> sharedStrings(String source) throws Exception {
        if (source == null) { return List.of(); }
        List<String> values = new ArrayList<>();
        XMLStreamReader reader = xml(source);
        StringBuilder current = null;
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT && reader.getLocalName().equals("si")) {
                current = new StringBuilder();
            } else if (event == XMLStreamConstants.CHARACTERS && current != null) {
                current.append(reader.getText());
            } else if (event == XMLStreamConstants.END_ELEMENT && reader.getLocalName().equals("si")) {
                values.add(current == null ? "" : current.toString()); current = null;
            }
        }
        reader.close();
        return values;
    }

    private static List<List<String>> sheetRows(String source, List<String> shared) throws Exception {
        XMLStreamReader reader = xml(source);
        SheetState state = new SheetState();
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) { startSheetElement(reader, state); }
            if (event == XMLStreamConstants.CHARACTERS && !state.capture.isEmpty()) { state.text.append(reader.getText()); }
            if (event == XMLStreamConstants.END_ELEMENT) { endSheetElement(reader.getLocalName(), shared, state); }
        }
        reader.close();
        return state.rows;
    }

    private static void startSheetElement(XMLStreamReader reader, SheetState state) {
        String element = reader.getLocalName();
        if (element.equals("row")) { state.row = new ArrayList<>(); }
        if (element.equals("c")) {
            state.column = columnIndex(attribute(reader, "r"));
            state.type = value(attribute(reader, "t")); state.cellValue = ""; state.formula = false;
        }
        if (element.equals("f")) { state.formula = true; }
        if (element.equals("v") || element.equals("t")) { state.capture = element; state.text.setLength(0); }
    }

    private static void endSheetElement(String element, List<String> shared, SheetState state) {
        if (element.equals("v") || element.equals("t")) { captureSheetValue(element, state); }
        if (element.equals("c") && state.row != null) { saveCell(shared, state); }
        if (element.equals("row") && state.row != null) {
            addRow(state.rows, state.row); state.row = null;
        }
    }

    private static void captureSheetValue(String element, SheetState state) {
        if (element.equals("v") || state.type.equals("inlineStr")) { state.cellValue = state.text.toString(); }
        state.capture = "";
    }

    private static void saveCell(List<String> shared, SheetState state) {
        if (state.formula) { invalid("请将公式转换为值后再导入"); }
        String cell = state.type.equals("s") ? sharedValue(shared, state.cellValue) : state.cellValue;
        putCell(state.row, state.column, cell);
    }

    /** 单工作表流式解析状态，累计有限单元格和行，同时校验单元格引用与共享字符串。 */
    private static final class SheetState {
        private final List<List<String>> rows = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();
        private List<String> row;
        private int column = -1;
        private String type = "";
        private String cellValue = "";
        private String capture = "";
        private boolean formula;
    }

    private static XMLStreamReader xml(String source) throws Exception {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, true);
        return factory.createXMLStreamReader(new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)));
    }

    private static String attribute(XMLStreamReader reader, String localName) {
        for (int i = 0; i < reader.getAttributeCount(); i++) {
            if (reader.getAttributeLocalName(i).equals(localName)) { return reader.getAttributeValue(i); }
        }
        return null;
    }

    private static int columnIndex(String reference) {
        if (reference == null) { invalid("Excel 单元格坐标无效"); }
        int column = 0;
        for (int i = 0; i < reference.length() && Character.isLetter(reference.charAt(i)); i++) {
            char letter = Character.toUpperCase(reference.charAt(i));
            if (letter < 'A' || letter > 'Z') { invalid("Excel 单元格坐标无效"); }
            column = column * 26 + letter - 'A' + 1;
        }
        if (column < 1 || column > MAX_COLUMNS) { invalid("Excel 列数超过限制"); }
        return column - 1;
    }

    private static void putCell(List<String> row, int column, String value) {
        if (column < 0) { invalid("Excel 单元格坐标无效"); }
        while (row.size() <= column) { row.add(""); }
        row.set(column, value);
    }

    private static String sharedValue(List<String> values, String index) {
        int parsed = Integer.parseInt(index);
        if (parsed < 0 || parsed >= values.size()) { invalid("Excel 共享字符串索引无效"); }
        return values.get(parsed);
    }

    private static String value(String value) { return value == null ? "" : value; }

    private static void validateRows(List<List<String>> rows) {
        if (rows.isEmpty() || rows.size() > MAX_ROWS) { invalid("请上传含表头及最多500行数据的文件"); }
        if (rows.stream().anyMatch(row -> row.size() > MAX_COLUMNS)) { invalid("文件列数超过限制"); }
    }

    private static <T> T invalid(String message) {
        throw new BusinessException(ErrorCode.INVALID_INPUT, message);
    }
}
