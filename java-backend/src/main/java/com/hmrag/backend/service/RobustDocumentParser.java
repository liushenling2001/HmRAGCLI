package com.hmrag.backend.service;

import com.hmrag.backend.config.AppProperties;
import com.hmrag.backend.domain.SourceFile;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.hwpf.usermodel.Range;
import org.apache.poi.hwpf.usermodel.Table;
import org.apache.poi.hwpf.usermodel.TableIterator;
import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class RobustDocumentParser {

    private static final Logger log = LoggerFactory.getLogger(RobustDocumentParser.class);

    private final AppProperties appProperties;
    private final ExecutorService parseExecutor;

    public RobustDocumentParser(AppProperties appProperties) {
        this.appProperties = appProperties;
        int threads = Math.max(1, appProperties.ingest().parseExecutorThreads());
        int queueCapacity = Math.max(4, appProperties.ingest().parseExecutorQueueCapacity());
        this.parseExecutor = new ThreadPoolExecutor(
                threads,
                threads,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                new ParserThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    public ParsedContent parse(SourceFile file) {
        return parse(file, ProgressListener.NOOP);
    }

    public ParsedContent parse(SourceFile file, ProgressListener progressListener) {
        Callable<ParsedContent> task = () -> parseUnsafe(file, progressListener == null ? ProgressListener.NOOP : progressListener);
        Future<ParsedContent> future;
        try {
            future = parseExecutor.submit(task);
        } catch (RejectedExecutionException ex) {
            log.warn("Parser queue is full, falling back immediately for file={}", file.getFilePath());
            return fallback(file, "PARSER_QUEUE_FULL");
        }
        Duration timeout = parseTimeout(file);
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            log.warn("Parser timed out for file={}", file.getFilePath());
            return fallback(file, "PARSER_TIMEOUT");
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            log.warn("Parser failed for file={}: {}", file.getFilePath(), cause.getMessage());
            return fallback(file, "PARSER_FAILED: " + cause.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Parser interrupted for file={}", file.getFilePath());
            return fallback(file, "PARSER_INTERRUPTED");
        }
    }

    private ParsedContent parseUnsafe(SourceFile file) throws Exception {
        return parseUnsafe(file, ProgressListener.NOOP);
    }

    private ParsedContent parseUnsafe(SourceFile file, ProgressListener progressListener) throws Exception {
        String ext = normalizedExt(file);
        return switch (ext) {
            case ".txt", ".md" -> text(file, progressListener);
            case ".pdf" -> pdf(file, progressListener);
            case ".docx" -> docx(file, progressListener);
            case ".doc" -> doc(file, progressListener);
            case ".xlsx", ".xls" -> workbook(file, progressListener);
            default -> fallback(file, "UNSUPPORTED_PARSER");
        };
    }

    private ParsedContent text(SourceFile file, ProgressListener progressListener) throws IOException {
        progressListener.onProgress(0, 1);
        String content = truncate(readString(Path.of(file.getFilePath())));
        List<ParsedChunk> chunks = splitPlainText(file, content);
        progressListener.onProgress(1, 1);
        return success(file, content, chunks, "native-text", Map.of("parserMode", "native-text"));
    }

    private ParsedContent pdf(SourceFile file, ProgressListener progressListener) throws IOException {
        try (PDDocument document = Loader.loadPDF(Path.of(file.getFilePath()).toFile())) {
            int pages = Math.max(1, document.getNumberOfPages());
            progressListener.onProgress(0, pages);
            PDFTextStripper stripper = new PDFTextStripper();
            StringBuilder builder = new StringBuilder();
            for (int page = 1; page <= pages; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = stripper.getText(document);
                if (!pageText.isBlank()) {
                    if (!builder.isEmpty()) {
                        builder.append('\n');
                    }
                    builder.append(pageText);
                }
                progressListener.onProgress(page, pages);
            }
            String content = truncate(builder.toString());
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("parserMode", "pdfbox");
            meta.put("pageCount", document.getNumberOfPages());
            return success(file, content, splitPlainText(file, content), "pdfbox", meta);
        }
    }

    private ParsedContent docx(SourceFile file, ProgressListener progressListener) throws IOException {
        try (InputStream input = Files.newInputStream(Path.of(file.getFilePath()));
             XWPFDocument document = new XWPFDocument(input)) {
            List<ParsedChunk> chunks = new ArrayList<>();
            String currentHeading = baseName(file.getFileName());
            int tableIndex = 0;
            List<IBodyElement> bodyElements = document.getBodyElements();
            int total = Math.max(1, bodyElements.size());
            int done = 0;
            progressListener.onProgress(0, total);
            for (IBodyElement element : bodyElements) {
                if (element instanceof XWPFParagraph paragraph) {
                    String text = normalize(paragraph.getText());
                    if (text.isBlank()) {
                        done++;
                        progressListener.onProgress(done, total);
                        continue;
                    }
                    String style = paragraph.getStyle();
                    if (isHeading(paragraph, style, text)) {
                        currentHeading = text;
                        done++;
                        progressListener.onProgress(done, total);
                        continue;
                    }
                    String chunkType = isListParagraph(paragraph) ? "list" : "paragraph";
                    chunks.add(new ParsedChunk(currentHeading, text, chunkType, null, null));
                } else if (element instanceof XWPFTable table) {
                    tableIndex++;
                    chunks.addAll(extractDocxTableChunks(table, currentHeading, tableIndex));
                }
                done++;
                progressListener.onProgress(done, total);
            }
            String content = joinChunkContent(chunks);
            if (chunks.isEmpty()) {
                StringBuilder rawText = new StringBuilder();
                for (XWPFParagraph paragraph : document.getParagraphs()) {
                    String value = normalize(paragraph.getText());
                    if (value.isBlank()) {
                        continue;
                    }
                    if (!rawText.isEmpty()) {
                        rawText.append("\n\n");
                    }
                    rawText.append(value);
                }
                content = truncate(rawText.toString());
                chunks = splitPlainText(file, content);
            }
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("parserMode", "poi-docx");
            meta.put("paragraphCount", document.getParagraphs().size());
            meta.put("tableCount", document.getTables().size());
            return success(file, content, chunks, "poi-docx", meta);
        }
    }

    private ParsedContent doc(SourceFile file, ProgressListener progressListener) throws IOException {
        try {
            return parseDocWithPoi(file, progressListener);
        } catch (Exception ex) {
            ParsedContent converted = tryConvertDocAndParse(file, ex);
            if (converted != null) {
                return converted;
            }
            if (ex instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException(ex);
        }
    }

    private ParsedContent workbook(SourceFile file, ProgressListener progressListener) throws IOException, OpenXML4JException {
        try (InputStream raw = Files.newInputStream(Path.of(file.getFilePath()));
             BufferedInputStream buffered = new BufferedInputStream(raw)) {
            FileMagic magic = FileMagic.valueOf(buffered);
            try (Workbook workbook = WorkbookFactory.create(buffered)) {
                DataFormatter formatter = new DataFormatter();
                List<ParsedChunk> chunks = new ArrayList<>();
                int sheetCount = workbook.getNumberOfSheets();
                progressListener.onProgress(0, Math.max(1, sheetCount));
                for (int i = 0; i < sheetCount; i++) {
                    Sheet sheet = workbook.getSheetAt(i);
                    int rowLimit = Math.min(sheet.getLastRowNum(), 200);
                    for (int rowNum = 0; rowNum <= rowLimit && chunks.size() < 120; rowNum++) {
                        Row row = sheet.getRow(rowNum);
                        if (row == null) {
                            continue;
                        }
                        StringBuilder rowText = new StringBuilder();
                        int cellLimit = Math.min(Math.max(row.getLastCellNum(), 0), 24);
                        for (int cellNum = 0; cellNum < cellLimit; cellNum++) {
                            Cell cell = row.getCell(cellNum);
                            if (cell == null) {
                                continue;
                            }
                            String value = formatter.formatCellValue(cell).trim();
                            if (value.isEmpty()) {
                                continue;
                            }
                            if (!rowText.isEmpty()) {
                                rowText.append(" | ");
                            }
                            rowText.append(value);
                        }
                        if (!rowText.isEmpty()) {
                            chunks.add(new ParsedChunk(sheet.getSheetName(), truncate(rowText.toString()), "table_row", rowNum + 1, null));
                        }
                    }
                    progressListener.onProgress(i + 1, Math.max(1, sheetCount));
                }
                String content = joinChunkContent(chunks);
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("parserMode", "poi-workbook");
                meta.put("sheetCount", sheetCount);
                meta.put("fileMagic", magic.name());
                return success(file, content, chunks, "poi-workbook", meta);
            }
        }
    }

    private List<ParsedChunk> extractDocxTableChunks(XWPFTable table, String heading, int tableIndex) {
        List<ParsedChunk> chunks = new ArrayList<>();
        int rowNumber = 0;
        for (XWPFTableRow row : table.getRows()) {
            rowNumber++;
            List<String> cells = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                String value = normalize(cell.getText());
                if (!value.isBlank()) {
                    cells.add(value);
                }
            }
            if (!cells.isEmpty()) {
                String title = heading + " / 表" + tableIndex;
                chunks.add(new ParsedChunk(title, truncate(String.join(" | ", cells)), "table_row", rowNumber, null));
            }
        }
        return chunks;
    }

    private List<ParsedChunk> extractDocTableChunks(Table table, String heading, int tableIndex) {
        List<ParsedChunk> chunks = new ArrayList<>();
        for (int rowIndex = 0; rowIndex < table.numRows(); rowIndex++) {
            org.apache.poi.hwpf.usermodel.TableRow row = table.getRow(rowIndex);
            List<String> cells = new ArrayList<>();
            for (int cellIndex = 0; cellIndex < row.numCells(); cellIndex++) {
                String value = normalize(row.getCell(cellIndex).text());
                if (!value.isBlank()) {
                    cells.add(value);
                }
            }
            if (!cells.isEmpty()) {
                String title = heading + " / 表" + tableIndex;
                chunks.add(new ParsedChunk(title, truncate(String.join(" | ", cells)), "table_row", rowIndex + 1, null));
            }
        }
        return chunks;
    }

    private ParsedContent parseDocWithPoi(SourceFile file, ProgressListener progressListener) throws IOException {
        try (InputStream input = Files.newInputStream(Path.of(file.getFilePath()));
             HWPFDocument document = new HWPFDocument(input);
             WordExtractor extractor = new WordExtractor(document)) {
            List<ParsedChunk> chunks = new ArrayList<>();
            String heading = baseName(file.getFileName());
            int paragraphLimit = 1200;
            int paragraphCount = 0;
            String[] paragraphs = extractor.getParagraphText();
            int total = Math.max(1, Math.min(paragraphs.length, paragraphLimit));
            progressListener.onProgress(0, total);
            for (String raw : paragraphs) {
                if (++paragraphCount > paragraphLimit) {
                    break;
                }
                String text = normalize(raw);
                if (text.isBlank()) {
                    progressListener.onProgress(paragraphCount, total);
                    continue;
                }
                if (looksLikeHeading(text)) {
                    heading = text;
                    progressListener.onProgress(paragraphCount, total);
                    continue;
                }
                chunks.add(new ParsedChunk(heading, text, "paragraph", null, null));
                progressListener.onProgress(paragraphCount, total);
            }
            Range range = document.getRange();
            TableIterator iterator = new TableIterator(range);
            int tableIndex = 0;
            int tableRowLimit = 800;
            int tableRows = 0;
            while (iterator.hasNext()) {
                Table table = iterator.next();
                tableIndex++;
                List<ParsedChunk> tableChunks = extractDocTableChunks(table, heading, tableIndex);
                for (ParsedChunk parsedChunk : tableChunks) {
                    if (tableRows >= tableRowLimit) {
                        break;
                    }
                    chunks.add(parsedChunk);
                    tableRows++;
                }
                if (tableRows >= tableRowLimit) {
                    break;
                }
            }
            String content = joinChunkContent(chunks);
            if (chunks.isEmpty()) {
                content = truncate(extractor.getText());
                chunks = splitPlainText(file, content);
            }
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("parserMode", "poi-doc");
            meta.put("tableCount", tableIndex);
            return success(file, content, chunks, "poi-doc", meta);
        }
    }

    private Duration parseTimeout(SourceFile file) {
        int configured = Math.max(5, appProperties.ingest().parseTimeoutSeconds());
        String ext = normalizedExt(file);
        if (".doc".equals(ext)) {
            return Duration.ofSeconds(Math.min(configured, 15));
        }
        if (".docx".equals(ext)) {
            return Duration.ofSeconds(Math.min(configured, 20));
        }
        return Duration.ofSeconds(configured);
    }

    private ParsedContent tryConvertDocAndParse(SourceFile file, Exception originalException) {
        if (!appProperties.ingest().docConversionEnabled()) {
            return null;
        }
        String sofficePath = appProperties.ingest().sofficePath();
        if (sofficePath == null || sofficePath.isBlank() || !Files.isRegularFile(Path.of(sofficePath))) {
            return null;
        }
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("hmrag-doc-convert-");
            ProcessBuilder builder = new ProcessBuilder(
                    sofficePath,
                    "--headless",
                    "--convert-to", "docx",
                    "--outdir", tempDir.toString(),
                    Path.of(file.getFilePath()).toString()
            );
            builder.redirectErrorStream(true);
            Process process = builder.start();
            long timeoutSeconds = Math.max(5, appProperties.ingest().parseTimeoutSeconds() / 2);
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }
            Path converted = tempDir.resolve(baseName(file.getFileName()) + ".docx");
            if (!Files.isRegularFile(converted)) {
                return null;
            }
            ParsedContent parsed = parseConvertedDocx(file, converted);
            Map<String, Object> merged = new LinkedHashMap<>(parsed.metadata());
            merged.put("conversionFallback", true);
            merged.put("conversionSource", "soffice");
            merged.put("conversionOriginalError", originalException.getMessage());
            return new ParsedContent(
                    parsed.content(),
                    parsed.chunks(),
                    "soffice-docx",
                    parsed.fallback(),
                    parsed.fallbackReason(),
                    merged
            );
        } catch (Exception conversionException) {
            return null;
        } finally {
            if (tempDir != null) {
                try {
                    Files.walk(tempDir)
                            .sorted(java.util.Comparator.reverseOrder())
                            .forEach(path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (IOException ignored) {
                                }
                            });
                } catch (IOException ignored) {
                }
            }
        }
    }

    private ParsedContent parseConvertedDocx(SourceFile sourceFile, Path convertedPath) throws IOException {
        try (InputStream input = Files.newInputStream(convertedPath);
             XWPFDocument document = new XWPFDocument(input)) {
            List<ParsedChunk> chunks = new ArrayList<>();
            String currentHeading = baseName(sourceFile.getFileName());
            int tableIndex = 0;
            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    String text = normalize(paragraph.getText());
                    if (text.isBlank()) {
                        continue;
                    }
                    String style = paragraph.getStyle();
                    if (isHeading(paragraph, style, text)) {
                        currentHeading = text;
                        continue;
                    }
                    String chunkType = isListParagraph(paragraph) ? "list" : "paragraph";
                    chunks.add(new ParsedChunk(currentHeading, text, chunkType, null, null));
                } else if (element instanceof XWPFTable table) {
                    tableIndex++;
                    chunks.addAll(extractDocxTableChunks(table, currentHeading, tableIndex));
                }
            }
            String content = joinChunkContent(chunks);
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("parserMode", "soffice-docx");
            meta.put("tableCount", tableIndex);
            meta.put("conversionFallback", true);
            return success(sourceFile, content, chunks, "soffice-docx", meta);
        }
    }

    private List<ParsedChunk> splitPlainText(SourceFile file, String content) {
        List<ParsedChunk> chunks = new ArrayList<>();
        String heading = baseName(file.getFileName());
        for (String part : content.split("\\n\\s*\\n")) {
            String normalized = normalize(part);
            if (normalized.isBlank()) {
                continue;
            }
            chunks.add(new ParsedChunk(heading, truncate(normalized), "paragraph", null, null));
            if (chunks.size() >= 40) {
                break;
            }
        }
        if (chunks.isEmpty() && !content.isBlank()) {
            chunks.add(new ParsedChunk(heading, truncate(content), "paragraph", null, null));
        }
        return chunks;
    }

    private ParsedContent success(SourceFile file, String content, List<ParsedChunk> chunks, String parserMode, Map<String, Object> metadata) {
        String normalized = content == null ? "" : content.trim();
        if (normalized.isEmpty()) {
            return fallback(file, "EMPTY_PARSED_CONTENT");
        }
        Map<String, Object> merged = new LinkedHashMap<>(metadata);
        merged.put("fallback", false);
        merged.put("parserMode", parserMode);
        merged.put("chunkCount", chunks.size());
        return new ParsedContent(normalized, chunks, parserMode, false, null, merged);
    }

    private ParsedContent fallback(SourceFile file, String reason) {
        String relativePath = file.getRelativePath() == null ? file.getFilePath() : file.getRelativePath();
        String content = """
                文件已接收，但解析器进入降级模式。
                原始文件名：%s
                相对路径：%s
                文件类型：%s
                降级原因：%s
                当前系统会继续建档并记录该原因，避免整个处理队列卡死。
                """.formatted(file.getFileName(), relativePath, normalizedExt(file), reason);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("fallback", true);
        metadata.put("fallbackReason", reason);
        metadata.put("parserMode", "fallback");
        List<ParsedChunk> chunks = List.of(new ParsedChunk(baseName(file.getFileName()), truncate(content), "fallback", null, null));
        return new ParsedContent(content, chunks, "fallback", true, reason, metadata);
    }

    private String joinChunkContent(List<ParsedChunk> chunks) {
        StringBuilder builder = new StringBuilder();
        for (ParsedChunk chunk : chunks) {
            if (builder.length() >= appProperties.ingest().parseMaxChars()) {
                break;
            }
            if (!builder.isEmpty()) {
                builder.append("\n\n");
            }
            if (chunk.title() != null && !chunk.title().isBlank()) {
                builder.append(chunk.title()).append('\n');
            }
            builder.append(chunk.content());
        }
        return truncate(builder.toString());
    }

    private boolean isHeading(XWPFParagraph paragraph, String style, String text) {
        if (style != null && style.toLowerCase().contains("heading")) {
            return true;
        }
        return looksLikeHeading(text) || paragraph.getStyleID() != null && paragraph.getStyleID().toLowerCase().contains("heading");
    }

    private boolean isListParagraph(XWPFParagraph paragraph) {
        return paragraph.getNumID() != null || paragraph.getParagraphText().stripLeading().matches("^[0-9一二三四五六七八九十]+[.、].*");
    }

    private boolean looksLikeHeading(String text) {
        return text.length() <= 32 && text.matches("^(第[一二三四五六七八九十0-9]+[章节部分条]|[0-9一二三四五六七八九十]+[.、]).*");
    }

    private String readString(Path path) throws IOException {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException primary) {
            return Files.readString(path);
        }
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        String normalized = normalize(text);
        int maxChars = Math.max(2000, appProperties.ingest().parseMaxChars());
        return normalized.length() > maxChars ? normalized.substring(0, maxChars) : normalized;
    }

    private String normalize(String text) {
        return text == null ? "" : text.replace("\u0000", " ").replace("\r", "\n").replaceAll("\\n{3,}", "\n\n").replaceAll("[ \\t]+", " ").trim();
    }

    private String normalizedExt(SourceFile file) {
        return file.getFileExt() == null ? "" : file.getFileExt().toLowerCase();
    }

    private String baseName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "未命名文档";
        }
        int idx = fileName.lastIndexOf('.');
        return idx > 0 ? fileName.substring(0, idx) : fileName;
    }

    public record ParsedChunk(
            String title,
            String content,
            String chunkType,
            Integer pageNo,
            String sourceSpan
    ) {
    }

    public record ParsedContent(
            String content,
            List<ParsedChunk> chunks,
            String parserMode,
            boolean fallback,
            String fallbackReason,
            Map<String, Object> metadata
    ) {
    }

    private static final class ParserThreadFactory implements ThreadFactory {
        private int counter = 0;

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "hmrag-parser-" + (++counter));
            thread.setDaemon(true);
            return thread;
        }
    }

    @FunctionalInterface
    public interface ProgressListener {
        ProgressListener NOOP = (completed, total) -> {
        };

        void onProgress(int completed, int total);
    }
}
