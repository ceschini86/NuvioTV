# PR Draft: Greek Localization Update

## Title
Greek localization: complete key parity and wording consistency improvements

## Summary
This PR completes the Greek localization against the current base locale.

- Added the previously missing Greek strings across account, sync, library, plugin, search, TMDB, QR login, accessibility, and collections surfaces.
- Completed a full parity pass against `app/src/main/res/values/strings.xml`.
- Preserved placeholders, formatting tokens, and XML validity.
- No feature, UI, architecture, dependency, or refactor changes.

## Scope
Changed file:
- `app/src/main/res/values-el/strings.xml`
- `docs/pr-greek-localization.md`

Not changed:
- Kotlin/Java code
- Build files
- Dependencies
- UI behavior/features
- Other locale files

## Validation
- XML resource file is valid.
- Greek key set matches base key set.
- Placeholders and formatting tokens are preserved.
- No extra Greek-only keys were introduced.

## Policy Alignment
This PR follows the repository contribution policy for **Translation updates**:
- Small scope
- Focused on one problem
- No unrelated changes bundled

## Notes for Reviewers
- Greek locale now has full key parity with the base strings file.
- Missing strings added in the previously untranslated tail of the resource file.
- Translation and consistency pass remains limited to the Greek locale resource file.

## Suggested Commit Message
`chore(i18n): complete Greek locale parity`
