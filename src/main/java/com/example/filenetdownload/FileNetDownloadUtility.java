package com.example.filenetdownload;

import com.filenet.api.collection.ContentElementList;
import com.filenet.api.constants.PropertyNames;
import com.filenet.api.core.Connection;
import com.filenet.api.core.ContentTransfer;
import com.filenet.api.core.Document;
import com.filenet.api.core.Domain;
import com.filenet.api.core.Factory;
import com.filenet.api.core.ObjectStore;
import com.filenet.api.property.FilterElement;
import com.filenet.api.property.PropertyFilter;
import com.filenet.api.util.Id;
import com.filenet.api.util.UserContext;

import javax.security.auth.Subject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;

public class FileNetDownloadUtility {
    public static void main(String[] args) throws Exception {
        Path configPath = args.length > 0
                ? Paths.get(args[0])
                : Paths.get("config", "application.properties");

        Properties config = loadConfig(configPath);
        Path baseDownloadDir = Paths.get(required(config, "download.baseDir"));
        Files.createDirectories(baseDownloadDir);

        runDownloadJob(config, baseDownloadDir);
    }

    private static void runDownloadJob(Properties config, Path baseDownloadDir) throws SQLException {
        System.out.println("Step 1: Connecting to Oracle database.");
        try (java.sql.Connection oracleConnection = DriverManager.getConnection(
                required(config, "oracle.jdbcUrl"),
                required(config, "oracle.username"),
                required(config, "oracle.password"))) {

            oracleConnection.setAutoCommit(false);

            System.out.println("Step 2: Running Oracle select query.");
            processOracleRows(config, oracleConnection, baseDownloadDir);
        }
    }

    private static void processOracleRows(
            Properties config,
            java.sql.Connection oracleConnection,
            Path baseDownloadDir) throws SQLException {

        UserContext fileNetUserContext = UserContext.get();
        ObjectStore objectStore = null;

        try (PreparedStatement selectStatement = oracleConnection.prepareStatement(required(config, "oracle.selectSql"))) {
            selectStatement.setFetchSize(Integer.parseInt(config.getProperty("oracle.fetchSize", "100")));

            try (ResultSet resultSet = selectStatement.executeQuery()) {
                int rowCount = 0;
                while (resultSet.next()) {
                    rowCount++;
                    SourceRow sourceRow = readSourceRow(resultSet);
                    System.out.printf("Step 3: Processing row %s with FileNet id %s.%n",
                            sourceRow.recordId, sourceRow.fileNetDocumentId);

                    try {
                        if (sourceRow.fileNetDocumentId.isBlank()) {
                            throw new IllegalArgumentException("Oracle row has empty FILENET_ID.");
                        }
                        if (objectStore == null) {
                            System.out.println("Step 4: Connecting to FileNet Content Platform Engine.");
                            objectStore = connectObjectStore(config, fileNetUserContext);
                        }
                        DownloadResult result = downloadDocument(objectStore, sourceRow.fileNetDocumentId, baseDownloadDir);
                        updateSuccess(oracleConnection, config, sourceRow, result);
                        oracleConnection.commit();
                        System.out.printf("Step 6: Marked row %s as SUCCESS.%n", sourceRow.recordId);
                    } catch (Exception ex) {
                        updateFailure(oracleConnection, config, sourceRow, ex);
                        oracleConnection.commit();
                        System.err.printf("Step 6: Marked row %s as FAILED: %s%n",
                                sourceRow.recordId, ex.getMessage());
                    }
                }
                System.out.printf("Completed processing %d Oracle rows.%n", rowCount);
            }
        } finally {
            if (objectStore != null) {
                fileNetUserContext.popSubject();
            }
        }
    }

    private static SourceRow readSourceRow(ResultSet resultSet) throws SQLException {
        String recordId = resultSet.getString("RECORD_ID");
        String fileNetDocumentId = resultSet.getString("FILENET_ID");

        if (recordId == null || recordId.isBlank()) {
            throw new SQLException("Oracle selectSql returned a row with empty RECORD_ID.");
        }
        return new SourceRow(recordId.trim(), fileNetDocumentId == null ? "" : fileNetDocumentId.trim());
    }

    private static DownloadResult downloadDocument(ObjectStore objectStore, String documentId, Path baseDownloadDir)
            throws IOException {

        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("Oracle row has empty FILENET_ID.");
        }

        System.out.printf("Step 4: Fetching FileNet document %s.%n", documentId);
        PropertyFilter propertyFilter = new PropertyFilter();
        propertyFilter.addIncludeProperty(new FilterElement(null, null, null, "DocumentTitle", null));
        propertyFilter.addIncludeProperty(new FilterElement(null, null, null, PropertyNames.CONTENT_ELEMENTS, null));

        Document document = Factory.Document.fetchInstance(objectStore, new Id(normalizeId(documentId)), propertyFilter);

        System.out.printf("Step 5: Creating local folder for FileNet document %s.%n", documentId);
        Path documentDir = baseDownloadDir.resolve(safeFolderName(documentId));
        Files.createDirectories(documentDir);

        ContentElementList contentElements = document.get_ContentElements();
        if (contentElements == null || contentElements.size() == 0) {
            throw new IllegalStateException("FileNet document has no content elements.");
        }

