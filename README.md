# AxiomBatchBlueprints

A small Fabric client companion mod for **Minecraft 26.2 + Axiom** that batch-converts Sponge `.schem` files into native Axiom `.bp` blueprints **using Axiom's own thumbnail renderer**.

This exists because generic schematic converters can write valid `.bp` files, but typically embed a placeholder thumbnail. Axiom's Blueprint Browser expects the rendered thumbnail to already be stored inside the blueprint.

## What it does

The mod runs inside Minecraft and uses Axiom's runtime implementation to:

1. load each Sponge `.schem`,
2. render the same 960 px transparent preview Axiom uses for Create Blueprint,
3. crop/downsample it to Axiom's 96×96 thumbnail,
4. write a native `.bp` using Axiom's own `BlueprintIo`.

No mouse automation, file-dialog automation, or external renderer is used.

## Current target

- Minecraft **26.2**
- Java **25**
- Fabric Loader **0.19.3+**
- Fabric API **0.156.0+26.2**
- Axiom **5.5.0**

The integration intentionally uses reflection for Axiom internals so the project does not need to compile directly against Axiom's private/internal class graph.

## Build

```bash
chmod +x build.sh
./build.sh
```

The first local build downloads Gradle 9.5.1 into `.gradle-bootstrap/`.

Output:

```text
build/libs/axiom-batch-blueprints-0.2.0.jar
```

GitHub Actions also builds the project on pushes and pull requests.

## Install

Copy the built JAR into the same Fabric instance's `mods/` directory as Axiom and Fabric API.

## Current batch layout

The current command looks below `config/axiom/blueprints/` for a folder named:

```text
tree_schems_for_schemconvert/
├── dark/
└── pale_no_birch/
```

It writes native blueprints to a sibling folder:

```text
NativeBP/
├── dark/
└── pale_no_birch/
```

Then, in-game:

```text
/axiombatchbp
```

Optional commands:

```text
/axiombatchbp status
/axiombatchbp cancel
```

The generated blueprints force `ContainsAir = false`, which is useful for Stamp assets because empty schematic space should not carve air into existing terrain.

## Why the renderer matches Axiom

Axiom's own preview path renders a `BlueprintPreview` at 960 px with a transparent background, then converts/crops it to a 96×96 `NativeImage` before writing the blueprint. This mod invokes that same runtime path instead of trying to reproduce Minecraft block rendering externally.

Relevant Axiom code:
- `BlueprintPreview`
- `BlueprintCreateWindow`
- `BlueprintIo`
- `SchematicLoader`

## Status

Early/experimental. The current version is being tested specifically against Minecraft 26.2 and Axiom 5.5.0.
