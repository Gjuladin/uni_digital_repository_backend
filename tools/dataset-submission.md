# Local dataset submission review

The Research Data collection uses the native Dataset submission process. Its
normal form captures authors and contributors, affiliations, release and creation
dates, language (including N/A), version, description, methods, scope, related
publications, source material, rights and an access summary. Upload the actual
dataset and supporting documentation before accepting the deposit licence.

## Changes from the review

- Replaced the staff example in Other contributors with **A. Einstein (compiler)**.
- Added optional Existing dataset identifiers (DOI, URI, Other), stored in
  `dc.identifier.doi`, `dc.identifier.uri` and `dc.identifier.other`.
- Added optional Dataset citation, stored in `dc.identifier.citation`.
- The dataset page displays the existing DOI separately and labels URI metadata
  as Dataset links. It also displays the assigned Handle from the archived item,
  even when the URI metadata contains only an external dataset URL.

Use a dataset's existing DOI here. Put a related article's DOI/URL in Related
publications. These fields do not mint a new DOI or create a DSpace version
history. The free-text File access summary documents access; the pencil beside
each uploaded file controls the actual file policies.

## File editor

Dataset file metadata includes Filename, File description / documentation role,
File licence or rights statement, and File licence URL. The same editor offers
Open access, Embargo, and Restricted (repository administrators only). An embargo
uses Grant access from as its public release date. Metadata remains public.
The repository deposit licence and a dataset's reuse licence are separate.

## Verification on 14 October 2026

The checks use a clearly labelled synthetic dataset in a disposable local
collection, submitted by a temporary account without administrator privileges.
No real Research Data record is used for testing.

- Incomplete submissions and submissions without files returned 422.
- Five files (data, README, dictionary, restricted and embargo examples) uploaded.
- Browser Save preserved a file's description, rights, licence URL, primary-file
  choice and future release date; the saved draft remained outside the archive.
- N/A language persisted as `zxx`.
- Deposit created an archived Dataset with its Handle and all submitted metadata.
- Anonymous reads of the record and open files succeeded. Restricted and future
  embargoed downloads returned 401. Open file checksums matched their uploads.
- A depositor without collection membership was denied submission elsewhere;
  anonymous submission was also denied.
- No file reuse licence was automatically changed to CC0.
- 69 focused dataset-page and file-editor tests passed, including DOI rendering
  and the Handle display when identifier metadata has only an external URL.
- The production Angular and server-rendered build succeeded; the local frontend
  runtime was rebuilt and restarted with these changes.
- OAI/Dublin Core and DIM exports, Dataset JSON-LD, search indexing and RSS/Atom
  feeds retained the submitted dataset information.

The synthetic records and accounts are removed after verification. These changes
apply to the local test installation; production deployment is a separate step.

## Lina Miloshevska dataset search

Public searches of her ORCID works, DataCite, Zenodo and publisher pages did not
identify a verified, publicly downloadable dataset suitable for deposit. This
does not establish that she has never created a dataset.

Her coauthored paper *Language Teachers and Crowdsourcing: Insights from a
Cross-European Survey* states in footnote 11 that the raw data and the complete
questionnaire are available on request:
[institutional copy](https://epubl.ktu.edu/object/elaba:68391016/68391016.pdf).
The article PDF is a publication, not the dataset itself. Obtain the actual data,
documentation and applicable reuse terms from the authors before depositing it
in Research Data.
