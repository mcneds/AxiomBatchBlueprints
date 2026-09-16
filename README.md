# AxiomBatchBlueprints

A Fabric client companion mod for **Minecraft 26.2 + Axiom** that batch-converts Sponge `.schem` files into native Axiom `.bp` blueprints using **Axiom's own thumbnail renderer**.

Generic schematic converters can write valid `.bp` files, but they usually embed a placeholder thumbnail. This mod runs inside Minecraft and invokes Axiom's own runtime loading, preview rendering and blueprint writing path instead.

## Current target

- Minecraft **26.2**
- Java **25**
- Fabric Loader **0.19.3+**
- Fabric API **0.156.0+26.2**
- Axiom **5.5.0**

## Build

```bash
chmod +x build.sh
./build.sh
```

Output:

```text
build/libs/axiom-batch-blueprints-0.5.0.jar
```

GitHub Actions also builds the project on pushes and pull requests.

## Install

Copy the built JAR into the same Fabric instance's `mods/` directory as Axiom and Fabric API.

## Usage

### Native file picker

Run:

```text
/axiombatchbp
```

A native system file picker opens. Select **one or multiple `.schem` files** (Ctrl/Shift multi-select as supported by your desktop), then choose a destination folder.

Selected files are written directly into that destination:

```text
example.schem -> example.bp
```

If duplicate output names occur, later files are suffixed (`name_2.bp`, etc.).

### Explicit source + destination

You can also provide a source path and destination directory directly:

```text
/axiombatchbp "<source>" "<destination>"
```

`<source>` may be either:

- a single `.schem` file, or
- a directory, which is scanned recursively.

Relative paths are resolved under:

```text
config/axiom/blueprints/
```

Absolute paths are also accepted.

For directory batches, the source tree is preserved in the destination. Example:

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

## Blueprint behavior

The mod uses Axiom's own Create Blueprint render path:

1. load the Sponge schematic,
2. render a 960 px transparent `BlueprintPreview`,
3. crop/downsample it to 96×96,
4. write the native `.bp` with `BlueprintIo`.

Current fixed defaults are:

```text
thumbnail yaw   = 135
thumbnail pitch = 30
ContainsAir     = false
```

`ContainsAir=false` is useful for Stamp assets because empty schematic space does not carve air into existing terrain.

## Native picker implementation

Axiom already exposes a native folder picker through its `AsyncFileDialogs` helper, but that helper only exposes single-file open. For multi-select, this mod calls the same LWJGL Native File Dialog library that Minecraft 26.2/Axiom use at runtime.

LWJGL is **not bundled** in this mod. Minecraft 26.2 provides it at runtime.

## Status

Early/experimental. Currently tested against Minecraft 26.2 and Axiom 5.5.0.
