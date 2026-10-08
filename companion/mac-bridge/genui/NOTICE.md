# Generative UI assets

Used by `samrabbit_genui.py` to generate, assemble and render widgets.

From OpenGenerativeUI (https://github.com/CopilotKit/OpenGenerativeUI, commit
457e60cdf7f63fb78004486e1dc7ba753194696d), MIT License, Copyright (c) Atai Barkai; the license text is in
`LICENSE-OpenGenerativeUI` and must ship with these files:

| file | source |
|---|---|
| `ogui-theme.css` | `THEME_CSS`, `packages/design-system/src/index.ts` (verbatim) |
| `ogui-svg-classes.css` | `SVG_CLASSES_CSS` (verbatim) |
| `ogui-form-styles.css` | `FORM_STYLES_CSS` (verbatim) |
| `ogui-importmap.html` | `IMPORTMAP_SCRIPT_TAG` (verbatim) |
| `ogui-design-skill.txt` | `OPEN_GEN_UI_DESIGN_SKILL` (verbatim, reference) |
| `skill.md` | condensed and adapted from `apps/agent/skills/{master-playbook,advanced-visualization,svg-diagrams}/SKILL.md` and `OPEN_GEN_UI_DESIGN_SKILL` |

SamRabbit's own files:

| file | purpose |
|---|---|
| `samrabbit.css` | maps the OGUI tokens onto the R1's SamTheme palette (always dark), layout and static-render rules |
| `bridge.js` | in-document bridge: `widget-resize` / `send-prompt` / `open-link` / `widget-ready` postMessages, the `SamRabbit.chart` Chart.js helper, the jsExpressions runner and render readiness |

The document assembly in `samrabbit_genui.py` (`assemble_document`) is a port of OGUI's
`buildFinalFrameContent` (`apps/app/src/components/generative-ui/open-generative-ui/frame-content.ts`) and
its CSP. Chart.js (MIT) is loaded at run time from cdn.jsdelivr.net and is not vendored.
