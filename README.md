# FileNet Download Utility

Standalone Java utility that:

1. Reads FileNet document ids from an Oracle query.
2. Connects to IBM FileNet Content Platform Engine.
3. Creates one runtime folder per FileNet document id.
4. Streams each document content element to disk.

## Configure

Edit `config/application.properties`.

The Oracle query must return the FileNet document id/GUID in the first column:

```sql
select FILENET_ID from YOUR_TABLE where FILENET_ID is not null
```

## FileNet jars

IBM's Content Engine Java API jars are normally supplied by your FileNet / Content Platform Engine installation or by IBM's Client Download Service. Copy `Jace.jar` into `lib/`. If your environment requires extra FileNet runtime jars, add them to your IDE/runtime classpath as well.

IBM documents the Content Engine Java API and notes that client applications should use the exposed `com.filenet.api` classes. See:

- [IBM Content Engine Java API](https://www.ibm.com/docs/en/filenet-p8-platform/5.2.0?topic=tools-content-engine-java-api)
- [IBM FileNet getting started](https://www.ibm.com/docs/en/filenet-p8-platform/5.7.0?topic=development-getting-started)
- [IBM working with documents](https://www.ibm.com/docs/en/filenet-p8-platform/5.7.0?topic=documents-working)

## Run

```powershell
mvn exec:java -Dexec.args="config/application.properties"
```

## Notes

- If a FileNet id is stored without braces, the utility wraps it as `{id}` before fetching.
- If a document has multiple content elements, each element is downloaded into the same document-id folder.
- If a file name already exists, the utility appends `-2`, `-3`, and so on.
