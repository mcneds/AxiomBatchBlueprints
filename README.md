# AxiomBatchBlueprints

Batch converts Sponge `.schem` files to native Axiom `.bp` blueprints and generates the blueprint thumbnails through Axiom.

## Download

[Download the latest build](https://github.com/mcneds/AxiomBatchBlueprints/releases/tag/latest-build)

Builds are provided for:

- Minecraft 26.2
- Minecraft 26.1.2

Versioned releases are also kept under [Releases](https://github.com/mcneds/AxiomBatchBlueprints/releases).

Requires Fabric API and Axiom. Put the matching JAR in your instance's `mods` folder.

## Usage

### File picker

```text
/axiombatchbp
```

Select one or multiple `.schem` files, then choose the destination folder.

The source picker remembers the last folder you selected. The destination picker always starts at:

```text
config/axiom/blueprints/
```

Existing `.bp` files are not overwritten. If the output name already exists, the new file is named `_2`, `_3`, etc.

### Source and destination paths

```text
/axiombatchbp "<source>" "<destination>"
```

`<source>` can be a single `.schem` file or a directory. Directories are scanned recursively and keep their folder structure in the destination.

Relative paths are resolved from `config/axiom/blueprints/`. Absolute paths also work.

Example:

```text
source/
├── dark/
│   └── large.schem
└── pale/
    └── large.schem
```

becomes:

```text
destination/
├── dark/
│   └── large.bp
└── pale/
    └── large.bp
```

If any target file already exists, the generated copy is numbered instead of replacing it.

### Batch controls

```text
/axiombatchbp status
/axiombatchbp cancel
```

## Blueprint output

- Axiom-rendered 96×96 thumbnail
- 135° yaw / 30° pitch
- `ContainsAir=false`
- Blueprint display names are derived from the source filename; directory batches also include parent folder names

Start, completion and errors are shown in chat. Progress is also written to the Minecraft log with the `[AxiomBatchBP]` prefix.

## Build from source

Requires Java 25.

```bash
chmod +x build.sh
./build.sh
```

The default local build targets Minecraft 26.2. GitHub Actions builds both supported Minecraft versions.
