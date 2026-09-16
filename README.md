# AxiomBatchBlueprints

A Fabric client companion mod for **Minecraft 26.2 + Axiom** that batch-converts Sponge `.schem` files into native Axiom `.bp` blueprints using **Axiom's own thumbnail renderer**.

Generic schematic converters can write valid `.bp` files, but they usually embed a placeholder thumbnail. This mod runs inside Minecraft and invokes Axiom's own runtime loading, rendering, and writing path instead.

## What it does

For every `.schem` in a source directory, recursively:

1. load the Sponge schematic with Axiom,
2. render the same 960 px transparent preview used by Create Blueprint,
3. crop/downsample it to Axiom's 96×96 thumbnail,
4. write a native `.bp` with Axiom's own `BlueprintIo`,
5. preserve the source directory structure inside the destination directory.

No mouse automation or external renderer is used.

## Target

- Minecraft **26.2**
- Java **25**
- Fabric Loader **0.19.3+**
- Fabric API **0.156.0+26.2**
- Axiom **5.5.0**

Axiom integration is resolved reflectively at runtime so the project does not need to compile directly against Axiom's internal dependency graph.

## Build

```bash
chmod +x build.sh
./build.sh
```

The first local build downloads Gradle 9.5.1 into `.gradle-bootstrap/`.

Output:

```text
build/libs/axiom-batch-blueprints-0.4.0.jar
```

GitHub Actions also builds the project on pushes and pull requests.

## Install

Copy the built JAR into the same Fabric instance's `mods/` directory as Axiom and Fabric API.

## Usage

### Native folder picker

Run:

```text
/axiombatchbp
```

Axiom's native system folder picker opens twice:

1. choose the source directory containing `.schem` files,
2. choose the destination directory for generated `.bp` files.

The source directory is scanned recursively.

### Explicit directories

You can skip the dialogs and provide both directories directly:

```text
/axiombatchbp "Trees/Dead/source" "Trees/Dead/NativeBP"
```

Relative paths are resolved under:

```text
config/axiom/blueprints/
```

Absolute paths also work.

Both arguments are always directories: the first is the source tree and the second is the destination tree.

For example:

```text
source/
├── dark/
│   └── large.schem
└── pale/
    └── large.schem
```

becomes:

```text
NativeBP/
├── dark/
│   └── large.bp
└── pale/
    └── large.bp
```

Axiom display names include parent folder names, so those two examples become `Dark - Large` and `Pale - Large`.

Generated blueprints use these fixed Stamp-friendly defaults:

```text
thumbnail yaw:   135°
thumbnail pitch: 30°
ContainsAir:     false
recursive scan:  true
overwrite:       true
```

`ContainsAir=false` prevents empty schematic space from carving holes in existing terrain when the blueprints are used by Stamp.

## Batch controls

```text
/axiombatchbp status
/axiombatchbp cancel
```

## Why the renderer matches Axiom

Axiom's own preview path renders a `BlueprintPreview` at 960 px with a transparent background, then converts/crops it to a 96×96 native image before writing the blueprint. This mod invokes that same runtime path rather than reproducing Minecraft block rendering externally.

Relevant Axiom classes include:

- `BlueprintPreview`
- `BlueprintCreateWindow`
- `BlueprintIo`
- `SchematicLoader`
- `AsyncFileDialogs`

## Status

Early/experimental. Currently tested against Minecraft 26.2 and Axiom 5.5.0.
