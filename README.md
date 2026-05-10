# FileNet Download Utility

Standalone Java utility that:

1. Connects to Oracle.
2. Runs a select query for source table rows.
3. Reads each row and extracts the FileNet document id.
4. Connects to FileNet Content Platform Engine and fetches the document by id.
5. Creates one local folder per FileNet document id and downloads the document content.
6. Updates the Oracle row as `SUCCESS` or `FAILED`.

## Configure

Edit `config/application.properties`.

The Oracle query must return these exact aliases:

- `RECORD_ID`: primary key or unique row id used for updates
- `FILENET_ID`: FileNet document id/GUID

```sql
select ID as RECORD_ID, FILENET_ID as FILENET_ID
from YOUR_TABLE
where FILENET_ID is not null
  and DOWNLOAD_STATUS is null
```

The success update SQL must accept parameters in this order:

1. downloaded file count
2. local download folder
3. `RECORD_ID`

The failure update SQL must accept parameters in this order:

1. error message
2. `RECORD_ID`

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
- Each Oracle row is committed after its success or failure update.
