# AxiomBatchBlueprints

Converts Sponge `.schem` files into native Axiom `.bp` blueprints, including rendered thumbnails for Axiom's blueprint browser.

Useful for importing larger schematic sets without opening and saving every file through Axiom manually.

## Download

[Download the latest build](https://github.com/mcneds/AxiomBatchBlueprints/releases/tag/latest-build)

Put `AxiomBatchBlueprints-latest.jar` in your instance's `mods` folder.

### Requirements

- Minecraft 26.2
- Fabric Loader and Fabric API
- Axiom
- Java 25

Currently tested with Axiom 6.0.5.

## Usage

### File picker

```text
/axiombatchbp
```

Select one or more `.schem` files, then choose the destination folder for the generated blueprints.

The destination picker starts at:

```text
config/axiom/blueprints/
```

Normal file selections keep the original filename:

```text
oak.schem        -> oak.bp
oak_large.schem  -> oak_large.bp
```

If selected files have the same filename, later outputs get `_2`, `_3`, and so on instead of replacing each other.

### Source and destination paths

You can also skip the picker:

```text
/axiombatchbp "<source>" "<destination>"
```

`<source>` can be a single `.schem` or a directory. Directory sources are scanned recursively and keep their folder structure in the destination.

Example:

```text
source/
├── dark/
│   ├── small.schem
│   └── large.schem
└── pale/
    └── large.schem
```

becomes:

```text
destination/
├── dark/
│   ├── small.bp
│   └── large.bp
└── pale/
    └── large.bp
```

Relative paths are resolved from `config/axiom/blueprints/`. Absolute paths also work.

### Batch controls

```text
/axiombatchbp status
/axiombatchbp cancel
```

Progress is also written to `latest.log`.

## Blueprint output

Schematics are loaded through Axiom and written with Axiom's own blueprint writer. Thumbnails are rendered through Axiom as well, so the generated files show the structure in the blueprint browser instead of a placeholder image.

Current output settings:

```text
Thumbnail yaw:   135°
Thumbnail pitch: 30°
ContainsAir:     false
```

`ContainsAir=false` is intended for stamp-style assets such as trees and structures, where empty schematic space should not clear surrounding terrain.

For directory imports, parent folders are included in the blueprint display name:

```text
dark/large.schem -> Dark - Large
pale/large.schem -> Pale - Large
```

The source `.schem` files are not modified.

## Build from source

Requires Java 25.

```bash
chmod +x build.sh
./build.sh
```

Built JARs are placed in `build/libs/`.
