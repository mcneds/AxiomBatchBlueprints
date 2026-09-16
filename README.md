# AxiomBatchBlueprints

Converts `.schem` files to Axiom `.bp` blueprints with rendered thumbnails.

## Download

[Latest build](https://github.com/mcneds/AxiomBatchBlueprints/releases/tag/latest-build)

Requires Minecraft 26.2, Fabric API, and Axiom.

## Usage

```text
/axiombatchbp
```

Select one or more `.schem` files, then choose the destination folder. The destination picker starts at `config/axiom/blueprints/`.

```text
/axiombatchbp "<source>" "<destination>"
```

`<source>` can be a `.schem` file or a directory. Directories are scanned recursively.

```text
/axiombatchbp status
/axiombatchbp cancel
```

Generated blueprints use `ContainsAir=false`.
