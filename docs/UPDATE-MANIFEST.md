# Update Manifest

`update.json` in the repository root is the version manifest read by the in-game update checker. Installed servers fetch it from:

```
https://raw.githubusercontent.com/DeKaeyman/ChunkVeil/main/update.json
```

The update checker only offers a release to a server when the server's exact Minecraft version falls inside one of the release's declared `mc` ranges. Releases without `mc` ranges are never offered.

## Release process

When you publish a new release, add an entry to the top of the `releases` array and push it to `main`. Servers pick it up on their next scheduled check (every 6 hours by default).

## Schema

```json
{
  "releases": [
    {
      "version": "0.4.0",
      "download": "https://github.com/DeKaeyman/ChunkVeil/releases/tag/v0.4.0",
      "severity": "recommended",
      "prerelease": false,
      "mc": [
        { "min": "1.21", "max": "1.21" },
        { "min": "26.0", "max": "26.1" }
      ],
      "notes": "One-line summary shown in the in-game notice."
    }
  ]
}
```

Field notes:

- `version` (required): the plugin version. A leading `v` is tolerated. Versions containing `-` (like `0.4.0-rc.1`) are treated as pre-releases unless `prerelease` says otherwise.
- `download` (required): the URL admins are sent to. Usually the GitHub release tag page.
- `severity` (optional): `critical`, `recommended`, or `optional`. Shown in the console log line.
- `prerelease` (optional): overrides the `-` suffix detection. Pre-releases are only offered to servers with `include-prereleases: true`.
- `mc` (required): one or more `{ "min", "max" }` ranges of supported Minecraft versions.
  - `min` is a normal inclusive lower bound: `"1.21"` allows `1.21` and up.
  - `max` is **prefix-inclusive**: the server version is truncated to the bound's segment count before comparing. `"max": "1.21"` therefore covers every `1.21.x` patch, and `"max": "26.1"` covers `26.1` and `26.1.x` but not `26.2`.
  - Either bound may be omitted for an open end.
- `notes` (optional): one line shown to admins under the update notice.

Keep old releases in the array. Servers running older Minecraft versions use them to find the newest release that still supports their version.
