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
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class FileNetDownloadUtility {
    public static void main(String[] args) throws Exception {
        Path configPath = args.length > 0
                ? Paths.get(args[0])
                : Paths.get("config", "application.properties");

        Properties config = loadConfig(configPath);
        Path baseDownloadDir = Paths.get(required(config, "download.baseDir"));
        Files.createDirectories(baseDownloadDir);

        List<String> documentIds = fetchDocumentIds(config);
        System.out.printf("Fetched %d FileNet document ids from Oracle.%n", documentIds.size());

        UserContext userContext = UserContext.get();
        ObjectStore objectStore = null;
        try {
            objectStore = connectObjectStore(config, userContext);
            for (String documentId : documentIds) {
                downloadDocument(objectStore, documentId, baseDownloadDir);
            }
        } finally {
            if (objectStore != null) {
                userContext.popSubject();
            }
        }
    }

    private static Properties loadConfig(Path configPath) throws IOException {
        Properties properties = new Properties();
        try (InputStream inputStream = Files.newInputStream(configPath)) {
            properties.load(inputStream);
        }
        return properties;
    }

    private static List<String> fetchDocumentIds(Properties config) throws SQLException {
        List<String> documentIds = new ArrayList<>();
        try (java.sql.Connection connection = DriverManager.getConnection(
                required(config, "oracle.jdbcUrl"),
                required(config, "oracle.username"),
                required(config, "oracle.password"));
             PreparedStatement statement = connection.prepareStatement(required(config, "oracle.query"));
             ResultSet resultSet = statement.executeQuery()) {

            while (resultSet.next()) {
                String documentId = resultSet.getString(1);
                if (documentId != null && !documentId.isBlank()) {
                    documentIds.add(documentId.trim());
                }
            }
        }
        return documentIds;
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

    private static void downloadDocument(ObjectStore objectStore, String documentId, Path baseDownloadDir) {
        try {
            PropertyFilter propertyFilter = new PropertyFilter();
            propertyFilter.addIncludeProperty(new FilterElement(null, null, null, "DocumentTitle", null));
            propertyFilter.addIncludeProperty(new FilterElement(null, null, null, PropertyNames.CONTENT_ELEMENTS, null));

            Document document = Factory.Document.fetchInstance(objectStore, new Id(normalizeId(documentId)), propertyFilter);
            Path documentDir = baseDownloadDir.resolve(safeFolderName(documentId));
            Files.createDirectories(documentDir);

            ContentElementList contentElements = document.get_ContentElements();
            if (contentElements == null || contentElements.size() == 0) {
                System.out.printf("Document %s has no content elements.%n", documentId);
                return;
            }

            for (int i = 0; i < contentElements.size(); i++) {
                Object element = contentElements.get(i);
                if (!(element instanceof ContentTransfer)) {
                    System.out.printf("Document %s content element %d is not downloadable content.%n", documentId, i + 1);
                    continue;
                }

                ContentTransfer contentTransfer = (ContentTransfer) element;
                String fileName = resolveFileName(contentTransfer, document, i + 1);
                Path targetFile = uniquePath(documentDir.resolve(fileName));

                try (InputStream contentStream = contentTransfer.accessContentStream()) {
                    Files.copy(contentStream, targetFile);
                }
                System.out.printf("Downloaded %s -> %s%n", documentId, targetFile);
            }
        } catch (Exception ex) {
            System.err.printf("Failed to download FileNet document %s: %s%n", documentId, ex.getMessage());
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

    private static String required(Properties config, String key) {
        String value = config.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required config key: " + key);
        }
        return value.trim();
    }
}
