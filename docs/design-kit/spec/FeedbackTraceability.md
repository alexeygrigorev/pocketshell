# Feedback → implementation contract

| Feedback | Design decision | Reference frames |
|---|---|---|
| Terminal stays; no conversations | Existing emulator is the sole session output surface. Agents are programs inside it. | terminal, composer, session-switch |
| Workspace is a folder containing sessions | Persistent host/root/workspace structure; workspace survives zero sessions; never collapse it into one session. | workspaces, workspace, workspace-empty |
| Easily find folders | One root-scoped picker and cross-root workspace search; explicit nested browsing. | workspace-search, add-workspace, folder-browser |
| Start in project roots too | Root menu has Start session here; root sessions appear in an In this root row. | root-actions, root-session |
| Create folders that do not exist | Named parent + folder name + explicit Create; separate session launch. | create-folder, create-folder-error |
| Larger fonts | 28sp screen title, 20sp workspace, 18sp body, 16sp metadata; rows grow and names wrap. | every frame, qa/workspaces-360-200.png |
| Do not repeat name + full path | Only name on root-grouped workspace rows. Paths appear in actual folder navigation or ambiguity cases. | workspaces |
| Show running session kinds | Muted shape + text per kind, duplicate counts, no colored vendor logos. | workspaces, workspace |
| Too overloaded | Flat list, no cards, no giant folder glyph, one primary action, secondary tools in overflow. | workspaces, host-tools, new-session |
| Muted/gray icons, better placement | A gray inline metadata row beneath the prominent name. Indicators are not tiny independent buttons. | workspaces |
| Follow desktop workspace behavior | Distinct root/folder/session identities, stable order, friendly session labels, same ordinary shape-to-agent mapping. | workspaces, reorder, session-switch |
| One design throughout | One catalog, one browser renderer, one token set, native vector/theme generation and shared primitives. | design-system/*, android/* |

## Explicit refinements since the last generated image
The type and spacing are now defined in Android units rather than guessed by an image model. The root button reads **+ Add** to stay quiet, with a full accessible label. Small glyphs are the desktop's existing product-local shapes instead of approximated vendor logos. The repeated session green dots are removed; Connected is the only routine green pip. Whole workspace rows navigate, avoiding ambiguous same-agent icon taps. No Workspaces/Host tab has been added.

The detailed paths that do appear are intentional: a file-browser location, a creation destination, a key fingerprint, or a destructive-action target. They are not repeated on each workspace row.