        int downloadedFileCount = 0;
        for (int i = 0; i < contentElements.size(); i++) {
            Object element = contentElements.get(i);
            if (!(element instanceof ContentTransfer)) {
                System.out.printf("Skipping non-downloadable content element %d for document %s.%n", i + 1, documentId);
                continue;
            }

            ContentTransfer contentTransfer = (ContentTransfer) element;
            String fileName = resolveFileName(contentTransfer, document, i + 1);
            Path targetFile = uniquePath(documentDir.resolve(fileName));

            try (InputStream contentStream = contentTransfer.accessContentStream()) {
                Files.copy(contentStream, targetFile);
            }

            downloadedFileCount++;
            System.out.printf("Downloaded FileNet document %s to %s.%n", documentId, targetFile);
        }

        if (downloadedFileCount == 0) {
            throw new IllegalStateException("FileNet document had content elements, but none were downloadable.");
        }
        return new DownloadResult(documentDir, downloadedFileCount);
    }

    private static void updateSuccess(
            java.sql.Connection oracleConnection,
            Properties config,
            SourceRow sourceRow,
            DownloadResult result) throws SQLException {

        try (PreparedStatement updateStatement = oracleConnection.prepareStatement(required(config, "oracle.successUpdateSql"))) {
            updateStatement.setInt(1, result.downloadedFileCount);
            updateStatement.setString(2, result.downloadFolder.toString());
            updateStatement.setString(3, sourceRow.recordId);
            int updatedRows = updateStatement.executeUpdate();
            ensureOneRowUpdated(updatedRows, sourceRow.recordId);
        }
    }

    private static void updateFailure(
            java.sql.Connection oracleConnection,
            Properties config,
            SourceRow sourceRow,
            Exception failure) throws SQLException {

        try (PreparedStatement updateStatement = oracleConnection.prepareStatement(required(config, "oracle.failureUpdateSql"))) {
            updateStatement.setString(1, truncate(failure.getMessage(), 1000));
            updateStatement.setString(2, sourceRow.recordId);
            int updatedRows = updateStatement.executeUpdate();
            ensureOneRowUpdated(updatedRows, sourceRow.recordId);
        }
    }

    private static void ensureOneRowUpdated(int updatedRows, String recordId) throws SQLException {
        if (updatedRows != 1) {
            throw new SQLException("Expected to update exactly one Oracle row for RECORD_ID "
                    + recordId + ", but updated " + updatedRows + ".");
        }
    }

    private static Properties loadConfig(Path configPath) throws IOException {
        Properties properties = new Properties();
        try (InputStream inputStream = Files.newInputStream(configPath)) {
            properties.load(inputStream);
        }
        return properties;
    }

    private static ObjectStore connectObjectStore(Properties config, UserContext userContext) {
        Connection connection = Factory.Connection.getConnection(required(config, "filenet.uri"));
        Subject subject = UserContext.createSubject(
                connection,
                required(config, "filenet.username"),
                required(config, "filenet.password"),
                required(config, "filenet.stanza"));
        boolean pushed = false;
        try {
            userContext.pushSubject(subject);
            pushed = true;

            Domain domain = Factory.Domain.fetchInstance(connection, null, null);
            return Factory.ObjectStore.fetchInstance(domain, required(config, "filenet.objectStore"), null);
        } catch (RuntimeException ex) {
            if (pushed) {
                userContext.popSubject();
            }
            throw ex;
        }
    }

    private static String resolveFileName(ContentTransfer contentTransfer, Document document, int index) {
        String retrievalName = contentTransfer.get_RetrievalName();
        if (retrievalName != null && !retrievalName.isBlank()) {
            return safeFileName(retrievalName);
        }

        String title = document.getProperties().getStringValue("DocumentTitle");
        if (title == null || title.isBlank()) {
            title = "document";
        }
        return safeFileName(title + "-" + index);
    }

    private static Path uniquePath(Path requestedPath) {
        if (!Files.exists(requestedPath)) {
            return requestedPath;
        }

        String fileName = requestedPath.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String baseName = dot > 0 ? fileName.substring(0, dot) : fileName;
        String extension = dot > 0 ? fileName.substring(dot) : "";

        int counter = 2;
        Path parent = requestedPath.getParent();
        Path candidate;
        do {
            candidate = parent.resolve(baseName + "-" + counter + extension);
            counter++;
        } while (Files.exists(candidate));
        return candidate;
    }

    private static String normalizeId(String documentId) {
        String trimmed = documentId.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed;
        }
        return "{" + trimmed + "}";
    }

    private static String safeFolderName(String value) {
        return safePathPart(value).replace("{", "").replace("}", "");
    }

    private static String safeFileName(String value) {
        return safePathPart(value);
    }

    private static String safePathPart(String value) {
        String sanitized = value.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return sanitized.isEmpty() ? "unnamed" : sanitized;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "Unknown error";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static String required(Properties config, String key) {
        String value = config.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required config key: " + key);
        }
        return value.trim();
    }

    private static class SourceRow {
        private final String recordId;
        private final String fileNetDocumentId;

        private SourceRow(String recordId, String fileNetDocumentId) {
            this.recordId = recordId;
            this.fileNetDocumentId = fileNetDocumentId;
        }
    }

    private static class DownloadResult {
        private final Path downloadFolder;
        private final int downloadedFileCount;

        private DownloadResult(Path downloadFolder, int downloadedFileCount) {
            this.downloadFolder = downloadFolder;
            this.downloadedFileCount = downloadedFileCount;
        }
    }
}
