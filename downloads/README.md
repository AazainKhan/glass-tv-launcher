# Download codes

`codes.json` is the public index behind Glass's Downloads: a code (a short word or number) that stands for a file URL.
Glass fetches it from GitHub once a day. Nothing else is ever called: no third-party code service.

```json
[
  { "code": "example", "url": "https://example.com/app.apk", "name": "Example app", "sha256": "<64 hex digits>" }
]
```

- `code` and `url` are required; `name` and `sha256` are optional. `sha256` is checked after the download, so use it.
- The URL must be https.
- Add an entry in a pull request. **Entries must be your own files or files their owners allow anyone to redistribute.**
  Nothing that points to pirated or paid-for content is accepted, and entries that do are removed.
- Every download from a code shows its site to the user first, once per site.
