# Batch Metadata Import

This directory contains metadata exported from DSpace batch edit, the online UIST community/collection structure,
and a prepared file for importing items through the Dockerized DSpace CLI.

- `batch-export-metadata.csv` is the original batch export copied from the supplied CSV.
- `batch-metadata-import.csv` is prepared for creating new items with `metadata-import`.
- `uist-structure.xml` mirrors the online UIST communities and collections with their original handles.

The import-ready CSV uses `+` in the `id` column so DSpace creates new items. It omits old
`dc.date.accessioned` and `dc.description.provenance[en]` values because DSpace should generate those for the
target repository.

Do not import `batch-export-metadata.csv` directly into an empty repository. It contains item UUIDs from the
online repository and DSpace will treat it as an update to existing items. In a new local database, run the
structure import first, then import `batch-metadata-import.csv`.

Start the backend first:

```sh
docker compose -p d9 up -d
```

Create an administrator account if the target database does not already have one:

```sh
docker compose -p d9 exec -T dspace /dspace/bin/dspace create-administrator \
  -e admin@uist.local -f UIST -l Admin -p admin -c en
```

Create the online UIST community and collection structure:

```sh
docker cp imports/uist-structure.xml dspace:/tmp/uist-structure.xml
docker compose -p d9 exec -T dspace /dspace/bin/dspace structure-builder \
  -e admin@uist.local -f /tmp/uist-structure.xml -o /tmp/uist-structure-result.xml -k
```

Validate the metadata import without changing data:

```sh
docker cp imports/batch-metadata-import.csv dspace:/tmp/batch-metadata-import.csv
docker compose -p d9 exec -T dspace /dspace/bin/dspace metadata-import \
  -f /tmp/batch-metadata-import.csv -e admin@uist.local -v
```

Run the metadata import:

```sh
docker compose -p d9 exec -T dspace /dspace/bin/dspace metadata-import \
  -f /tmp/batch-metadata-import.csv -e admin@uist.local -s
docker compose -p d9 exec -T dspace /dspace/bin/dspace index-discovery -b
```
