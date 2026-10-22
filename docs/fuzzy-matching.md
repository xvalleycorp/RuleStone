# Fuzzy matching

RuleStone has functions that score how similar two names are. Use them in rules that screen customers, counterparties or beneficiaries against a list of names, for example an internal watchlist in an AML/KYC process.

```
maxNameSimilarity(customer.fullName, watchlist) >= decimal('0.92')
```

> **Important.** These functions are building blocks, not a sanctions-screening system. They are not certified, they do not handle transliteration between scripts, and they know nothing about aliases, dates of birth or nationalities. A match, or the absence of one, must be handled and reviewed under your institution's AML policy. See [Limitations](#limitations).

- [Functions](#functions)
- [Normalization](#normalization)
- [How scores are computed](#how-scores-are-computed)
- [Writing screening rules](#writing-screening-rules)
- [Choosing a threshold](#choosing-a-threshold)
- [Worked examples](#worked-examples)
- [Performance and limits](#performance-and-limits)
- [Errors](#errors)
- [Limitations](#limitations)

## Functions

| Function | Result | Meaning |
|---|---|---|
| `normalizeName(s)` | `string` | `s` without accents, case or punctuation (see [Normalization](#normalization)). |
| `similarity(a, b)` | `decimal` | Jaro-Winkler similarity of the normalized names, from 0 (nothing in common) to 1 (identical). |
| `nameSimilarity(a, b)` | `decimal` | The higher of `similarity(a, b)` and the similarity of the names with their words sorted, so `Van An Nguyen` matches `Nguyen Van An`. |
| `levenshtein(a, b)` | `int` | Edit distance of the normalized names: the number of single-character insertions, deletions and substitutions needed to turn one into the other. |
| `maxNameSimilarity(name, names)` | `decimal` | The highest `nameSimilarity(name, n)` over the list `names`; 0 for an empty list. |

Every score:

- is a `decimal`, never a floating-point number, so compare it with `decimal('0.92')`, not `0.92`;
- lies between 0 and 1 and has exactly four decimal places, rounded half-even (`0.9611`, `1.0000`);
- is symmetric: `similarity(a, b) == similarity(b, a)`;
- is identical on every machine and Java version, because it is computed with exact integer arithmetic.

All functions compare Unicode code points, so a letter outside the Basic Multilingual Plane counts as one character.

## Normalization

Every function normalizes its arguments first, in this order:

1. Decompose the text (Unicode NFD), so `ễ` becomes `e` followed by two combining marks.
2. Remove all combining marks (Unicode category `M`): accents, tones, cedillas, umlauts.
3. Replace `đ` and `Đ` with `d`. These Vietnamese letters have no Unicode decomposition, so step 2 does not handle them.
4. Lowercase, independently of the server's locale.
5. Replace every character that is not a letter or digit with a space: punctuation, hyphens, apostrophes, tabs, line breaks.
6. Collapse runs of spaces into one and trim both ends.

| Input | `normalizeName` |
|---|---|
| `  NGUYỄN  Văn Đức, Jr. ` | `nguyen van duc jr` |
| `Đặng Thị Ngọc Hân` | `dang thi ngoc han` |
| `José Müller-Lüdenscheidt` | `jose muller ludenscheidt` |

Text in any script keeps its letters: `Владимир Путин` becomes `владимир путин`, which does **not** match `vladimir putin` (see [Limitations](#limitations)). Letters that are not decomposed in Unicode, such as `ø`, `ł`, `æ` and `ß`, are kept as they are; only `đ` is mapped.

Normalization means that, for example, a name typed without diacritics on a mobile app compares as equal to the same name with diacritics on the customer's ID card.

## How scores are computed

### `similarity`: Jaro-Winkler

The Jaro similarity of two strings counts the characters they have in common within a window of `max(len(a), len(b)) / 2 - 1` positions (*m*) and how many of those are out of order (*t*, half the number of out-of-order characters, rounded down):

```
jaro = (m / len(a) + m / len(b) + (m - t) / m) / 3        (0 when m = 0)
```

Winkler's variant rewards names that start the same way. If `jaro` is **greater than 0.7**, it adds a bonus for the common prefix of length `p` (at most 4 characters):

```
similarity = jaro + p × 0.1 × (1 − jaro)
```

This is the definition of Winkler's original `strcmp95` program, also used by Apache Commons Text and the Python `jellyfish` library, so you can cross-check scores with them. The 0.7 threshold means that two strings that share little more than their first letters get no bonus: the Jaro similarity of `abcxyz` and `abcuvw` is 2/3, so `similarity('abcxyz', 'abcuvw')` stays `0.6667` despite the common prefix `abc`.

The result is computed as an exact fraction and rounded once to four decimal places.

**Empty names.** Two names that are empty after normalization (for example `''` and `'-'`) score 1, the usual convention. An empty name and a non-empty one score 0. In a screening rule, guard against blank names explicitly; see [Writing screening rules](#writing-screening-rules).

### `nameSimilarity`: word order

Jaro-Winkler compares strings character by character, so the same name in a different order scores low: `similarity('Nguyen Van An', 'Van An Nguyen')` is only `0.5971`. Names are often written in different orders: family name first or last, middle names moved.

`nameSimilarity` also compares the two names with their words sorted (both of these become `an nguyen van`) and returns the higher of the two scores. Words are sorted by character code. It is never lower than `similarity`.

### `levenshtein`: edit distance

`levenshtein` returns a count, not a score. Use it when the rule must express "at most one typo", which reads more naturally as a distance: `levenshtein(a, b) <= 1`. It does not handle word order and treats a transposition (`ht` ↔ `th`) as two edits.

## Writing screening rules

A complete table that sends a customer to review when their name is close to an entry on a watchlist:

```json
{
  "id": "name_screening",
  "version": "1",
  "description": "Flags customers whose name is close to an entry on the internal watchlist",
  "hitPolicy": "FIRST",
  "inputs": {
    "customer": { "fullName": "string" },
    "watchlist": ["string"]
  },
  "outputs": { "decision": "string", "matchScore": "decimal" },
  "rules": [
    {
      "id": "NAME_MISSING",
      "when": "!has(customer.fullName) || normalizeName(customer.fullName) == ''",
      "then": { "decision": "'REVIEW'" },
      "reasonCode": "NAME_MISSING"
    },
    {
      "id": "WATCHLIST_HIT",
      "when": "maxNameSimilarity(customer.fullName, watchlist) >= decimal('0.92')",
      "then": { "decision": "'REVIEW'", "matchScore": "maxNameSimilarity(customer.fullName, watchlist)" },
      "reasonCode": "WATCHLIST_NAME_MATCH"
    },
    { "id": "NO_ALERT", "when": "true", "then": { "decision": "'NO_ALERT'" } }
  ]
}
```

With the watchlist `["Van An Nguyen", "Tran Van Binh"]`, the customer `NGUYỄN Văn An` matches `WATCHLIST_HIT` with `matchScore` 1.0000, and the customer `Le Thi Hoa` matches `NO_ALERT`. This table is run by the test suite.

Notes on the table:

- **Blank names.** The first rule catches a missing or blank name. Without it, a blank name would score 1 against a blank watchlist entry and 0 against everything else, so a customer without a name would pass silently.
- **Return the score.** `matchScore` lets the analyst who reviews the alert see how close the match was.
- **`maxNameSimilarity` or `exists`.** The same condition can be written as a comprehension:

  ```
  watchlist.exists(w, nameSimilarity(w, customer.fullName) >= decimal('0.92'))
  ```

  Prefer `maxNameSimilarity`: it normalizes the customer's name once instead of once per entry, it returns the score, and it is not subject to the comprehension iteration limit (see [Performance and limits](#performance-and-limits)). Use `exists` when the list holds structs and the condition involves other fields:

  ```
  watchlist.exists(w, nameSimilarity(w.name, customer.fullName) >= decimal('0.85') && w.birthYear == customer.birthYear)
  ```

## Choosing a threshold

The threshold trades **false negatives** (a listed person is not flagged) against **false positives** (an unrelated customer is flagged and an analyst must clear the alert). There is no universally correct value.

- A lower threshold catches more spelling variants, but produces more alerts.
- A higher threshold produces fewer alerts, but misses more variants.

Vietnamese names deserve special care. A few family names (Nguyễn, Trần, Lê, Phạm) and middle names (Văn, Thị) are shared by a large part of the population, so different people often have very similar names, and the Winkler prefix bonus rewards a shared family name. For example `similarity('Nguyen Van An', 'Nguyen Van Binh')` is `0.9446`: at a threshold of 0.92 these two different people match. Conversely, `similarity('Mohammed Ali', 'Muhammad Ali')` is `0.8700` and would be missed at 0.92, while `nameSimilarity` gives `0.9333`.

To choose a threshold:

1. Build a labelled sample from your own data: pairs of names known to be the same person (including real spelling variants, missing diacritics, reordered names, abbreviations such as `T.` for `Thị`) and pairs known to be different people with similar names.
2. Compute the scores with RuleStone and, for each candidate threshold, count the true matches caught and the false alerts raised.
3. Pick the threshold that meets your policy's detection requirement at an alert volume your team can review, and document the decision and the sample.
4. Re-test when the list, the customer base or this function changes.

Combine the name score with other attributes when you have them (date of birth, nationality, ID number), as in the last example of [Writing screening rules](#writing-screening-rules). A lower name threshold combined with a matching date of birth is usually far more precise than a name score alone.

## Worked examples

Every row of this table is evaluated by the test suite (`FuzzyLibraryTest`), and every score quoted on this page appears here.

| Expression | Result |
|---|---|
| `normalizeName('  NGUYỄN  Văn Đức, Jr. ')` | `'nguyen van duc jr'` |
| `normalizeName('Đặng Thị Ngọc Hân')` | `'dang thi ngoc han'` |
| `normalizeName('José Müller-Lüdenscheidt')` | `'jose muller ludenscheidt'` |
| `normalizeName('ACME Ltd. No.7')` | `'acme ltd no 7'` |
| `similarity('MARTHA', 'MARHTA')` | `0.9611` |
| `similarity('DWAYNE', 'DUANE')` | `0.8400` |
| `similarity('DIXON', 'DICKSONX')` | `0.8133` |
| `similarity('abcxyz', 'abcuvw')` | `0.6667` |
| `similarity('Nguyễn Văn An', 'Nguyen Van An')` | `1.0000` |
| `similarity('Nguyen Van An', 'Nguyen Van Anh')` | `0.9857` |
| `similarity('Tran Thi Hoa', 'Tran Thi Hoai')` | `0.9846` |
| `similarity('Nguyen Van An', 'Nguyen Van Binh')` | `0.9446` |
| `similarity('Nguyen Thi Minh Khai', 'Nguyen T. Minh Khai')` | `0.9244` |
| `similarity('Mohamed', 'Mohammed')` | `0.9750` |
| `similarity('Mohammed Ali', 'Muhammad Ali')` | `0.8700` |
| `similarity('Nguyen Van An', 'Van An Nguyen')` | `0.5971` |
| `similarity('Nguyen Van An', 'Le Thi Hoa')` | `0.4859` |
| `similarity('', '-')` | `1.0000` |
| `similarity('Nguyen', '')` | `0.0000` |
| `nameSimilarity('Nguyen Van An', 'Van An Nguyen')` | `1.0000` |
| `nameSimilarity('Smith, John', 'JOHN SMITH')` | `1.0000` |
| `nameSimilarity('Ngô Bảo Châu', 'Châu Ngô Bảo')` | `1.0000` |
| `nameSimilarity('Nguyen Thi Minh Khai', 'Nguyen T. Minh Khai')` | `0.9800` |
| `nameSimilarity('Mohammed Ali', 'Muhammad Ali')` | `0.9333` |
| `nameSimilarity('Nguyen Van An', 'Tran Van Binh')` | `0.6447` |
| `levenshtein('kitten', 'sitting')` | `3` |
| `levenshtein('Lê Văn Tám', 'Le Van Tam')` | `0` |
| `levenshtein('Nguyen Van An', 'Nguyen Van Anh')` | `1` |
| `levenshtein('Nguyen Van An', 'Van An Nguyen')` | `10` |
| `maxNameSimilarity('Nguyễn Văn An', ['Tran Van Binh', 'Van An Nguyen'])` | `1.0000` |
| `maxNameSimilarity('Nguyen Van An', ['Tran Van Binh', 'Le Thi Hoa'])` | `0.6447` |
| `maxNameSimilarity('Nguyen Van An', [])` | `0.0000` |
| `maxNameSimilarity('Nguyen Van An', ['Nguyen Van Binh']) >= decimal('0.92')` | `true` |
| `['Tran Van Binh', 'Van An Nguyen'].exists(w, nameSimilarity(w, 'NGUYỄN Văn An') >= decimal('0.92'))` | `true` |

`MARTHA`/`MARHTA`, `DWAYNE`/`DUANE` and `DIXON`/`DICKSONX` are the reference pairs published with the Jaro-Winkler algorithm.

## Performance and limits

**Cost per comparison.** For two names of lengths *n* and *k*:

- normalization is linear in the length of the text;
- `similarity` is at most proportional to *n* × *k* and in practice much less, because characters are only compared within the match window;
- `nameSimilarity` costs about two `similarity` calls plus sorting the words;
- `levenshtein` is proportional to *n* × *k* and uses memory proportional to the shorter name.

For typical names of 10 to 40 characters a `nameSimilarity` comparison takes a few microseconds on a current laptop, so `maxNameSimilarity` over a 10,000-name list takes in the order of tens of milliseconds. `maxNameSimilarity` performs one `nameSimilarity` per list element and stops early at a perfect score, so its cost grows linearly with the list. List entries are normalized on every evaluation; RuleStone does not cache them between calls. For lists of many thousands of names evaluated on every transaction, measure the latency in your environment and consider a dedicated screening service (see [Limitations](#limitations)).

**Limits.** To protect the engine from oversized inputs:

| Limit | Value | Applies to |
|---|---|---|
| List size | 100,000 elements | `maxNameSimilarity`. A longer list fails the evaluation. |
| Name length | 1,000 characters after normalization | Every argument of `similarity`, `nameSimilarity`, `levenshtein` and `maxNameSimilarity`, including list elements. A longer name fails the evaluation. `normalizeName` has no limit. |
| Comprehension iterations | 10,000 by default | `watchlist.exists(...)` and the other list macros. A longer list fails the evaluation. Change it with `RuleStone.builder().maxIterations(n)`. |

`maxNameSimilarity` is a single function call, not a comprehension, so the iteration limit does not apply to it: it accepts lists up to 100,000 names with the default settings.

## Errors

The following fail the evaluation, and therefore the whole decision (see [Errors](expressions.md#errors) in the expression language reference):

- `maxNameSimilarity() list must not have more than 100000 elements`
- `fuzzy matching arguments must not be longer than 1000 characters after normalization`

Error messages never contain the names being compared, so they can be logged without exposing customer data.

Passing a non-`string` argument, a list that is not a list of strings, or comparing a score with a `double` such as `0.92` is reported when the rules are loaded.

## Limitations

- **Not a certified screening system.** These functions do not replace a sanctions or PEP screening product, the official lists, or the obligations of your institution's AML/CFT programme. They do not maintain lists, aliases, entity relationships or audit trails of list versions.
- **No transliteration.** Names in different scripts are not related: Cyrillic `Путин`, Arabic `محمد` or Chinese characters do not match their Latin transliterations, and different transliteration schemes (`Mohammed`, `Muhammad`, `Mohamad`) only match to the extent their spellings are similar. Transliterate both sides to the same scheme before calling these functions if you need cross-script matching.
- **No phonetics, nicknames or titles.** `Bill` does not match `William`. Titles and suffixes (`Mr`, `Jr`, `Ltd`) are kept as words and affect the score; remove them before comparing if they are not significant for you.
- **Letters without decomposition.** Apart from `đ`, letters such as `ø`, `ł`, `æ` and `ß` are not mapped to Latin letters.
- **Scores are not probabilities.** `0.95` does not mean a 95% chance of being the same person. Scores depend on name length and structure; a threshold is only meaningful after testing on your own data.
- **Review is mandatory.** Treat a match as an alert for human review under your AML policy, never as an automatic conclusion, and treat the absence of a match as "no alert from this rule", not as clearance.
