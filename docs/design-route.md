# Project design and technical route

Niuma Liquid Glass is an Android Rime/Trime input method. Trime provides the Android service, keyboard framework, settings UI, and JNI bridge; Rime provides schema-driven composition and candidate generation. The project keeps the engine's core input behavior in Rime schema/processor/data configuration and integrates it with a custom Android frontend.

## Input path

The `niuma_glass` schema combines Niuma four-code entry, static dictionary candidates and project-specific Lua processing. User dictionaries and conversion tables are kept separate from generated association and Z-prefix transition data. The keyboard routes key events through Trime's existing Rime session rather than implementing a second input engine. Candidate-code hints are rendered as tappable completions; exact four-code candidates remain selectable and input can continue into the next code.

## Android UI and editing

The glass keyboard surface and candidate strip are implemented in project-owned UI components with configurable appearance. Gesture rules are parsed separately from gesture execution; cursor-window reading and safe deletion are bounded by the editor's surrounding-text capabilities. Clipboard actions remain explicit. This separation allows contract tests to exercise editing rules without a device.

## Voice and English assistance

Offline voice processing is opt-in and local: audio is processed in bounded windows by native code and the installed model files are SHA-256 checked. Final transcript insertion requires an explicit confirmation and is guarded against sensitive editor fields and stale input sessions. The optional English completion path reads a bounded local context and commits through an editor-safe suffix-only path; it does not upload text or use the clipboard.

## Build and validation route

Upstream Trime is pinned at v3.3.13. Native dependencies and Rime data are pinned as gitlinks. Project-generated dictionaries, transitions, association data, model metadata, and staged assets have dedicated tools and source contracts. See [build instructions](build.md), [provenance](provenance.md), and [upstream delta](upstream-delta.md).
