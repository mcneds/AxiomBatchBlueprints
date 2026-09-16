# AxiomBatchBlueprints

A Fabric client companion mod for **Minecraft 26.2 + Axiom** that batch-converts Sponge `.schem` files into native Axiom `.bp` blueprints **using Axiom's own thumbnail renderer**.

Generic schematic converters can write valid `.bp` files, but they usually embed a placeholder thumbnail. This mod runs inside Minecraft and invokes Axiom's own runtime rendering/writing path instead.

## What it does

For each `.schem`, the mod uses Axiom to:

1. load the Sponge schematic,
2. render the same 960 px transparent preview used by Create Blueprint,
3. crop/downsample it to Axiom's 96×96 thumbnail,
4. write a native `.bp` with Axiom's own `BlueprintIo`.

No mouse automation, file dialogs, or external renderer are used.

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
build/libs/axiom-batch-blueprints-0.3.0.jar
```

GitHub Actions also builds the project on pushes and pull requests.

## Install

Copy the built JAR into the same Fabric instance's `mods/` directory as Axiom and Fabric API.

## Usage

All relative paths are resolved under:

```text
config/axiom/blueprints/
```

Convert a directory recursively:

```text
/axiombatchbp run "Trees/Dead/source"
```

With no output argument, that creates a sibling directory named:

```text
Trees/Dead/source_bp/
```

Choose an explicit output directory:

```text
/axiombatchbp run "Trees/Dead/source" "Trees/Dead/NativeBP"
```

Absolute paths are also accepted.

You can also convert a single schematic:

```text
/axiombatchbp run "Trees/Dead/oak.schem"
```

or choose its exact BP filename:

```text
/axiombatchbp run "Trees/Dead/oak.schem" "Trees/Dead/oak_custom.bp"
```

Directory structure is preserved. For example:

```text
source/
├── dark/
│   └── large.schem
└── pale/
    └── large.schem
```

becomes:

```text
source_bp/
├── dark/
│   └── large.bp
└── pale/
    └── large.bp
```

By default the displayed Axiom blueprint names include their parent folders, so those become `Dark - Large` and `Pale - Large` instead of two indistinguishable `Large` entries.

## Batch controls

```text
/axiombatchbp status
/axiombatchbp cancel
```

Running `/axiombatchbp` with no subcommand prints command help instead of automatically converting a hard-coded folder.

## Configuration

Current settings:

```text
/axiombatchbp config
```

Change thumbnail angle:

```text
/axiombatchbp config yaw 135
/axiombatchbp config pitch 30
```

Control whether schematic air is meaningful when stamping:

```text
/axiombatchbp config containsAir false
```

Control existing output files:

```text
/axiombatchbp config overwrite true
```

Enable or disable recursive directory scanning:

```text
/axiombatchbp config recursive true
```

Include parent folder names in the Axiom display name:

```text
/axiombatchbp config folderNames true
```

Reset defaults:

```text
/axiombatchbp config reset
```

Settings persist in:

```text
config/axiom-batch-blueprints.properties
```

Default values are:

```properties
yaw=135.0
pitch=30.0
containsAir=false
overwrite=true
recursive=true
folderNames=true
```

For Stamp assets, `containsAir=false` is generally preferable because empty schematic space will not carve air into existing terrain.

## Why the renderer matches Axiom

Axiom's own preview path renders a `BlueprintPreview` at 960 px with a transparent background, then converts/crops it to a 96×96 native image before writing the blueprint. This mod invokes that same runtime path rather than reproducing Minecraft block rendering externally.

Relevant Axiom classes include:

- `BlueprintPreview`
- `BlueprintCreateWindow`
- `BlueprintIo`
- `SchematicLoader`

## Status

Early/experimental. Currently tested against Minecraft 26.2 and Axiom 5.5.0.
