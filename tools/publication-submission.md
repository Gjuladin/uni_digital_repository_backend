# Publication submissions at UIST

The publication form should capture a complete record during the normal deposit:
choose the collection, describe the work, upload files and set their access, then
accept the repository deposit licence and submit. Fields without information can
remain blank; a published paper's existing metadata and licence should be used.

## Implemented configuration

`dspace/config/submission-forms.xml` supplies the fields rendered by Angular and
accepted by the submission REST API. Both `traditionalpageone` and
`publicationStep` now expose the same publication details; both use
`traditionalpagetwo` for the abstract, links and reuse terms. The corresponding
post-publication editing processes reuse these forms.

| Input | Stored metadata |
| --- | --- |
| Published in | `dc.relation.ispartof` |
| Pages | `dc.format.extent` |
| Identifiers: DOI | `dc.identifier.doi` |
| Identifiers: URI | `dc.identifier.uri` |
| Identifiers: ISBN / ISSN | `dc.identifier.isbn` / `dc.identifier.issn` |
| Full-text URL (repeatable) | `dc.relation.uri` |
| Publication licence or rights statement | `dc.rights` |
| Publication licence URL | `dc.rights.uri` |

All added inputs are optional. No licence is selected automatically. Enter authors
one per row, in publication order, using the repository's initials-plus-surname
format. Retain the full author names in the bibliographic citation where appropriate.
A full-text URL supplements a file upload; it does not attach or download a file.

## File access and embargo

The existing `uploadConfigurationDefault` in
`dspace/config/spring/api/access-conditions.xml` already includes open access,
embargo, administrator-only access, and a time-limited open-access option. These
apply to files. No new whole-item restriction or discoverability step is enabled.
The current embargo configuration permits release dates up to 36 months ahead.

1. Upload the paper and any supporting files.
2. Click the pencil beside a file: **Edit file and access**.
3. Enter its filename and optional description.
4. Select the appropriate access condition.
5. For **Embargo**, set **Grant access from** to the date when the file should
   become publicly downloadable. Leave **Grant access until** unused.
6. Save that file's settings, repeat for other files, then submit the record.

With a file-only embargo, the record remains public while anonymous downloads of
that file are denied until the release date. Do not also add an immediate open
access condition to the same embargoed file. The publication's reuse licence,
file access policy, and non-exclusive repository deposit licence have different
purposes and are entered separately.

## Blank file editor fix

The file-edit component now has an explicit, idempotent `initializeForm()` call
made after the modal caller assigns its file data and form configuration. This
also handles a component initialised before those properties arrive. The upload
help text identifies the pencil control and explains the embargo release date.

## Local verification (14 October 2026)

- Production Angular/SSR build succeeded.
- 21 focused file-editor/file-row tests passed, including late modal data and
  access-condition handling.
- Both modified XML and metadata-field registration were checked. The existing
  unrelated owner-form DTD ordering issue was unchanged.
- A synthetic depositor with no administrator role saved all new fields through
  the normal configured submission steps. DOI remained `dc.identifier.doi` after
  archive, rather than `dc.identifier.other` or ISSN.
- A synthetic local record was deposited. Anonymous metadata returned 200; an
  open file returned 200, while future-embargoed and administrator-only files
  returned 401.
- Agent-browser opened the file editor, selected embargo, entered 2026-11-13,
  saved, and verified that the release date and original filename persisted.
- Test records and accounts are disposable and removed after verification.

Local XML is mounted into the backend container and was loaded by restarting it.
The frontend build was loaded into the local runtime image. Production deployment
has not been performed. Apply the XML configuration and frontend build together
when deploying; restart the REST backend and refresh the browser.

## Public research

The Luna 6 agent at maximum reasoning reviewed these sources:

- [DSpace 10 Embargo documentation](https://wiki.lyrasis.org/spaces/DSDOC10x/pages/408944722/Embargo):
  file embargo support is enabled by default; whole-item access is a separate,
  configurable step. Date limits and available policies are backend settings.
- [Indiana University IUScholarWorks deposit guide](https://guides.libraries.indiana.edu/IUSWUserGuide/depositnew):
  faculty-oriented example of metadata, identifiers, rights and file upload in
  one submission. Its uploaded-file edit window offers embargo and a future
  "Grant access from" date while the record remains public. Its identifier and
  licence conventions are institution-specific; UIST uses its own field mappings.
- [DSpace 8 Add item guide](https://wiki.lyrasis.org/spaces/DSDOC8x/pages/315721126/Add%2Bitem):
  documents file editing, access choices, release dates and expiry dates.
- [DSpace submission XML source](https://github.com/DSpace/DSpace/blob/main/dspace/config/submission-forms.xml)
  and [step configuration](https://github.com/DSpace/DSpace/blob/main/dspace/config/item-submission.xml):
  native field definitions and ordering, including distinct deposit-licence,
  Creative Commons, and item-access steps.
- [DSpace 8 release notes](https://wiki.lyrasis.org/spaces/DSDOC8x/pages/315720591/Release%2BNotes):
  optional Import via DOI can search Crossref and DataCite. This is an alternative
  way to start a new submission, not an automatic change to a draft already being
  edited; imported metadata still needs review. It was not implemented in this change.

A next improvement could start new deposits with DOI lookup, then let the author
review imported metadata and complete the same form. Do not apply licence or
open-access defaults merely because a DOI was imported.
