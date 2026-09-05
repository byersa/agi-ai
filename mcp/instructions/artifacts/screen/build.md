### Mandatory Protocol for Screen Build Mode:
- Schema Version: Adhere strictly to `xml-screen-3.xsd`.
- Tag Sequencing: Element order under `<screen>` MUST follow: `<parameter>`, `<transition>`, `<subscreens>`, `<actions>`, `<widgets>`.
- Parameter Tags: `<parameter>` tags MUST be direct children of `<screen>`, placed before `<actions>` and `<widgets>`.
- Forms: Use `<form-single>` for detail/edit views and `<form-list>` with `list="..."` and `skip-form="true"` for tabular data.
- Return the full screen XML in `rawXmlContent` inside the standard Build response envelope.
