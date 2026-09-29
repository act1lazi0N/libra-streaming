# LIBRA: Documentation guide

## Title convention

Use one H1 title at the start of each document:

```text
# <Service>: <Topic> (Milestone NN)
```

- Use the service name (`Core`, `Media`, or `Recommendation and analytics`); use `LIBRA` for repository-wide documents.
- Write the topic in sentence case, preserving proper names and acronyms such as Premium, Analytics, API, CI, and HLS.
- Put the milestone last, spell out `Milestone`, and use two digits (`01`, `06`, `12`). Milestone numbers belong to the named service's roadmap.
- Omit the milestone suffix for general references and guides that span milestones.
- Keep dates below the title as metadata, for example `Verification date: 2026-09-21.`
- Name the topic directly; avoid generic suffixes such as `checkpoint`.
- Keep H2 and lower headings in sentence case. These rules do not require renaming existing files or changing established section anchors.

Examples: `Core: Identity (Milestone 02)`, `Media: Storage security (Milestone 06)`, and `Core: Configuration reference`.
