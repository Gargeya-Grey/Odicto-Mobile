# Bundled English spelling vocabulary

`english-40000.txt` is a frequency-ordered subset of SymSpell's
`frequency_dictionary_en_82_765.txt`, from release v6.7.3, pinned to commit
`2ac2df1d01c99711162c4c38a191e522ed4b29c7`.

Source: https://github.com/wolfgarbe/SymSpell/blob/2ac2df1d01c99711162c4c38a191e522ed4b29c7/SymSpell/frequency_dictionary_en_82_765.txt

Odicto's changes: remove the UTF-8 byte-order mark, retain the first 40,000 distinct lowercase ASCII words of
1–24 letters in source order, discard counts and other entries, and write one
word per line in UTF-8. Input ordering retains relative frequency ranking.
No application code from SymSpell is included.

Original dictionary SHA-256 (UTF-8):
`c604e1121e398ae7c7fbf777f11e0a0f2fa66eda932cb9fba1321466cf3acd7b`

Bundled subset SHA-256:
`16d14c4afb5f797371d437aa4e7afd98b0ccfb5c07bba9aa6d17ae28cb6e3ec8`

## Licenses and sources

SymSpell is Copyright (c) 2018 Wolf Garbe, distributed under the MIT License.
The full notice is included in `LICENSE-SymSpell.txt`.

SymSpell documents that this dictionary intersects Google Books Ngram frequency
data with SCOWL spelling word lists:
https://github.com/wolfgarbe/SymSpell/blob/2ac2df1d01c99711162c4c38a191e522ed4b29c7/README.md#frequency-dictionary

Google Books Ngram data is attributed to Google and its contributing authors,
under Creative Commons Attribution 3.0 Unported (CC BY 3.0):
https://creativecommons.org/licenses/by/3.0/
https://creativecommons.org/licenses/by/3.0/legalcode
https://storage.googleapis.com/books/ngrams/books/datasetsv2.html

SCOWL (Spell Checking Oriented Word Lists), collective work Copyright 2000–2018
Kevin Atkinson, includes permissively licensed and public-domain source lists.
Its source-specific notices, including WordNet, AGID, ENABLE, Moby, 12Dicts,
Ispell and VarCon credits, are retained in `SCOWL-NOTICES.txt`. These notices
were obtained from the SCOWLv1 README published by the project:
https://wordlist.aspell.net/scowl_v1-readme/

This attribution and the accompanying license notices must remain with the
bundled vocabulary. The dataset authors do not endorse Odicto. Only static
dictionary data is included: the feature does not send words to a service or
retain what users type.
