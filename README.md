# AxiomBatchBlueprints

Batch converts Sponge `.schem` files to native Axiom `.bp` blueprints and generates the blueprint thumbnails through Axiom.

## Download

[Download the latest build](https://github.com/mcneds/AxiomBatchBlueprints/releases/tag/latest-build)

Requires Minecraft 26.2, Fabric API, and Axiom. Put the JAR in your instance's `mods` folder.

## Usage

### File picker

```text
/axiombatchbp
```

Opens a file picker where you can select one or multiple `.schem` files. After selecting them, choose the Axiom blueprint folder they should be written to.

The destination picker starts at:

```text
config/axiom/blueprints/
```

Selected files keep their original filename with the extension changed to `.bp`. If multiple selected files would produce the same output name, later ones get `_2`, `_3`, etc.

### Source and destination paths

```text
/axiombatchbp "<source>" "<destination>"
```

`<source>` can be either a single `.schem` file or a directory. Directories are scanned recursively and their folder structure is preserved in the destination.

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

### Batch controls

```text
/axiombatchbp status
/axiombatchbp cancel
```

## Blueprint output

- Uses Axiom's renderer for the thumbnail instead of a placeholder image.
- Thumbnail angle is fixed at 135° yaw / 30° pitch.
- `ContainsAir` is set to `false`, so empty schematic space does not overwrite surrounding blocks when stamping.

## Build from source

Requires Java 25.

```bash
chmod +x build.sh
./build.sh
```

Built JARs are placed in `build/libs/`.
